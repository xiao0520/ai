package org.ruoyi.controller.mcp;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.domain.bo.mcp.McpToolBo;
import org.ruoyi.domain.bo.mcp.McpToolPolicyBo;
import org.ruoyi.domain.vo.mcp.McpToolOptionVo;
import org.ruoyi.domain.vo.mcp.McpToolVo;
import org.ruoyi.service.mcp.IMcpToolService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Arrays;

/**
 * MCP工具管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/mcp/tool")
public class McpToolController extends BaseController {

    private final IMcpToolService toolService;

    /**
     * 分页查询MCP工具列表
     */
    @SaCheckPermission("mcp:tool:list")
    @GetMapping("/list")
    public TableDataInfo<McpToolVo> list(McpToolBo bo, PageQuery pageQuery) {
        return toolService.queryPage(bo, pageQuery);
    }

    /**
     * 查询启用的MCP工具下拉选项
     */
    @SaCheckPermission(value = {"mcp:tool:list", "agent:agent:list"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    @GetMapping("/options")
    public R<List<McpToolOptionVo>> options() {
        return R.ok(toolService.queryEnabledOptions());
    }

    /**
     * 根据ID获取MCP工具详情
     */
    @SaCheckPermission("mcp:tool:query")
    @GetMapping("/{id}")
    public R<McpToolVo> get(@PathVariable Long id) {
        return R.ok(toolService.queryById(id));
    }

    /**
     * 更新MCP工具策略配置
     */
    @SaCheckPermission("mcp:tool:edit")
    @Log(title = "MCP工具策略", businessType = BusinessType.UPDATE)
    @PutMapping("/policy")
    public R<Void> policy(@Valid @RequestBody McpToolPolicyBo bo) {
        return toAjax(toolService.updatePolicy(bo));
    }

    /**
     * 修改MCP工具状态
     */
    @SaCheckPermission("mcp:tool:edit")
    @Log(title = "MCP工具状态", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status")
    public R<Void> status(@PathVariable Long id, @RequestParam String adminEnabled) {
        return toAjax(toolService.updateStatus(id, adminEnabled));
    }

    @SaCheckPermission("mcp:tool:remove")
    @Log(title = "MCP已移除工具清理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        return toAjax(toolService.deleteRemoved(Arrays.asList(ids)));
    }
}
