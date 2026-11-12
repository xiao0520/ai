package org.ruoyi.controller.chat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.excel.utils.ExcelUtil;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.chat.ChatSessionBo;
import org.ruoyi.domain.vo.chat.ChatSessionVo;
import org.ruoyi.service.chat.IChatSessionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话会话管理。
 * 会话和消息均由后续运行面写入，管理端仅提供审计查询和归档操作。
 *
 * @author xiaoqh
 * @date 2026-08-31
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat/sessions")
public class ChatSessionController {

    private final IChatSessionService chatSessionService;

    /** 分页查询会话。非平台管理员只能查询本人会话。 */
    @SaCheckPermission("chat:session:list")
    @GetMapping
    public TableDataInfo<ChatSessionVo> list(ChatSessionBo bo, PageQuery pageQuery) {
        if (!LoginHelper.isAdmin()) bo.setUserId(LoginHelper.getUserId());
        return chatSessionService.queryPageList(bo, pageQuery);
    }

    /** 导出会话审计信息。 */
    @SaCheckPermission("chat:session:export")
    @Log(title = "对话会话", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(ChatSessionBo bo, HttpServletResponse response) {
        if (!LoginHelper.isAdmin()) bo.setUserId(LoginHelper.getUserId());
        List<ChatSessionVo> list = chatSessionService.queryList(bo);
        ExcelUtil.exportExcel(list, "对话会话", ChatSessionVo.class, response);
    }

    /** 查询会话详情。 */
    @SaCheckPermission("chat:session:query")
    @GetMapping("/{id}")
    public R<ChatSessionVo> getInfo(@NotNull(message = "会话ID不能为空") @PathVariable Long id) {
        return R.ok(chatSessionService.queryAccessibleById(id));
    }

    /** 归档或恢复会话，不允许物理删除。 */
    @SaCheckPermission("chat:session:archive")
    @Log(title = "对话会话", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/status")
    public R<Void> updateStatus(@NotNull(message = "会话ID不能为空") @PathVariable Long id,
                                @RequestParam @NotBlank(message = "状态不能为空") String status) {
        return chatSessionService.updateStatus(id, status) ? R.ok() : R.fail("会话状态更新失败");
    }
}
