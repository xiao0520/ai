package org.ruoyi.domain.dto.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.ruoyi.domain.dto.knowledge.rag.SourceLocation;

import java.util.List;

/** Python 返回并由 Java 持久化的合同审查结果。 */
public record ContractReviewResultDto(
    @NotNull @Pattern(regexp = "1\\.0") String schemaVersion,
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String taskId,
    @NotNull @Valid ContractInformation information,
    @NotNull @Size(max = 100) List<@NotNull @Valid ContractFinding> findings,
    @NotNull @Valid RiskSummary summary,
    @NotNull @Size(max = 100) List<@NotNull String> warnings,
    @NotNull @Pattern(regexp = "contract-review-v1") String engineVersion
) {
    /** 风险统计必须与条目一致，禁止不完整或矛盾结果落库。 */
    @AssertTrue(message = "风险汇总与明细不一致")
    @JsonIgnore
    public boolean isSummaryConsistent() {
        if (findings == null || summary == null || summary.high() == null
            || summary.medium() == null || summary.low() == null
            || findings.stream().anyMatch(item -> item == null)) {
            return false;
        }
        return findings.stream().map(ContractFinding::findingId).distinct().count() == findings.size()
            && summary.high() == findings.stream().filter(item -> "HIGH".equals(item.severity())).count()
            && summary.medium() == findings.stream().filter(item -> "MEDIUM".equals(item.severity())).count()
            && summary.low() == findings.stream().filter(item -> "LOW".equals(item.severity())).count();
    }

    public record ContractInformation(
        @Size(max = 500) String title,
        @Size(max = 1000) String partyA,
        @Size(max = 1000) String partyB,
        @NotNull @Size(max = 20) List<@NotNull String> otherParties,
        @Size(max = 100) String amount,
        @Size(max = 32) String currency,
        @Size(max = 100) String effectiveDate,
        @Size(max = 100) String startDate,
        @Size(max = 100) String endDate,
        @Size(max = 4000) String paymentTerms,
        @Size(max = 4000) String deliveryAndAcceptance,
        @Size(max = 4000) String breachLiability,
        @Size(max = 4000) String terminationTerms,
        @Size(max = 4000) String disputeResolution,
        @Size(max = 2000) String term
    ) { }

    public record ContractFinding(
        @NotNull @Pattern(regexp = "^F-[0-9]{3}$") String findingId,
        @NotNull @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String category,
        @NotNull @Pattern(regexp = "HIGH|MEDIUM|LOW") String severity,
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 4000) String description,
        @NotBlank @Size(max = 2000) String quote,
        @NotNull @Valid ContractLocation location,
        @NotBlank @Size(max = 4000) String suggestion,
        @NotBlank @Size(max = 4000) String basis,
        @NotNull @Size(max = 20) List<@NotNull @Valid KnowledgeEvidence> evidences
    ) { }

    public record ContractLocation(@Min(1) Integer pageNumber, @Size(max = 128) String clauseNumber,
                                   @NotNull @Min(0) Integer blockIndex,
                                   @NotNull @Min(0) Integer startOffset,
                                   @NotNull @Min(1) Integer endOffset) {
        @AssertTrue(message = "合同原文位置区间无效")
        @JsonIgnore
        public boolean isValidRange() {
            return startOffset != null && endOffset != null && endOffset > startOffset;
        }
    }

    public record KnowledgeEvidence(@NotBlank @Size(max = 256) String evidenceId,
                                    @NotBlank @Size(max = 512) String sourceTitle,
                                    @NotBlank @Size(max = 40000) String quote,
                                    @NotNull @Valid SourceLocation location) { }

    public record RiskSummary(@NotNull @Min(0) Integer high, @NotNull @Min(0) Integer medium,
                               @NotNull @Min(0) Integer low) { }
}
