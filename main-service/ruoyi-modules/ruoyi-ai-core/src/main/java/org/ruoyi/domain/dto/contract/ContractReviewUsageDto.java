package org.ruoyi.domain.dto.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import java.util.List;

/** Python 阶段与终态事件中的累计计量。 */
public record ContractReviewUsageDto(@NotNull String requestId,
    @NotNull @Size(max = 1000) List<@NotNull @Valid ExecutionUsageItem> executionUsage) {
}
