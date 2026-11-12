package org.ruoyi.integration.kafka;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeDeadLetter;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeJobEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Kafka 结果只在 Java 业务事务提交或毒消息定位成功后确认消费。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "knowledge.kafka", name = "enabled", havingValue = "true")
public class KnowledgeResultListener {
    private final KnowledgeKafkaService service;
    private final KnowledgeKafkaProperties properties;
    private final RagContractJson codec;
    private final KafkaTemplate<String, byte[]> knowledgeKafkaTemplate;

    @KafkaListener(id = "knowledge-job-results", topics = "${knowledge.kafka.result-topic:knowledge.job.result.v1}",
        groupId = "${knowledge.kafka.result-group:ruoyi-knowledge-job-result-consumer}",
        containerFactory = "knowledgeKafkaListenerContainerFactory")
    public void onResult(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) throws IOException {
        KnowledgeJobEvent message;
        try {
            message = codec.read(record.value() == null ? new byte[0] : record.value(), KnowledgeJobEvent.class);
            Long.parseLong(message.jobId());
        } catch (IOException | IllegalArgumentException e) {
            deadLetter(record, KnowledgeDeadLetter.Reason.V_INVALID_MESSAGE);
            acknowledgment.acknowledge();
            return;
        }
        if (!message.jobId().equals(record.key())) {
            deadLetter(record, KnowledgeDeadLetter.Reason.V_KEY_MISMATCH);
        } else {
            KnowledgeKafkaService.EventDisposition disposition = service.accept(message);
            if (disposition == KnowledgeKafkaService.EventDisposition.REJECTED) {
                deadLetter(record, KnowledgeDeadLetter.Reason.V_UNSUPPORTED_RESULT);
            }
        }
        // accept 经 Spring 事务代理返回后数据库已提交；异常会交给停止型错误处理器保留 offset。
        acknowledgment.acknowledge();
    }

    private void deadLetter(ConsumerRecord<String, byte[]> record, KnowledgeDeadLetter.Reason reason) throws IOException {
        KnowledgeDeadLetter marker = new KnowledgeDeadLetter(KnowledgeDeadLetter.ContractVersion.V_KNOWLEDGE_KAFKA_V1,
            record.topic(), record.partition(), BigInteger.valueOf(record.offset()),
            DigestUtil.sha256Hex(record.value() == null ? new byte[0] : record.value()), reason);
        try {
            knowledgeKafkaTemplate.send(properties.getDlqTopic(), record.key(), codec.write(marker))
                .get(properties.getSendTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka 死信投递被中断");
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka 死信投递未确认");
        }
        log.warn("知识库结果被拒绝 topic={} partition={} offset={} reason={}",
            record.topic(), record.partition(), record.offset(), reason);
    }
}