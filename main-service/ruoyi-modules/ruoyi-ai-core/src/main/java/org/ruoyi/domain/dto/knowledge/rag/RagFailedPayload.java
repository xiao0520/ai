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

/** RAG 失败终态包含已发生用量，避免异常路径漏记调用成本。  与普通聊天 FailedPayload 的区别：   - error_code 使用 RAG 统一错误码（ErrorCode），范围更广   - 增加 execution_usage 上报已发生的模型调用成本 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagFailedPayload(
    @JsonProperty(value = "requestId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String requestId,
    @JsonProperty(value = "errorCode", required = true)
    @NotNull ErrorCode errorCode,
    @JsonProperty(value = "executionUsage", required = true)
    @NotNull @Size(max = 1000) List<@NotNull @Valid ExecutionUsageItem> executionUsage
) {

    /** 保留契约定义的线枚举值。 */
    public enum ErrorCode {
        V_VALIDATION("VALIDATION"),
        V_AUTHORIZATION("AUTHORIZATION"),
        V_PROVIDER_AUTHORIZATION("PROVIDER_AUTHORIZATION"),
        V_MODEL_UNAVAILABLE("MODEL_UNAVAILABLE"),
        V_RATE_LIMITED("RATE_LIMITED"),
        V_CANCELLED("CANCELLED"),
        V_TIMEOUT("TIMEOUT"),
        V_INTERNAL("INTERNAL"),
        V_KNOWLEDGE_NOT_READY("KNOWLEDGE_NOT_READY"),
        V_SCOPE_EXPIRED("SCOPE_EXPIRED"),
        V_VECTOR_UNAVAILABLE("VECTOR_UNAVAILABLE"),
        V_EMBEDDING_UNAVAILABLE("EMBEDDING_UNAVAILABLE"),
        V_RERANK_UNAVAILABLE("RERANK_UNAVAILABLE"),
        V_DOCUMENT_UNSUPPORTED("DOCUMENT_UNSUPPORTED"),
        V_PARSE_FAILED("PARSE_FAILED"),
        V_INDEX_MISMATCH("INDEX_MISMATCH"),
        V_LEASE_EXPIRED("LEASE_EXPIRED"),
        V_STALE_ATTEMPT("STALE_ATTEMPT"),
        V_BUDGET_EXCEEDED("BUDGET_EXCEEDED"),
        V_CITATION_INVALID("CITATION_INVALID"),
        V_TOOL_REJECTED("TOOL_REJECTED"),
        V_TOOL_CREDENTIAL_MISSING("TOOL_CREDENTIAL_MISSING"),
        V_TOOL_TIMEOUT("TOOL_TIMEOUT");
        private final String value;
        ErrorCode(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static ErrorCode fromValue(String value) {
            for (ErrorCode candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
