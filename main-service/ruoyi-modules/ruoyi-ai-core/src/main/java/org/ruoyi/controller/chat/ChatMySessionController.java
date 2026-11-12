package org.ruoyi.controller.chat;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.chat.ChatSessionBo;
import org.ruoyi.domain.bo.chat.ChatSessionCreateBo;
import org.ruoyi.domain.bo.chat.ChatSessionRenameBo;
import org.ruoyi.domain.vo.chat.ChatSessionVo;
import org.ruoyi.service.chat.IChatSessionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户侧“我的会话”接口，仅可访问本人会话。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat/my/sessions")
public class ChatMySessionController {

    private final IChatSessionService chatSessionService;

    @SaCheckLogin
    @GetMapping
    public TableDataInfo<ChatSessionVo> list(ChatSessionBo bo, PageQuery pageQuery) {
        bo.setUserId(LoginHelper.getUserId());
        bo.setStatus("ACTIVE");
        return chatSessionService.queryPageList(bo, pageQuery);
    }

    @SaCheckLogin
    @GetMapping("/{id}")
    public R<ChatSessionVo> getInfo(@NotNull(message = "会话ID不能为空") @PathVariable Long id) {
        return R.ok(chatSessionService.queryMyById(id));
    }

    @SaCheckLogin
    @PostMapping
    public R<Long> create(@Validated @RequestBody ChatSessionCreateBo bo) {
        return R.ok(chatSessionService.createMySession(LoginHelper.getUserId(), bo.getSessionTitle()));
    }

    @SaCheckLogin
    @PutMapping("/{id}")
    public R<Void> rename(@NotNull(message = "会话ID不能为空") @PathVariable Long id,
                          @Validated @RequestBody ChatSessionRenameBo bo) {
        return chatSessionService.renameMySession(id, bo.getSessionTitle()) ? R.ok() : R.fail("会话名称更新失败");
    }

    @SaCheckLogin
    @DeleteMapping("/{id}")
    public R<Void> archive(@NotNull(message = "会话ID不能为空") @PathVariable Long id) {
        return chatSessionService.updateStatus(id, "ARCHIVED") ? R.ok() : R.fail("会话归档失败");
    }
}
