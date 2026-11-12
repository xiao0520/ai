package org.ruoyi.integration.kafka;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Max;
import java.util.stream.Stream;
import org.springframework.util.StringUtils;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 知识库队列部署参数；模型与权限配置不进入 Kafka 静态配置。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "knowledge.kafka")
public class KnowledgeKafkaProperties {
    private boolean enabled;
    @NotBlank private String bootstrapServers = "localhost:9092";
    @NotBlank private String commandTopic = "knowledge.ingestion.command.v1";
    @NotBlank private String resultTopic = "knowledge.job.result.v1";
    @NotBlank private String controlTopic = "knowledge.job.control.v1";
    @NotBlank private String dlqTopic = "knowledge.dlq.v1";
    @NotBlank private String resultGroup = "ruoyi-knowledge-job-result-consumer";
    @Min(1) @Max(100) private int partitions = 3;
    @Min(1) @Max(10) private short replicationFactor = 1;
    @Min(1) @Max(100) private int batchSize = 20;
    @Min(15) @Max(120) private int sendTimeoutSeconds = 30;
    @Min(5) @Max(100) private int publishMaxAttempts = 10;
    @Min(60) @Max(3600) private int commandTtlSeconds = 1200;
    @Min(30) @Max(300) private int executionLeaseSeconds = 60;
    @Min(1) @Max(300) private int retryBaseSeconds = 60;
    @Pattern(regexp = "PLAINTEXT|SASL_PLAINTEXT|SSL|SASL_SSL")
    private String securityProtocol = "PLAINTEXT";
    private String saslMechanism = "PLAIN";
    @ToString.Exclude private String saslJaasConfig;
    private String sslTruststoreLocation;
    @ToString.Exclude private String sslTruststorePassword;
    /** 各类消息不得落到同一 Topic，SASL 开启时必须提供服务凭证。 */
    @AssertTrue(message = "Kafka Topic 必须互异，SASL 配置必须完整")
    public boolean isTransportValid() {
        return !enabled || (Stream.of(commandTopic, resultTopic, controlTopic, dlqTopic).distinct().count() == 4
            && (!securityProtocol.startsWith("SASL") || StringUtils.hasText(saslJaasConfig)));
    }
}
