package org.ruoyi.controller.agent;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.domain.dto.OssDTO;
import org.ruoyi.common.excel.utils.ExcelUtil;
import org.ruoyi.common.idempotent.annotation.RepeatSubmit;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.domain.bo.skill.SkillDefinitionBo;
import org.ruoyi.domain.vo.skill.SkillBoundAgentVo;
import org.ruoyi.domain.vo.skill.SkillDefinitionVo;
import org.ruoyi.domain.vo.skill.SkillOptionVo;
import org.ruoyi.service.skill.ISkillDefinitionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 技能定义管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/agent/skill")
public class SkillDefinitionController extends BaseController {
    private final ISkillDefinitionService service;

    /**
     * 查询技能定义列表
     */
    @SaCheckPermission("agent:skill:list")
    @GetMapping("/list")
    public TableDataInfo<SkillDefinitionVo> list(SkillDefinitionBo bo, PageQuery pageQuery) {
        return service.queryPageList(bo, pageQuery);
    }

    /**
     * 查询技能定义列表（不分页，用于导出）
     */
    @SaCheckPermission("agent:skill:list")
    @GetMapping("/all")
    public R<List<SkillDefinitionVo>> queryList(SkillDefinitionBo bo) {
        return R.ok(service.queryList(bo));
    }

    /**
     * 导出技能定义列表
     */
    @SaCheckPermission("agent:skill:export")
    @Log(title = "Skill管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(SkillDefinitionBo bo, HttpServletResponse response) {
        ExcelUtil.exportExcel(service.queryList(bo), "Skill数据", SkillDefinitionVo.class, response);
    }

    /**
     * 根据ID获取技能定义详情
     */
    @SaCheckPermission("agent:skill:query")
    @GetMapping("/{id}")
    public R<SkillDefinitionVo> get(@PathVariable Long id) { return R.ok(service.query(id)); }

    /**
     * 保存技能定义
     */
    @SaCheckPermission("agent:skill:add")
    @Log(title = "Skill管理", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Validated @RequestBody SkillDefinitionBo bo) {
        return toAjax(service.save(bo));
    }

    /**
     * 修改技能定义
     */
    @SaCheckPermission("agent:skill:edit")
    @Log(title = "Skill管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@Validated @RequestBody SkillDefinitionBo bo) {
        return toAjax(service.save(bo));
    }

    /**
     * 删除技能定义
     */
    @SaCheckPermission("agent:skill:remove")
    @Log(title = "Skill管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> delete(@PathVariable Long[] ids) {
        boolean success = true;
        for (Long id : ids) success = service.delete(id) && success;
        return toAjax(success);
    }

    /**
     * 修改Skill状态
     */
    @SaCheckPermission("agent:skill:edit")
    @PutMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam String status) {
        return service.changeStatus(id, status) ? R.ok() : R.fail("状态更新失败");
    }

    /**
     * 查询绑定当前Skill的Agent列表
     */
    @SaCheckPermission("agent:skill:query")
    @GetMapping("/{id}/agents")
    public R<List<SkillBoundAgentVo>> agents(@PathVariable Long id) { return R.ok(service.listBoundAgents(id)); }

    /**
     * 启用的 Skill 下拉选项，保留给已有管理端页面调用。
     * */
    @SaCheckPermission("agent:skill:list")
    @GetMapping("/options")
    public R<List<SkillOptionVo>> options() { return R.ok(service.options()); }

    /** 上传 Skill 制品，存储目录由系统参数配置。 */
    @SaCheckPermission(value = {"agent:skill:add", "agent:skill:edit"}, mode = SaMode.OR)
    @Log(title = "Skill制品", businessType = BusinessType.INSERT)
    @PostMapping(value = "/upload-artifact", consumes = "multipart/form-data")
    public R<OssDTO> uploadArtifact(@RequestPart("file") MultipartFile file) {
        return R.ok(service.uploadArtifact(file));
    }
}
