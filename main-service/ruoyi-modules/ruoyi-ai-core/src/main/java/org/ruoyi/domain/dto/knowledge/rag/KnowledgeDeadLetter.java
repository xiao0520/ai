// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigInteger;
import org.hibernate.validator.constraints.CodePointLength;
import org.ruoyi.domain.dto.knowledge.RagIntegerSerializer;

/** 毒消息只记录定位和摘要；原记录仍保留于原 Topic，避免复制正文或票据。  当 Python 无法解析或校验 Kafka 消息时，将消息的 Kafka 元信息和哈希写入 死信 Topic，供运维排查。原始消息体不复制，防止敏感信息扩散。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeDeadLetter(
    @JsonProperty(value = "contractVersion", required = true)
    @NotNull ContractVersion contractVersion,
    @JsonProperty(value = "sourceTopic", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String sourceTopic,
    @JsonProperty(value = "sourcePartition", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer sourcePartition,
    @JsonProperty(value = "sourceOffset", required = true) @JsonSerialize(using = RagIntegerSerializer.class)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "9223372036854775807", inclusive = true) BigInteger sourceOffset,
    @JsonProperty(value = "payloadHash", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String payloadHash,
    @JsonProperty(value = "reason", required = true)
    @NotNull Reason reason
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

    /** 保留契约定义的线枚举值。 */
    public enum Reason {
        V_INVALID_MESSAGE("INVALID_MESSAGE"),
        V_KEY_MISMATCH("KEY_MISMATCH"),
        V_UNSUPPORTED_RESULT("UNSUPPORTED_RESULT");
        private final String value;
        Reason(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Reason fromValue(String value) {
            for (Reason candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
