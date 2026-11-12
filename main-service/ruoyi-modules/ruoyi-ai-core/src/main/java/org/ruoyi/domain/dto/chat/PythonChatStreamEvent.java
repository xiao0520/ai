package org.ruoyi.domain.dto.chat;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 从 Python SSE 响应中解析出的命名事件。
 *
 * @param event 事件名称
 * @param data  事件 JSON 数据
 */
public record PythonChatStreamEvent(String event, JsonNode data) {
}