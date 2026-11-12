package org.ruoyi.integration.kafka;

import jakarta.validation.Validator;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 数据契约工具与 Kafka 开关独立，关闭消息消费时仍可校验任务 DTO。 */
@Configuration
public class KnowledgeContractConfiguration {
    @Bean
    public RagContractJson knowledgeContractJson(Validator validator) {
        return new RagContractJson(validator);
    }
}