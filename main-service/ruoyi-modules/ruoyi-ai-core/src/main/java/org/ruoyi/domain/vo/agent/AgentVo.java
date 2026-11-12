package org.ruoyi.domain.vo.agent;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 智能体视图对象
 * <p>
 * MCP 工具来自关联表，Skill 和知识库 JSON 数组由 AgentServiceImpl 展开。
 *
 * @author xiaoqh
 */
@Data
@ExcelIgnoreUnannotated
public class AgentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @ExcelProperty(value = "智能体ID")
    private Long id;

    /**
     * 智能体编码
     */
    @ExcelProperty(value = "Agent编码")
    private String agentCode;

    /**
     * 智能体名称
     */
    @ExcelProperty(value = "智能体名称")
    private String agentName;

    /**
     * 智能体类型
     */
    @ExcelProperty(value = "Agent类型")
    private String agentType;

    /**
     * 智能体描述
     */
    @ExcelProperty(value = "智能体描述")
    private String agentDescribe;

    /**
     * 智能体展示图标
     */
    private String agentShow;

    /**
     * 模型ID
     */
    @ExcelProperty(value = "绑定模型ID")
    private Long modelId;

    /**
     * 模型ID列表
     */
    private List<Long> modelIds;

    /**
     * 模型选择策略
     */
    private String modelSelectPolicy;

    /**
     * 知识库范围策略
     */
    private String knowledgeScopePolicy;

    /**
     * 模型名称
     */
    @ExcelProperty(value = "绑定模型")
    private String modelName;

    /**
     * 模型名称列表
     */
    private List<String> modelNames;

    /**
     * 是否启用深度思考
     */
    @ExcelProperty(value = "深度思考")
    private String enableThinking;

    /**
     * 系统提示词
     */
    private String systemPrompt;

    /**
     * 欢迎语
     */
    private String welcomeMessage;

    /**
     * 建议问题列表
     */
    private List<String> suggestedQuestions;

    /**
     * 执行策略
     */
    private String executionPolicy;

    /**
     * MCP工具ID列表
     */
    private List<Long> mcpToolIds;

    /**
     * MCP工具名称列表
     */
    private List<String> mcpToolNames;

    /**
     * 技能名称列表
     */
    private List<String> skillNames;

    /**
     * 知识库ID列表
     */
    private List<Long> knowledgeIds;

    /**
     * 知识库名称列表
     */
    private List<String> knowledgeNames;

    /**
     * 状态
     */
    @ExcelProperty(value = "状态")
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    @ExcelProperty(value = "创建时间")
    private Date createTime;

    /**
     * 更新时间
     */
    @ExcelProperty(value = "更新时间")
    private Date updateTime;

}
