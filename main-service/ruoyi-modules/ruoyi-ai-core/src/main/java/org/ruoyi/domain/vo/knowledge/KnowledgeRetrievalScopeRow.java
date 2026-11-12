package org.ruoyi.domain.vo.knowledge;

import lombok.Data;

/** 已发布知识库及活动索引的授权范围查询结果。 */
@Data
public class KnowledgeRetrievalScopeRow {
    private Long knowledgeId;
    private Integer permissionRevision;
    private Long indexVersionId;
    private String indexVersionCode;
    private Integer manifestRevision;
    private Integer memberCount;
    private String indexSnapshot;
    private String retrievalConfig;
}
