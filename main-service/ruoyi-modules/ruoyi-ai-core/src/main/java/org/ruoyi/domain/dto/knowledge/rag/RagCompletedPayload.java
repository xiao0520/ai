// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

/** 完成引用是已复核的实际使用子集；usage 兼容原聊天但不可重复计费。  字段分为四组：   生成结果 — content 与 usage（兼容原聊天协议）   答案状态 — 是否有证据支持的判定   引用 — 已复核的实际使用引用集合   凭证与用量 — 验证票据和执行用量（供 Java 结算） */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagCompletedPayload(
    @JsonProperty(value = "requestId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String requestId,
    @JsonProperty(value = "content", required = true)
    @NotNull @CodePointLength(max = 200000) String content,
    @JsonProperty(value = "usage", required = true)
    @Valid RagGenerationUsage usage,
    @JsonProperty(value = "answerStatus", required = true)
    @NotNull AnswerStatus answerStatus,
    @JsonProperty(value = "citations", required = true)
    @NotNull @Size(max = 100) List<@NotNull @Valid Citation> citations,
    @JsonProperty(value = "validationTicket", required = true)
    @CodePointLength(min = 16, max = 4096) String validationTicket,
    @JsonProperty(value = "executionUsage", required = true)
    @NotNull @Size(max = 1000) List<@NotNull @Valid ExecutionUsageItem> executionUsage
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (answerStatus == null || citations == null
            || citations.stream().anyMatch(java.util.Objects::isNull)) { return true; }
        boolean hasEvidence = answerStatus == AnswerStatus.V_ANSWERED || answerStatus == AnswerStatus.V_PARTIAL;
        return hasEvidence == !citations.isEmpty() && (citations.isEmpty() || validationTicket != null)
            && citations.stream().map(Citation::citationId).distinct().count() == citations.size();
    }

    /** 保留契约定义的线枚举值。 */
    public enum AnswerStatus {
        V_ANSWERED("ANSWERED"),
        V_PARTIAL("PARTIAL"),
        V_NO_EVIDENCE("NO_EVIDENCE"),
        V_NEEDS_CLARIFICATION("NEEDS_CLARIFICATION");
        private final String value;
        AnswerStatus(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static AnswerStatus fromValue(String value) {
            for (AnswerStatus candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
