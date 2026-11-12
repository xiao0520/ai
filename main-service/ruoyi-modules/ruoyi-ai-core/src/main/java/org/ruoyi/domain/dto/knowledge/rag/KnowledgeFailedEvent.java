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

/** 执行失败由 Java 决定重试；执行器未实现必须明确失败，禁止伪造成功。  error_code 语义：   EXECUTOR_NOT_READY — worker 启动中 / 依赖未就绪，可重试   COMMAND_EXPIRED     — 消息超过 deadline，不可重试   DOCUMENT_UNSUPPORTED — 文件格式/编码不支持   PARSE_FAILED        — 解析器返回截断或异常   EMBEDDING_UNAVAILABLE — 向量模型不可用   VECTOR_UNAVAILABLE  — Qdrant 写入失败   MODEL_UNAVAILABLE   — 模型供应商不可用   INDEX_MISMATCH      — 快照与索引不一致   BUDGET_EXCEEDED     — Token 预算不足   TIMEOUT             — 执行超时   INTERNAL            — 未知内部错误 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeFailedEvent(
    @JsonProperty(value = "eventType", required = true)
    @NotNull EventType eventType,
    @JsonProperty(value = "errorCode", required = true)
    @NotNull ErrorCode errorCode,
    @JsonProperty(value = "usageItems", required = true)
    @NotNull @Size(max = 1000) List<@NotNull @Valid ExecutionUsageItem> usageItems
) implements KnowledgeJobEventEvent {

    /** 保留契约定义的线枚举值。 */
    public enum EventType {
        V_FAILED("FAILED");
        private final String value;
        EventType(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static EventType fromValue(String value) {
            for (EventType candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }

    /** 保留契约定义的线枚举值。 */
    public enum ErrorCode {
        V_EXECUTOR_NOT_READY("EXECUTOR_NOT_READY"),
        V_COMMAND_EXPIRED("COMMAND_EXPIRED"),
        V_DOCUMENT_UNSUPPORTED("DOCUMENT_UNSUPPORTED"),
        V_PARSE_FAILED("PARSE_FAILED"),
        V_EMBEDDING_UNAVAILABLE("EMBEDDING_UNAVAILABLE"),
        V_VECTOR_UNAVAILABLE("VECTOR_UNAVAILABLE"),
        V_MODEL_UNAVAILABLE("MODEL_UNAVAILABLE"),
        V_INDEX_MISMATCH("INDEX_MISMATCH"),
        V_BUDGET_EXCEEDED("BUDGET_EXCEEDED"),
        V_TIMEOUT("TIMEOUT"),
        V_INTERNAL("INTERNAL");
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
