// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

/** 仅复核当前请求快照的候选；空集合用于签发无命中的受限票据。  purpose 说明复核目的：   MODEL_CONTEXT  — 生成模型上下文，需要可读文本   COMPLETION     — 补全，仅验证有效性   CHUNK_PREVIEW  — 管理后台预览，允许更宽松的校验 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ValidateCandidatesRequest(
    @JsonProperty(value = "contractVersion", required = true)
    @NotNull ContractVersion contractVersion,
    @JsonProperty(value = "requestId", required = true)
    @NotNull @CodePointLength(min = 6, max = 128) String requestId,
    @JsonProperty(value = "candidates", required = true)
    @NotNull @Size(max = 500) List<@NotNull @Valid SourceRef> candidates,
    @JsonProperty(value = "purpose", required = true)
    @NotNull Purpose purpose
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
    public enum Purpose {
        V_MODEL_CONTEXT("MODEL_CONTEXT"),
        V_COMPLETION("COMPLETION"),
        V_CHUNK_PREVIEW("CHUNK_PREVIEW");
        private final String value;
        Purpose(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Purpose fromValue(String value) {
            for (Purpose candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
