package org.ruoyi.service.chat;

import org.ruoyi.domain.bo.chat.ChatSendBo;
import org.ruoyi.domain.dto.chat.ChatModelDecision;

/**
 * 聊天模型选择服务。
 *
 * <p>固定模式校验用户指定模型；自动模式只能从 Java 已授权并冻结的候选范围内选择模型。</p>
 */
public interface IChatModelRoutingService {

    /**
     * 解析一次聊天最终应使用的模型。
     *
     * @param command 聊天请求，包含选择模式、可选模型 ID、Agent 和消息内容
     * @param userId  当前用户 ID
     * @return 已通过权限和运行配置校验的模型决策
     */
    ChatModelDecision resolve(ChatSendBo command, Long userId);
}