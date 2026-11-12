package org.ruoyi.domain.vo.knowledge;

import lombok.Data;

/** 知识库文档数量统计项。 */
@Data
public class KnowledgeDocumentCountVo {

    /** 知识库ID。 */
    private Long knowledgeId;

    /** 逻辑文档数量。 */
    private Integer documentCount;
}
