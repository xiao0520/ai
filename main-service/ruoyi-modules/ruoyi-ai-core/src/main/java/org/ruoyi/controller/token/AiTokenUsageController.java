package org.ruoyi.controller.token;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.token.AiTokenUsageQueryBo;
import org.ruoyi.domain.vo.token.AiTokenStatisticsVo;
import org.ruoyi.domain.vo.token.AiTokenUsageVo;
import org.ruoyi.service.token.IAiTokenUsageService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token用量查询管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/token/usage")
public class AiTokenUsageController {

    private final IAiTokenUsageService usageService;

    /**
     * 分页查询Token用量列表
     */
    @SaCheckPermission("token:usage:list")
    @GetMapping("/list")
    public TableDataInfo<AiTokenUsageVo> list(AiTokenUsageQueryBo query, PageQuery pageQuery) {
        return usageService.queryPage(query, pageQuery);
    }

    /**
     * 统计Token用量
     */
    @SaCheckPermission("token:usage:statistics")
    @GetMapping("/statistics")
    public R<AiTokenStatisticsVo> statistics(AiTokenUsageQueryBo query) {
        return R.ok(usageService.statistics(query));
    }
}
