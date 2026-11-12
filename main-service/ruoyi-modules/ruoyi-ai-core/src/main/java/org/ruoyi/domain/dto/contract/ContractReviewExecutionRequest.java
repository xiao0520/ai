package org.ruoyi.domain.dto.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;
import org.ruoyi.domain.dto.chat.PythonModelProfile;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.knowledge.rag.VisionProfile;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Java 已完成权限裁定后的合同审查执行快照。 */
public record ContractReviewExecutionRequest(
    @NotNull @Pattern(regexp = "1\\.0") String schemaVersion,
    @NotNull @Size(min = 6, max = 128) String requestId,
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String taskId,
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String userId,
    @NotNull @Size(min = 1, max = 255) String fileName,
    @NotNull @Pattern(regexp = "application/pdf|application/vnd\\.openxmlformats-officedocument\\.wordprocessingml\\.document|image/png|image/jpeg") String mediaType,
    @NotNull @Pattern(regexp = "PURCHASE|SALES|SERVICE|OTHER") String contractType,
    @NotNull @Pattern(regexp = "PARTY_A|PARTY_B|NEUTRAL") String partyPosition,
    @CodePointLength(max = 20000) String systemPrompt,
    @NotNull @Valid PythonModelProfile modelProfile,
    @NotNull @Valid ContractExecutionPolicy executionPolicy,
    @Valid RagExecutionScope rag,
    @NotNull @Pattern(regexp = "REVIEW|EXTRACT") String operation,
    @Valid VisionProfile visionProfile,
    @NotNull @Min(1) @Max(32768) Integer visionMaxOutputTokens
) {
    /** 模式决定是否需要知识库，不接受提取任务携带无关授权范围。 */
    @AssertTrue(message = "合同处理模式与知识库范围不一致")
    @JsonIgnore
    public boolean isOperationConsistent() {
        return "EXTRACT".equals(operation) ? rag == null
            : "REVIEW".equals(operation) && rag != null && mediaType != null
                && !mediaType.startsWith("image/");
    }
}
