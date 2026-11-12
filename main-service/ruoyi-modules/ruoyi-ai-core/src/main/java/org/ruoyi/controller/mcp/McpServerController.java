package org.ruoyi.controller.mcp;

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
import org.ruoyi.domain.bo.mcp.McpServerBo;
import org.ruoyi.domain.bo.mcp.McpToolImportBo;
import org.ruoyi.domain.vo.mcp.McpServerVo;
import org.ruoyi.service.mcp.IMcpServerService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * MCP服务管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/mcp/server")
public class McpServerController extends BaseController {

    private final IMcpServerService serverService;

    /**
     * 分页查询MCP服务列表
     */
    @SaCheckPermission("mcp:server:list")
    @GetMapping("/list")
    public TableDataInfo<McpServerVo> list(McpServerBo bo, PageQuery pageQuery) {
        return serverService.queryPage(bo, pageQuery);
    }

    /**
     * 查询全部MCP服务列表
     */
    @SaCheckPermission(value = {"mcp:server:list", "mcp:tool:list"}, mode = SaMode.OR)
    @GetMapping("/all")
    public R<List<McpServerVo>> all() {
        return R.ok(serverService.queryAll());
    }

    /**
     * 根据ID获取MCP服务详情
     */
    @SaCheckPermission("mcp:server:query")
    @GetMapping("/{id}")
    public R<McpServerVo> get(@NotNull @PathVariable Long id) {
        return R.ok(serverService.queryById(id));
    }

    /**
     * 新增MCP服务
     */
    @SaCheckPermission("mcp:server:add")
    @Log(title = "MCP Server管理", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Valid @RequestBody McpServerBo bo) {
        return toAjax(serverService.insert(bo));
    }

    /**
     * 修改MCP服务
     */
    @SaCheckPermission("mcp:server:edit")
    @Log(title = "MCP Server管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@Valid @RequestBody McpServerBo bo) {
        return toAjax(serverService.update(bo));
    }

    /**
     * 删除MCP服务
     */
    @SaCheckPermission("mcp:server:remove")
    @Log(title = "MCP Server管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        return toAjax(serverService.delete(Arrays.asList(ids)));
    }

    /**
     * 修改MCP服务状态
     */
    @SaCheckPermission("mcp:server:edit")
    @Log(title = "MCP Server状态", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status")
    public R<Void> status(@PathVariable Long id, @RequestParam String status) {
        return toAjax(serverService.updateStatus(id, status));
    }

    /**
     * 导入MCP工具Manifest
     */
    @SaCheckPermission("mcp:server:import")
    @Log(title = "MCP工具Manifest导入", businessType = BusinessType.IMPORT)
    @RepeatSubmit
    @PostMapping("/{id}/tools/import")
    public R<Integer> importTools(@PathVariable Long id, @Valid @RequestBody McpToolImportBo bo) {
        return R.ok(serverService.importTools(id, bo.getManifestJson()));
    }

}
