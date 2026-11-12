package org.ruoyi.domain.vo.skill;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * Skill Bundle 配置视图。
 */
@Data
@ExcelIgnoreUnannotated
public class SkillDefinitionVo implements Serializable {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 技能名称
     */
    @ExcelProperty(value = "Skill编码")
    private String skillName;
    /**
     * 显示名称
     */
    @ExcelProperty(value = "Skill名称")
    private String displayName;
    /**
     * 描述
     */
    @ExcelProperty(value = "能力说明")
    private String description;
    /**
     * 运行时
     */
    @ExcelProperty(value = "运行时")
    private String runtime;
    /**
     * 入口点
     */
    @ExcelProperty(value = "入口")
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
    @ExcelProperty(value = "状态")
    private String status;
    /**
     * Agent数量
     */
    private Long agentCount;
    /**
     * 创建时间
     */
    private Date createTime;
    /**
     * 更新时间
     */
    private Date updateTime;
}
