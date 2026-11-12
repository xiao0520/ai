package org.ruoyi.domain.entity.agent;

import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 智能体信息实体
 * <p>
 * 一个智能体聚合默认模型、提示词、工具、Skill 和知识库能力范围等配置。
 * 模型、知识库的实际使用权限仍由角色权限和知识库 ACL 决定。
 *
 * @author xiaoqh
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_info")
public class Agent extends BaseEntity {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 智能体编码
     */
    private String agentCode;

    /**
     * 智能体名称
     */
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
    private Long modelId;

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
    private String suggestedQuestions;

    /**
     * 执行策略
     */
    private String executionPolicy;

    /**
     * 技能名称列表
     */
    private String skillNames;

    /**
     * 知识库ID列表
     */
    private String knowledgeIds;

    /**
     * 状态
     */
    private String status;

    /**
     * 备注
     */
    private String remark;
    /**
     * 删除标志
     */
    @TableLogic
    private String delFlag;

}
