package org.ruoyi.domain.vo.agent;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 用户聊天页可选择的智能体。
 *
 * 仅包含展示信息，不暴露系统提示词、工具、知识库和 Skill 的内部绑定。
 */
@Data
public class ChatMyAgentOptionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 智能体编码，用于用户端选择对应业务入口。
     */
    private String agentCode;

    /**
     * 智能体名称
     */
    private String agentName;

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
     * 模型名称
     */
    private String modelName;

    /**
     * 是否启用深度思考
     */
    private String enableThinking;

    /**
     * 欢迎语
     */
    private String welcomeMessage;

    /**
     * 建议问题列表
     */
    private List<String> suggestedQuestions;
}
