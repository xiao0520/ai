// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** jobType 的封闭判别体，未知类型必须拒绝。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "jobType", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = KnowledgeCleanupTask.class, name = "CLEANUP"),
    @JsonSubTypes.Type(value = KnowledgeDocumentTask.class, name = "INGESTION"),
    @JsonSubTypes.Type(value = KnowledgeReconcileTask.class, name = "RECONCILE")
})
public sealed interface KnowledgeJobCommandTask permits KnowledgeCleanupTask, KnowledgeDocumentTask, KnowledgeReconcileTask {
}
