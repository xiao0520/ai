// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** eventType 的封闭判别体，未知类型必须拒绝。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "eventType", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = KnowledgeArtifactsEvent.class, name = "ARTIFACTS_READY"),
    @JsonSubTypes.Type(value = KnowledgeCancelledEvent.class, name = "CANCELLED"),
    @JsonSubTypes.Type(value = KnowledgeFailedEvent.class, name = "FAILED"),
    @JsonSubTypes.Type(value = KnowledgeMaintenanceEvent.class, name = "MAINTENANCE_COMPLETED"),
    @JsonSubTypes.Type(value = KnowledgeProgressEvent.class, name = "PROGRESS"),
    @JsonSubTypes.Type(value = KnowledgeStartedEvent.class, name = "STARTED")
})
public sealed interface KnowledgeJobEventEvent permits KnowledgeArtifactsEvent, KnowledgeCancelledEvent, KnowledgeFailedEvent, KnowledgeMaintenanceEvent, KnowledgeProgressEvent, KnowledgeStartedEvent {
}
