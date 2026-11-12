// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import org.hibernate.validator.constraints.CodePointLength;

/** 取消通过独立 Topic 广播，避免排在同一分区的长任务之后。  Python 消费到 CANCEL 指令后应立即停止当前 attempt 的处理，发出 KnowledgeCancelledEvent，并停止消费同 attempt 的消息。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeJobControl(
    @JsonProperty(value = "contractVersion", required = true)
    @NotNull ContractVersion contractVersion,
    @JsonProperty(value = "messageId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String messageId,
    @JsonProperty(value = "jobId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String jobId,
    @JsonProperty(value = "attemptId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String attemptId,
    @JsonProperty(value = "action", required = true)
    @NotNull Action action,
    @JsonProperty(value = "expiresAt", required = true)
    @NotNull OffsetDateTime expiresAt
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
    public enum Action {
        V_CANCEL("CANCEL");
        private final String value;
        Action(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Action fromValue(String value) {
            for (Action candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
