package org.ruoyi.controller.knowledge;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.domain.bo.knowledge.KnowledgeIndexVersionBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeIndexVersionVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeParseJobVo;
import org.ruoyi.service.knowledge.IKnowledgeIndexVersionService;
import org.ruoyi.service.knowledge.IKnowledgeParseJobService;
import org.ruoyi.service.knowledge.IKnowledgeAclService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库索引版本管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge/index-versions")
public class KnowledgeIndexVersionController {
    private final IKnowledgeIndexVersionService service;
    private final IKnowledgeParseJobService jobService;
    private final IKnowledgeAclService knowledgeAclService;

    /**
     * 查询知识库索引版本列表
     */
    @SaCheckPermission("knowledge:index:list")
    @GetMapping
    public R<List<KnowledgeIndexVersionVo>> list(@RequestParam Long knowledgeId) {
        knowledgeAclService.require(knowledgeId, "READ");
        return R.ok(service.list(knowledgeId));
    }

    /**
     * 根据ID获取知识库索引版本详情
     */
    @SaCheckPermission("knowledge:index:query")
    @GetMapping("/{id}")
    public R<KnowledgeIndexVersionVo> get(@PathVariable Long id) {
        KnowledgeIndexVersionVo version = service.query(id);
        if (version == null) return R.fail("索引版本不存在");
        knowledgeAclService.require(version.getKnowledgeId(), "READ");
        return R.ok(version);
    }

    /**
     * 保存知识库索引版本
     */
    @SaCheckPermission("knowledge:index:edit")
    @PostMapping
    public R<Void> save(@Valid @RequestBody KnowledgeIndexVersionBo bo) {
        knowledgeAclService.require(bo.getKnowledgeId(), "MANAGE");
        return service.save(bo) ? R.ok() : R.fail("保存失败");
    }

    /**
     * 按目标索引快照重建当前已发布文档。
     */
    @SaCheckPermission("knowledge:index:edit")
    @PostMapping("/{id}/rebuild")
    public R<KnowledgeParseJobVo> rebuild(@PathVariable Long id) {
        KnowledgeIndexVersionVo version = service.query(id);
        if (version == null) return R.fail("索引版本不存在");
        knowledgeAclService.require(version.getKnowledgeId(), "MANAGE");
        if (version.getDocumentCount() == null || version.getDocumentCount() <= 0) {
            return R.fail("当前索引没有已发布文档，无需重建");
        }
        return R.ok(jobService.submitRebuild(version.getKnowledgeId(), version.getIndexVersion()));
    }

    /**
     * 删除空索引版本，并异步回收对应技术产物；当前生效版本会同时解除知识库指针。
     */
    @SaCheckPermission("knowledge:index:edit")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        KnowledgeIndexVersionVo version = service.query(id);
        if (version == null) return R.fail("索引版本不存在");
        knowledgeAclService.require(version.getKnowledgeId(), "MANAGE");
        return service.remove(id) ? R.ok() : R.fail("删除失败");
    }

    /**
     * 激活知识库索引版本
     */
    @SaCheckPermission("knowledge:index:edit")
    @PostMapping("/{id}/activate")
    public R<Void> activate(@PathVariable Long id) {
        KnowledgeIndexVersionVo version = service.query(id);
        if (version == null) return R.fail("索引版本不存在");
        knowledgeAclService.require(version.getKnowledgeId(), "MANAGE");
        return service.activate(id) ? R.ok() : R.fail("切换失败");
    }
}
