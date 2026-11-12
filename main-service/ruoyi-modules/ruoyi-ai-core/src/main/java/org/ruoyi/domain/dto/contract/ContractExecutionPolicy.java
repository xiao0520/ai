package org.ruoyi.domain.dto.contract;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 合同审查单次执行限制。 */
public record ContractExecutionPolicy(@NotNull @Min(1) Integer maxInputTokens,
                                      @NotNull @Min(1) @Max(2000000) Integer maxOutputTokens,
                                      @NotNull @Min(1) @Max(300) Integer timeoutSeconds,
                                      @NotNull @Min(1) Integer maxTotalTokens) {
}
