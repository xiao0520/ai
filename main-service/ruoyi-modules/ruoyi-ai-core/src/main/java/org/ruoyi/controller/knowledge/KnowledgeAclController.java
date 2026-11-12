package org.ruoyi.controller.knowledge;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.domain.bo.knowledge.KnowledgeAclBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeAclVo;
import org.ruoyi.service.knowledge.IKnowledgeAclService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库ACL权限管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge/acls")
public class KnowledgeAclController {
    private final IKnowledgeAclService service;

    /**
     * 查询知识库ACL权限列表
     */
    @SaCheckPermission("knowledge:acl:query")
    @GetMapping
    public R<List<KnowledgeAclVo>> list(@RequestParam Long knowledgeId) { return R.ok(service.list(knowledgeId)); }

    /**
     * 保存知识库ACL权限
     */
    @SaCheckPermission("knowledge:acl:edit")
    @PostMapping
    public R<Void> save(@Valid @RequestBody KnowledgeAclBo bo) {
        return service.save(bo) ? R.ok() : R.fail("保存失败");
    }

    /**
     * 删除知识库ACL权限
     */
    @SaCheckPermission("knowledge:acl:edit")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        return service.delete(id) ? R.ok() : R.fail("删除失败");
    }
}
