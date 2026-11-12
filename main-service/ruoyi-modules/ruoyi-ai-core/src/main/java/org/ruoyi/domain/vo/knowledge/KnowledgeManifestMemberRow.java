package org.ruoyi.domain.vo.knowledge;

import lombok.Data;

/** Java 已验收且当前仍可检索的索引成员。 */
@Data
public class KnowledgeManifestMemberRow {
    private Long documentId;
    private Long documentVersionId;
    private Long generationId;
    private String documentProcessingSnapshot;
}
