package org.ruoyi.service.token;

import org.ruoyi.domain.dto.token.ChatTokenReservation;
import org.ruoyi.domain.vo.model.ModelVo;

/**
 * 聊天模型调用的额度预占、结算与释放能力。
 */
public interface IAiChatTokenService {

    /**
     * 按预计输入量和模型最大输出Token上限预占本次可用额度。
     *
     * @param requestId 调用请求标识
     * @param userId 用户主键
     * @param sessionId 会话主键
     * @param agentId 智能体主键
     * @param model 已校验的模型 Profile
     * @param estimatedInputTokens 根据本轮完整消息估算的输入 Token 上界
     * @return 已预占的额度上下文
     */
    ChatTokenReservation reserve(String requestId, Long userId, Long sessionId, Long agentId, ModelVo model,
                                 long estimatedInputTokens);

    /** 为最多 modelTurns 次模型调用预占总额度，仅普通聊天工具分支使用。 */
    ChatTokenReservation reserve(String requestId, Long userId, Long sessionId, Long agentId, ModelVo model,
                                 long estimatedInputTokens, int modelTurns);

    /**
     * 使用模型返回的真实用量结算账户并写入成功明细。
     *
     * @param reservation 额度预占上下文
     * @param inputTokens 实际输入 Token 数
     * @param outputTokens 实际输出 Token 数
     */
    void settle(ChatTokenReservation reservation, long inputTokens, long outputTokens);

    /** 失败前已产生模型用量时，按真实用量扣费并记录失败终态。 */
    void settleFailed(ChatTokenReservation reservation, long inputTokens, long outputTokens, String errorCode);

    /**
     * 调用失败或取消时释放预占额度并写入失败明细。
     *
     * @param reservation 额度预占上下文
     * @param errorCode 失败原因编码
     */
    void release(ChatTokenReservation reservation, String errorCode);
}
