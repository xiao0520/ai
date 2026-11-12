package org.ruoyi.controller.token;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.idempotent.annotation.RepeatSubmit;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.domain.bo.token.AiRoleModelSaveBo;
import org.ruoyi.domain.bo.token.AiTokenAccountCommandBo;
import org.ruoyi.domain.bo.token.AiTokenAccountQueryBo;
import org.ruoyi.domain.bo.token.AiTokenQuotaLogQueryBo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.domain.vo.token.AiRoleModelPermissionVo;
import org.ruoyi.domain.vo.token.AiTokenQuotaLogVo;
import org.ruoyi.domain.vo.token.AiTokenUserOptionVo;
import org.ruoyi.domain.vo.token.AiUserTokenAccountVo;
import org.ruoyi.service.token.IAiRoleModelPermissionService;
import org.ruoyi.service.token.IAiTokenAccountService;
import org.ruoyi.system.domain.bo.SysRoleBo;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Token配额与角色模型权限管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/token/quota")
public class AiTokenQuotaController extends BaseController {

    private final IAiTokenAccountService accountService;
    private final IAiRoleModelPermissionService roleModelService;

    /**
     * 分页查询用户Token额度账户列表
     */
    @SaCheckPermission("token:user:list")
    @GetMapping("/users")
    public TableDataInfo<AiUserTokenAccountVo> users(AiTokenAccountQueryBo query, PageQuery pageQuery) {
        return accountService.queryPage(query, pageQuery);
    }

    /**
     * 分页查询用户选项列表
     */
    @SaCheckPermission("token:user:list")
    @GetMapping("/users/options")
    public TableDataInfo<AiTokenUserOptionVo> userOptions(AiTokenAccountQueryBo query, PageQuery pageQuery) {
        return accountService.queryUserOptions(query, pageQuery);
    }

    /**
     * 根据用户ID查询Token额度账户详情
     */
    @SaCheckPermission("token:user:list")
    @GetMapping("/users/{userId}")
    public R<AiUserTokenAccountVo> userDetail(
        @NotNull(message = "员工ID不能为空") @PathVariable Long userId) {
        return R.ok(accountService.queryByUserId(userId));
    }

    /**
     * 初始授予Token额度
     */
    @SaCheckPermission("token:user:grant")
    @Log(title = "Token额度首次分配", businessType = BusinessType.GRANT)
    @RepeatSubmit
    @PostMapping("/users/{userId}/initial-grant")
    public R<Void> initialGrant(@PathVariable Long userId,
                                @Valid @RequestBody AiTokenAccountCommandBo command) {
        return toAjax(accountService.initialGrant(userId, command));
    }

    /**
     * 追加授予Token额度
     */
    @SaCheckPermission("token:user:grant")
    @Log(title = "Token额度追加", businessType = BusinessType.GRANT)
    @RepeatSubmit
    @PostMapping("/users/{userId}/append")
    public R<Void> appendGrant(@PathVariable Long userId,
                               @Valid @RequestBody AiTokenAccountCommandBo command) {
        return toAjax(accountService.appendGrant(userId, command));
    }

    /**
     * 设置Token剩余额度
     */
    @SaCheckPermission("token:user:adjust")
    @Log(title = "Token剩余额度调整", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/users/{userId}/remaining")
    public R<Void> setRemaining(@PathVariable Long userId,
                                @Valid @RequestBody AiTokenAccountCommandBo command) {
        return toAjax(accountService.setRemaining(userId, command));
    }

    /**
     * 设置Token预警阈值
     */
    @SaCheckPermission("token:user:adjust")
    @Log(title = "Token额度告警阈值", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/users/{userId}/warning-threshold")
    public R<Void> setWarningThreshold(@PathVariable Long userId,
                                       @Valid @RequestBody AiTokenAccountCommandBo command) {
        return toAjax(accountService.setWarningThreshold(userId, command));
    }

    /**
     * 设置Token额度无限
     */
    @SaCheckPermission("token:user:unlimited")
    @Log(title = "Token不限额设置", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/users/{userId}/unlimited")
    public R<Void> setUnlimited(@PathVariable Long userId,
                                @Valid @RequestBody AiTokenAccountCommandBo command) {
        return toAjax(accountService.setUnlimited(userId, command));
    }

    /**
     * 修改Token账户状态
     */
    @SaCheckPermission("token:user:freeze")
    @Log(title = "Token额度账户状态", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/users/{userId}/status")
    public R<Void> changeStatus(@PathVariable Long userId,
                                @Valid @RequestBody AiTokenAccountCommandBo command) {
        return toAjax(accountService.changeStatus(userId, command));
    }

    /**
     * 分页查询角色模型权限列表
     */
    @SaCheckPermission("token:role-model:list")
    @GetMapping("/roles")
    public TableDataInfo<AiRoleModelPermissionVo> roles(SysRoleBo query, PageQuery pageQuery) {
        return roleModelService.queryRolePage(query, pageQuery);
    }

    /**
     * 查询模型选项列表
     */
    @SaCheckPermission(value = {
        "token:role-model:list", "token:usage:list", "token:usage:statistics"
    }, mode = SaMode.OR)
    @GetMapping("/models/options")
    public R<List<AiModelOptionVo>> modelOptions() {
        return R.ok(roleModelService.queryModelOptions());
    }

    /**
     * 根据角色ID查询角色模型权限
     */
    @SaCheckPermission("token:role-model:list")
    @GetMapping("/roles/{roleId}/models")
    public R<AiRoleModelPermissionVo> roleModels(@PathVariable Long roleId) {
        return R.ok(roleModelService.queryRoleModels(roleId));
    }

    /**
     * 保存角色模型权限配置
     */
    @SaCheckPermission("token:role-model:assign")
    @Log(title = "角色模型权限", businessType = BusinessType.GRANT)
    @RepeatSubmit
    @PutMapping("/roles/{roleId}/models")
    public R<Void> saveRoleModels(@PathVariable Long roleId,
                                  @Valid @RequestBody AiRoleModelSaveBo command) {
        return toAjax(roleModelService.saveRoleModels(roleId, command));
    }

    /**
     * 查询用户生效的模型权限列表
     */
    @SaCheckPermission("token:role-model:list")
    @GetMapping("/users/{userId}/effective-models")
    public R<List<AiModelOptionVo>> effectiveModels(@PathVariable Long userId) {
        return R.ok(roleModelService.queryEffectiveModels(userId));
    }

    /**
     * 分页查询Token额度日志列表
     */
    @SaCheckPermission("token:quota-log:list")
    @GetMapping("/logs")
    public TableDataInfo<AiTokenQuotaLogVo> quotaLogs(AiTokenQuotaLogQueryBo query, PageQuery pageQuery) {
        return accountService.queryLogPage(query, pageQuery);
    }
}
