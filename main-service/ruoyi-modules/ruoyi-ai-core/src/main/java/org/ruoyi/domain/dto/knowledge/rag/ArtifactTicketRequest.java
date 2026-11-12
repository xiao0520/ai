// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 请求刷新已绑定对象的短期票据，不接受任意 URL 或路径。  Java 通过此请求为 Python 获取 OSS 对象的短期访问票据， operation 限定了票据的操作权限。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ArtifactTicketRequest(
    @JsonProperty(value = "contractVersion", required = true)
    @NotNull ContractVersion contractVersion,
    @JsonProperty(value = "requestId", required = true)
    @NotNull @CodePointLength(min = 6, max = 128) String requestId,
    @JsonProperty(value = "attemptId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String attemptId,
    @JsonProperty(value = "generationId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String generationId,
    @JsonProperty(value = "workerId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String workerId,
    @JsonProperty(value = "operation", required = true)
    @NotNull Operation operation,
    @JsonProperty(value = "objectId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String objectId
) {

    /** 保留契约定义的线枚举值。 */
    public enum ContractVersion {
        V_RAG_V1("rag-v1");
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
    public enum Operation {
        V_READ_SOURCE("READ_SOURCE"),
        V_WRITE_MANIFEST("WRITE_MANIFEST"),
        V_READ_MANIFEST("READ_MANIFEST");
        private final String value;
        Operation(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Operation fromValue(String value) {
            for (Operation candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
