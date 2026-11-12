package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * 知识库逻辑文档。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document")
public class KnowledgeDocument extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
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
}
