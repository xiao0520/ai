// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import org.hibernate.validator.constraints.CodePointLength;

/** 同一 attempt 重复投递时，消息标识与全部执行快照保持不变。  字段按职责分为四层：   路由 — Kafka 分区 key 与链路追踪   任务 — 入库/清理/对账的不可变参数   快照 — 索引和目标文档的发布版本固化   预算 — 仅入库任务携带的 Token 上限 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeJobCommand(
    @JsonProperty(value = "contractVersion", required = true)
    @NotNull ContractVersion contractVersion,
    @JsonProperty(value = "messageId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String messageId,
    @JsonProperty(value = "requestId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String requestId,
    @JsonProperty(value = "jobId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String jobId,
    @JsonProperty(value = "knowledgeId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String knowledgeId,
    @JsonProperty(value = "attemptId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String attemptId,
    @JsonProperty(value = "generationId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String generationId,
    @JsonProperty(value = "attemptNumber", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "100", inclusive = true) Integer attemptNumber,
    @JsonProperty(value = "createdAt", required = true)
    @NotNull OffsetDateTime createdAt,
    @JsonProperty(value = "deadline", required = true)
    @NotNull OffsetDateTime deadline,
    @JsonProperty(value = "task", required = true)
    @NotNull @Valid KnowledgeJobCommandTask task,
    @JsonProperty(value = "indexSnapshot", required = true)
    @NotNull @Valid IndexSnapshot indexSnapshot,
    @JsonProperty(value = "documentProcessingSnapshot", required = true)
    @Valid DocumentProcessingSnapshot documentProcessingSnapshot,
    @JsonProperty(value = "executionBudget", required = true)
    @Valid KnowledgeExecutionBudget executionBudget
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (knowledgeId == null || task == null || indexSnapshot == null) { return true; }
        if (!knowledgeId.equals(indexSnapshot.knowledgeId())) { return false; }
        String target = task instanceof KnowledgeDocumentTask documentTask
            ? documentTask.targetIndexVersion()
            : task instanceof KnowledgeCleanupTask cleanupTask
                ? cleanupTask.targetIndexVersion()
                : ((KnowledgeReconcileTask) task).targetIndexVersion();
        if (target != null && !target.equals(indexSnapshot.indexVersion())) { return false; }
        if (!(task instanceof KnowledgeDocumentTask)) {
            return documentProcessingSnapshot == null && executionBudget == null;
        }
        if (documentProcessingSnapshot == null || executionBudget == null
            || !documentProcessingSnapshot.textEmbedding().profile().equals(indexSnapshot.embedding().profile())) {
            return false;
        }
        var indexMultimodal = indexSnapshot.multimodalEmbedding();
        var documentMultimodal = documentProcessingSnapshot.multimodalEmbedding();
        return documentMultimodal == null || (indexMultimodal != null
            && indexMultimodal.profile().equals(documentMultimodal.profile()));
    }

    /** 保留契约定义的线枚举值。 */
    public enum ContractVersion {
        V_KNOWLEDGE_KAFKA_V1("knowledge-kafka-v1");
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
}
