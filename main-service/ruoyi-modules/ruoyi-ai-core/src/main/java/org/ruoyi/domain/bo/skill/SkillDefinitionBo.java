package org.ruoyi.domain.bo.skill;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.List;

/**
 * Skill Bundle 配置对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillDefinitionBo extends BaseEntity {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 技能名称
     */
    @NotBlank(message = "Skill 名称不能为空")
    private String skillName;
    /**
     * 显示名称
     */
    private String displayName;
    /**
     * 描述
     */
    private String description;
    /**
     * 运行时
     */
    @NotBlank(message = "运行时不能为空")
    private String runtime;
    /**
     * 入口点
     */
    @NotBlank(message = "入口不能为空")
    private String entrypoint;
    /**
     * 制品对象存储ID
     */
    private Long artifactOssId;
    /**
     * 摘要
     */
    private String digest;
    /**
     * 参数模式
     */
    private String parameterSchema;
    /**
     * 输出模式
     */
    private String outputSchema;
    /**
     * 允许的工具ID列表
     */
    private List<Long> allowedToolIds;
    /**
     * 资源限制
     */
    private String resourceLimits;
    /**
     * 状态
     */
    private String status;
}
