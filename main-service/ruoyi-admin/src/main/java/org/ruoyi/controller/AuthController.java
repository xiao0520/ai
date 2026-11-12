package org.ruoyi.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.domain.model.LoginBody;
import org.ruoyi.common.core.utils.*;
import org.ruoyi.common.encrypt.annotation.ApiEncrypt;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.sse.dto.SseMessageDto;
import org.ruoyi.common.sse.utils.SseMessageUtils;
import org.ruoyi.system.domain.vo.*;
import org.ruoyi.system.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 认证
 *
 * @author xiaoqh
 */
@Slf4j
@SaIgnore
@RequiredArgsConstructor
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SysLoginService loginService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ISysClientService clientService;


    /**
     * 登录方法
     *
     * @param body 登录信息
     * @return 结果
     */
    @ApiEncrypt
    @PostMapping("/login")
    public R<LoginVo> login(@RequestBody String body) {
        LoginBody loginBody = JsonUtils.parseObject(body, LoginBody.class);
        ValidatorUtils.validate(loginBody);
        // 授权类型和客户端id
        String clientId = loginBody.getClientId();
        String grantType = loginBody.getGrantType();
        log.info("登录请求 - clientId: {}, grantType: {}", clientId, grantType);
        SysClientVo client = clientService.queryByClientId(clientId);
        log.info("查询客户端结果 - client: {}, grantType: {}", client, client != null ? client.getGrantType() : "null");
        // 查询不到 client 或 client 内不包含 grantType
        if (ObjectUtil.isNull(client)) {
            log.info("客户端id: {} 不存在!", clientId);
            return R.fail(MessageUtils.message("auth.grant.type.error"));
        }
        if (!StringUtils.contains(client.getGrantType(), grantType)) {
            log.info("客户端id: {} 认证类型：{} 不匹配! 数据库grantType: {}", clientId, grantType, client.getGrantType());
            return R.fail(MessageUtils.message("auth.grant.type.error"));
        } else if (!SystemConstants.NORMAL.equals(client.getStatus())) {
            return R.fail(MessageUtils.message("auth.grant.type.blocked"));
        }
        // 登录
        LoginVo loginVo = IAuthStrategy.login(body, client, grantType);

        Long userId = LoginHelper.getUserId();
        scheduledExecutorService.schedule(() -> {
            SseMessageDto dto = new SseMessageDto();
            dto.setMessage("欢迎登录MJN-AI后台管理系统");
            dto.setUserIds(List.of(userId));
            SseMessageUtils.publishMessage(dto);
        }, 5, TimeUnit.SECONDS);
        return R.ok(loginVo);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        loginService.logout();
        return R.ok("退出成功");
    }

}
