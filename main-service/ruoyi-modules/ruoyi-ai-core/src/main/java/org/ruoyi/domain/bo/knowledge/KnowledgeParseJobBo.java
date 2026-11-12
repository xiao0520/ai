package org.ruoyi.domain.bo.knowledge;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * 知识库解析任务查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeParseJobBo extends BaseEntity {
    /**
     * 知识库ID
     */
    private Long knowledgeId;
    /**
     * 文档ID
     */
    private Long documentId;
    /**
     * 文档版本ID
     */
    private Long documentVersionId;
    /**
     * 作业类型
     */
    private String jobType;
    /**
     * 状态
     */
    private String status;
    /**
     * 目标索引版本
     */
    private String targetIndexVersion;
}
