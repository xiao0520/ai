package org.ruoyi.controller.agent;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.excel.utils.ExcelUtil;
import org.ruoyi.common.idempotent.annotation.RepeatSubmit;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.domain.bo.agent.AgentBo;
import org.ruoyi.domain.bo.agent.AgentAclSaveBo;
import org.ruoyi.domain.vo.agent.AgentAclVo;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.domain.vo.agent.ChatMyAgentOptionVo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.IChatUserOptionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/agent")
public class AgentController extends BaseController {

    private final IAgentService agentService;
    private final IChatUserOptionService chatUserOptionService;

    /**
     * 分页查询智能体列表
     */
    @SaCheckPermission("agent:agent:list")
    @GetMapping("/list")
    public TableDataInfo<AgentVo> list(AgentBo bo, PageQuery pageQuery) {
        return agentService.queryPageList(bo, pageQuery);
    }

    /**
     * 查询智能体列表（不分页，用于导出）
     */
    @SaCheckPermission("agent:agent:list")
    @GetMapping("/all")
    public R<List<AgentVo>> queryList(AgentBo bo) {
        return R.ok(agentService.queryList(bo));
    }

    /**
     * 导出智能体列表
     */
    @SaCheckPermission("agent:agent:export")
    @Log(title = "智能体管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(AgentBo bo, HttpServletResponse response) {
        List<AgentVo> list = agentService.queryList(bo);
        ExcelUtil.exportExcel(list, "智能体", AgentVo.class, response);
    }

    /**
     * 根据ID获取智能体详情
     */
    @SaCheckPermission("agent:agent:query")
    @GetMapping("/{id}")
    public R<AgentVo> getInfo(@PathVariable Long id) {
        return R.ok(agentService.queryById(id));
    }

    /**
     * 新增智能体
     */
    @SaCheckPermission("agent:agent:add")
    @Log(title = "智能体管理", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Validated @RequestBody AgentBo bo) {
        return toAjax(agentService.insertByBo(bo));
    }

    /**
     * 修改智能体
     */
    @SaCheckPermission("agent:agent:edit")
    @Log(title = "智能体管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@Validated @RequestBody AgentBo bo) {
        return toAjax(agentService.updateByBo(bo));
    }

    /**
     * 删除智能体
     */
    @SaCheckPermission("agent:agent:remove")
    @Log(title = "智能体管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        return toAjax(agentService.deleteByIds(List.of(ids)));
    }

    /** 用户端聊天页智能体下拉选项。 */
    @SaCheckLogin
    @GetMapping("/my-options")
    public R<List<ChatMyAgentOptionVo>> agentOptions() {
        return R.ok(chatUserOptionService.queryMyAgents());
    }

    /**
     * 查询当前用户在指定 Agent 下可选择的模型。
     */
    @SaCheckLogin
    @GetMapping("/{id}/available-models")
    public R<List<AiModelOptionVo>> availableModels(@PathVariable Long id) {
        return R.ok(agentService.queryAvailableModels(id));
    }

    /** 查询 Agent 的角色、部门使用权限。 */
    @SaCheckPermission("agent:agent:query")
    @GetMapping("/{id}/acl")
    public R<List<AgentAclVo>> acl(@PathVariable Long id) {
        return R.ok(agentService.listAcl(id));
    }

    /** 保存 Agent 的角色、部门使用权限。 */
    @SaCheckPermission("agent:agent:acl")
    @Log(title = "Agent权限配置", businessType = BusinessType.GRANT)
    @PutMapping("/{id}/acl")
    public R<Void> saveAcl(@PathVariable Long id, @Validated @RequestBody AgentAclSaveBo command) {
        return toAjax(agentService.saveAcl(id, command));
    }

}
