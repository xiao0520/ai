package org.ruoyi.domain.vo.knowledge;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocument;

import java.io.Serializable;
import java.util.Date;

/**
 * 知识库逻辑文档视图。
 */
@Data
@AutoMapper(target = KnowledgeDocument.class)
public class KnowledgeDocumentVo implements Serializable {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 知识库ID
     */
    private Long knowledgeId;
    /**
     * 文档编码
     */
    private String documentCode;
    /**
     * 名称
     */
    private String name;
    /**
     * 来源类型
     */
    private String sourceType;
    /**
     * 状态
     */
    private String status;
    /**
     * 当前版本号
     */
    private Integer currentVersionNo;
    /**
     * 当前版本ID
     */
    private Long currentVersionId;
    /**
     * 创建时间
     */
    private Date createTime;
    /**
     * 更新时间
     */
    private Date updateTime;
}
