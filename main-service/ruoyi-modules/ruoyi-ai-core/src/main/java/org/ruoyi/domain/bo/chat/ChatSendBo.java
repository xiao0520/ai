package org.ruoyi.domain.bo.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.ruoyi.enums.ChatKnowledgeMode;
import org.ruoyi.enums.ChatModelSelectionMode;

/** 用户侧发起对话的请求。 */
@Data
public class ChatSendBo {

    /** 模型选择模式；未传时兼容旧客户端并按指定模型处理。 */
    @NotNull(message = "模型选择模式不能为空")
    private ChatModelSelectionMode modelMode = ChatModelSelectionMode.FIXED;

    /** FIXED 模式下用户选择的已授权模型主键。 */
    private Long modelId;

    /** FIXED 必须携带具体模型；AUTO 由 Java 授权候选和 LangChain 路由决定。 */
    @JsonIgnore
    @AssertTrue(message = "指定模型模式必须提供模型ID")
    public boolean isFixedModelSelectionValid() {
        return modelMode != ChatModelSelectionMode.FIXED || modelId != null;
    }

    /** 会话主键，由用户端创建会话接口返回。 */
    @NotNull(message = "会话ID不能为空")
    private Long sessionId;

    /** 用户本轮输入。 */
    @NotBlank(message = "对话内容不能为空")
    @Size(max = 20_000, message = "对话内容不能超过20000个字符")
    private String content;

    /** 可选智能体主键；Java 据此叠加 Agent 模型白名单和工具需求。 */
    private Long agentId;

    /**
     * 知识来源模式。旧客户端未传时按 knowledgeId 是否存在兼容推断，
     * 避免升级期间把已有知识库请求误当成普通聊天。
     */
    private ChatKnowledgeMode chatMode;

    /** KNOWLEDGE 模式下明确选择的知识库主键。 */
    private Long knowledgeId;

    /** 返回本轮实际模式；旧客户端仅传 knowledgeId 时兼容为知识库模式。 */
    @JsonIgnore
    public ChatKnowledgeMode effectiveChatMode() {
        if (chatMode != null) {
            return chatMode;
        }
        return knowledgeId == null ? ChatKnowledgeMode.GENERAL : ChatKnowledgeMode.KNOWLEDGE;
    }

    /** 知识库模式必须选择知识库，普通模式不能夹带知识库参数。 */
    @JsonIgnore
    @AssertTrue(message = "聊天模式与知识库参数不一致")
    public boolean isKnowledgeSelectionValid() {
        ChatKnowledgeMode effectiveMode = effectiveChatMode();
        return effectiveMode == ChatKnowledgeMode.KNOWLEDGE
            ? knowledgeId != null
            : knowledgeId == null;
    }
    /** 浏览器生成的重复提交标识，供后续幂等策略扩展。 */
    @Size(max = 128, message = "客户端请求标识长度不能超过128")
    private String clientRequestId;
}
