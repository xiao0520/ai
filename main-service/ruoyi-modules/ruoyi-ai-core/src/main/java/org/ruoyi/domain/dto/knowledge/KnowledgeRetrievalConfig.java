package org.ruoyi.domain.dto.knowledge;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 知识库查询时的检索与重排配置；变更不影响已发布向量。 */
public record KnowledgeRetrievalConfig(
    @NotNull Boolean denseEnabled,
    @NotNull Boolean sparseEnabled,
    @NotNull @DecimalMin("1") @DecimalMax("200") Integer recallCount,
    @NotNull @DecimalMin("1") @DecimalMax("1000") Integer rrfK,
    @NotNull Boolean rerankEnabled,
    Long rerankModelId,
    @NotNull @DecimalMin("1") @DecimalMax("200") Integer rerankInputCount,
    @NotNull @DecimalMin("1") @DecimalMax("100") Integer rerankOutputCount,
    @DecimalMin("0.0") @DecimalMax("1.0") Double relevanceThreshold
) {
    /** 检查跨字段约束，不能把无效配置写进执行快照。 */
    public void validate() {
        if (denseEnabled == null || sparseEnabled == null || (!denseEnabled && !sparseEnabled)
            || recallCount == null || recallCount < 1 || recallCount > 200
            || rrfK == null || rrfK < 1 || rrfK > 1000
            || rerankEnabled == null || rerankInputCount == null || rerankInputCount < 1
            || rerankInputCount > 200 || rerankOutputCount == null || rerankOutputCount < 1
            || rerankOutputCount > 100 || (rerankEnabled && rerankInputCount > recallCount)
            || (rerankEnabled && rerankOutputCount > rerankInputCount)
            || (rerankEnabled && (rerankModelId == null || rerankModelId <= 0))
            || (relevanceThreshold != null && !denseEnabled && !rerankEnabled)
            || (relevanceThreshold != null && (relevanceThreshold < 0
                || relevanceThreshold > 1 || !Double.isFinite(relevanceThreshold)))) {
            throw new IllegalArgumentException("检索策略参数无效：请检查召回通道、数量和重排模型");
        }
    }

    /** 未配置的知识库在管理页展示此默认值，保存前仍沿用存量执行策略。 */
    public static KnowledgeRetrievalConfig defaults() {
        return new KnowledgeRetrievalConfig(true, false, 10, 60, false, null, 10, 5, null);
    }
}
