package org.ruoyi.controller.chat;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.chat.ChatMessageBo;
import org.ruoyi.domain.vo.chat.ChatCitationVo;
import org.ruoyi.domain.vo.chat.ChatMessageVo;
import org.ruoyi.service.chat.IChatCitationService;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.chat.IChatSessionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户侧“我的消息”接口，仅可查看本人会话的消息。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat/my/messages")
public class ChatMyMessageController {

    private final IChatMessageService chatMessageService;
    private final IChatCitationService chatCitationService;
    private final IChatSessionService chatSessionService;

    @SaCheckLogin
    @GetMapping
    public TableDataInfo<ChatMessageVo> list(ChatMessageBo bo, PageQuery pageQuery) {
        if (bo.getSessionId() != null) chatSessionService.queryMyById(bo.getSessionId());
        bo.setUserId(LoginHelper.getUserId());
        return chatMessageService.queryPageList(bo, pageQuery);
    }

    /** 回访引用时重新校验会话归属、知识库权限和来源有效性。 */
    @SaCheckLogin
    @GetMapping("/{messageId}/citations/{citationId}")
    public R<ChatCitationVo> getCitation(@NotNull @PathVariable Long messageId,
                                         @PathVariable String citationId) {
        return R.ok(chatCitationService.queryMyCitation(LoginHelper.getUserId(), messageId, citationId));
    }
    @SaCheckLogin
    @GetMapping("/{id}")
    public R<ChatMessageVo> getInfo(@NotNull(message = "消息ID不能为空") @PathVariable Long id) {
        ChatMessageVo message = chatMessageService.queryById(id);
        if (message == null) return R.fail("消息不存在");
        chatSessionService.queryMyById(message.getSessionId());
        return R.ok(message);
    }
}
