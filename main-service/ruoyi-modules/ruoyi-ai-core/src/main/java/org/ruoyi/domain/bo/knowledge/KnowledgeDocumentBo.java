package org.ruoyi.domain.bo.knowledge;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocument;

/**
 * 知识库逻辑文档业务对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = KnowledgeDocument.class, reverseConvertGenerate = false)
public class KnowledgeDocumentBo extends BaseEntity {
    /**
     * 主键ID
     */
    @NotNull(message = "主键不能为空", groups = EditGroup.class)
    private Long id;
    /**
     * 知识库ID
     */
    @NotNull(message = "知识库ID不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long knowledgeId;
    /**
     * 文档编码
     */
    private String documentCode;
    /**
     * 名称
     */
    @NotBlank(message = "文档名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String name;
    /**
     * 来源类型
     */
    private String sourceType;
    /**
     * 状态
     */
    private String status;
}
