package org.ruoyi.domain.bo.knowledge;

import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;

/**
 * 知识库业务对象 knowledge_info
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = KnowledgeInfo.class, reverseConvertGenerate = false)
public class KnowledgeInfoBo extends BaseEntity {

    /**
     * 主键ID
     */
    @NotNull(message = "主键不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     * 知识库编码
     */
    private String libraryCode;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 名称
     */
    @NotBlank(message = "知识库名称不能为空", groups = { AddGroup.class, EditGroup.class })
    private String name;

    /**
     * 访问范围
     */
    private String accessScope;

    /**
     * 描述
     */
    private String description;

    /**
     * 分割器配置
     */
    private String splitterConfig;

    /**
     * 当前索引版本
     */
    private String currentIndexVersion;

    /**
     * 权限修订号
     */
    private Integer permissionRevision;

    /**
     * 状态
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

}

