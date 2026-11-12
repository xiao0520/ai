package org.ruoyi.service.chat;

import org.ruoyi.domain.bo.chat.ChatSendBo;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 用户侧聊天执行网关。
 *
 * 统一编排鉴权、模型路由、上下文预算、额度预占、Python 流式调用及最终结算。
 */
public interface IChatExecutionService {

    /**
     * 校验模型权限和预算后，异步执行普通聊天或 RAG 聊天。
     *
     * @param command 已通过接口参数校验的聊天请求
     * @param userId  当前用户 ID
     * @return 向浏览器推送正文、引用、错误和完成事件的 SSE 连接
     */
    SseEmitter stream(ChatSendBo command, Long userId);
}
