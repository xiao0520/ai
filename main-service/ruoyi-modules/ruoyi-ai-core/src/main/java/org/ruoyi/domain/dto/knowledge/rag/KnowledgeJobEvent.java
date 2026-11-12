// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 序列在单个 attempt 内递增；原消息 key 必须与 jobId 一致。  字段分为两层：   路由 — 关联原始命令的标识，确保 Java 可定位到对应 attempt   负载 — 按 event_type 区分的事件体，sequence 保证同 attempt 内有序 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeJobEvent(
    @JsonProperty(value = "contractVersion", required = true)
    @NotNull ContractVersion contractVersion,
    @JsonProperty(value = "messageId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String messageId,
    @JsonProperty(value = "requestId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String requestId,
    @JsonProperty(value = "jobId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String jobId,
    @JsonProperty(value = "attemptId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String attemptId,
    @JsonProperty(value = "generationId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String generationId,
    @JsonProperty(value = "workerId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String workerId,
    @JsonProperty(value = "sequence", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer sequence,
    @JsonProperty(value = "event", required = true)
    @NotNull @Valid KnowledgeJobEventEvent event
) {

    /** 保留契约定义的线枚举值。 */
    public enum ContractVersion {
        V_KNOWLEDGE_KAFKA_V1("knowledge-kafka-v1");
        private final String value;
        ContractVersion(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static ContractVersion fromValue(String value) {
            for (ContractVersion candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
