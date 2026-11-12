package org.ruoyi.domain.entity.skill;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * Java 控制面维护的 Skill Bundle 元数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill")
public class SkillDefinition extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
    private Long id;
    /**
     * 技能名称
     */
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
    private String runtime;
    /**
     * 入口点
     */
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
    private String allowedToolIds;
    /**
     * 资源限制
     */
    private String resourceLimits;
    /**
     * 状态
     */
    private String status;
    /**
     * 删除标志
     */
    @TableLogic
    private String delFlag;
}
