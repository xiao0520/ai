package org.ruoyi.controller.chat;

import lombok.RequiredArgsConstructor;
import cn.dev33.satoken.annotation.SaCheckLogin;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.chat.ChatSendBo;
import org.ruoyi.domain.vo.knowledge.ChatKnowledgeOptionVo;
import org.ruoyi.service.chat.IChatExecutionService;
import org.ruoyi.service.chat.IChatUserOptionService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** 用户侧聊天执行入口，浏览器只连接 Java，不直接访问 Python。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatExecutionController {

    private final IChatExecutionService chatExecutionService;
    private final IChatUserOptionService chatUserOptionService;

    /** 查询当前用户可在聊天页明确选择的知识库。 */
    @SaCheckLogin
    @GetMapping("/knowledge-options")
    public R<List<ChatKnowledgeOptionVo>> knowledgeOptions() {
        return R.ok(chatUserOptionService.queryMyKnowledgeOptions());
    }

    /**
     * 全局拦截器在初始请求线程完成登录校验；不使用方法注解，避免 SSE 异步分发时重复读取已释放的 Sa-Token 上下文。
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@Validated @RequestBody ChatSendBo command) {
        return chatExecutionService.stream(command, LoginHelper.getUserId());
    }
}
