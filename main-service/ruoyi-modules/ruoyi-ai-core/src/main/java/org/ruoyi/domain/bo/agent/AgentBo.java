package org.ruoyi.domain.bo.agent;

import io.github.linpeilie.annotations.AutoMapper;
import io.github.linpeilie.annotations.AutoMapping;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import org.ruoyi.domain.entity.agent.Agent;

import java.io.Serial;
import java.util.List;

/**
 * 智能体业务对象
 *
 * @author xiaoqh
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = Agent.class, reverseConvertGenerate = false)
public class AgentBo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 智能体编码
     */
    @NotBlank(message = "Agent编码不能为空")
    @Size(max = 100, message = "Agent编码不能超过{max}个字符")
    private String agentCode;

    /**
     * 智能体名称
     */
    @NotBlank(message = "智能体名称不能为空")
    @Size(min = 0, max = 200, message = "智能体名称不能超过{max}个字符")
    private String agentName;

    /**
     * 智能体类型
     */
    private String agentType;

    /**
     * 智能体描述
     */
    private String agentDescribe;

    /**
     * 智能体展示图标
     */
    private String agentShow;

    /**
     * 模型ID
     */
    @NotNull(message = "绑定模型不能为空")
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
     * 是否启用深度思考
     */
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
    @AutoMapping(target = "suggestedQuestions", expression = "java(org.ruoyi.common.json.utils.JsonUtils.toJsonString(source.getSuggestedQuestions()))")
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
     * 技能名称列表
     */
    @AutoMapping(target = "skillNames", expression = "java(org.ruoyi.common.json.utils.JsonUtils.toJsonString(source.getSkillNames()))")
    private List<String> skillNames;

    /**
     * 知识库ID列表
     */
    @AutoMapping(target = "knowledgeIds", expression = "java(org.ruoyi.common.json.utils.JsonUtils.toJsonString(source.getKnowledgeIds()))")
    private List<Long> knowledgeIds;

    /**
     * 状态
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

}

