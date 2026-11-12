package org.ruoyi.domain.dto.chat;

import java.util.List;

/**
 * Python 模型执行所需的有序消息输入。
 *
 * @param messages 已完成上下文窗口裁剪的聊天消息
 */
public record PythonChatInput(List<PythonChatMessage> messages) {
}