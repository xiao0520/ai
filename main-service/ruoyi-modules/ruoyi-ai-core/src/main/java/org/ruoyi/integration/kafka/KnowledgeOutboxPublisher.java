package org.ruoyi.integration.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.mapper.knowledge.KnowledgeKafkaJobMapper;
import org.ruoyi.mapper.knowledge.KnowledgeOutboxMapper;
import org.ruoyi.service.knowledge.KnowledgeArtifactMaintenanceService;
import org.ruoyi.service.knowledge.KnowledgeDeletionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 多实例用 Outbox 条件租约竞争发送；Broker 确认后才标记已发送。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "knowledge.kafka", name = "enabled", havingValue = "true")
public class KnowledgeOutboxPublisher {
    private final KnowledgeOutboxMapper outbox;
    private final KnowledgeKafkaJobMapper jobs;
    private final KnowledgeKafkaService service;
    private final KnowledgeArtifactMaintenanceService maintenance;
    private final KnowledgeDeletionService deletion;
    private final KnowledgeKafkaProperties properties;
    private final KafkaTemplate<String, byte[]> knowledgeKafkaTemplate;

    @Scheduled(scheduler = "knowledgeKafkaScheduler", fixedDelayString = "${knowledge.kafka.publish-interval-ms:1000}")
    public void publishDue() {
        List<KnowledgeOutboxMessage> dueMessages = outbox.selectDue(properties.getBatchSize());
        if (dueMessages.isEmpty()) return;
        int sentCount = 0;
        for (KnowledgeOutboxMessage message : dueMessages) {
            String token = UUID.randomUUID().toString();
            if (outbox.claim(message.id(), token, properties.getSendTimeoutSeconds() + 60) != 1) continue;
            try {
                knowledgeKafkaTemplate.send(message.topic(), message.messageKey(), message.payload().getBytes(StandardCharsets.UTF_8))
                    .get(properties.getSendTimeoutSeconds(), TimeUnit.SECONDS);
                service.confirmDelivery(message, token);
                sentCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException e) {
                service.recordDeliveryFailure(message, token);
                log.warn("知识库消息投递未确认 jobId={} messageId={} topic={} messageKey={} sendAttempt={}，已保留重试记录",
                    message.jobId(), message.id(), message.topic(), message.messageKey(), message.sendAttempts() + 1, e);
            }
        }
        log.debug("知识库 Outbox 轮询完成 selected={} sent={}", dueMessages.size(), sentCount);
    }

    @Scheduled(scheduler = "knowledgeKafkaScheduler", fixedDelayString = "${knowledge.kafka.lease-scan-interval-ms:10000}")
    public void recoverExpired() {
        for (Long id : jobs.selectExpired(properties.getBatchSize())) service.expire(id);
    }

    @Scheduled(scheduler = "knowledgeKafkaScheduler", fixedDelayString = "${knowledge.kafka.maintenance-interval-ms:60000}")
    public void maintainArtifacts() {
        service.scheduleMaintenance();
        maintenance.cleanupManifests(properties.getBatchSize());
    }

    /** 源文件重试与数据库物理清理由独立轮询执行，避免被 Qdrant 对账异常长期阻断。 */
    @Scheduled(scheduler = "knowledgeKafkaScheduler",
        fixedDelayString = "${knowledge.kafka.deletion-maintenance-interval-ms:60000}")
    public void maintainDeletedResources() {
        deletion.cleanupDeletedSourceFiles(properties.getBatchSize());
        deletion.purgeDeletedResources(properties.getBatchSize());
    }
}
