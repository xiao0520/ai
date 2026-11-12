package org.ruoyi.controller.knowledge;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo;
import org.ruoyi.domain.dto.knowledge.KnowledgeRetrievalConfig;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.service.knowledge.IKnowledgeAclService;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * 知识库管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge/libraries")
public class KnowledgeLibraryController {
    private final IKnowledgeInfoService knowledgeInfoService;
    private final IKnowledgeAclService knowledgeAclService;

    /**
     * 分页查询知识库列表
     */
    @SaCheckPermission("knowledge:library:list")
    @GetMapping
    public TableDataInfo<KnowledgeInfoVo> list(KnowledgeInfoBo bo, PageQuery pageQuery) {
        List<Long> ids = knowledgeAclService.accessibleKnowledgeIds();
        return knowledgeInfoService.queryAuthorizedPageList(bo, pageQuery, ids.isEmpty() ? List.of(-1L) : ids);
    }

    /**
     * 根据ID获取知识库详情
     */
    @SaCheckPermission("knowledge:library:query")
    @GetMapping("/{id}")
    public R<KnowledgeInfoVo> get(@PathVariable Long id) {
        knowledgeAclService.require(id, "READ");
        return R.ok(knowledgeInfoService.queryById(id));
    }

    /** 查询知识库检索策略。 */
    @SaCheckPermission("knowledge:library:query")
    @GetMapping("/{id}/retrieval-config")
    public R<KnowledgeRetrievalConfig> retrievalConfig(@PathVariable Long id) {
        knowledgeAclService.require(id, "READ");
        return R.ok(knowledgeInfoService.queryRetrievalConfig(id));
    }

    /** 保存知识库检索策略；生效于后续请求。 */
    @SaCheckPermission("knowledge:library:edit")
    @PutMapping("/{id}/retrieval-config")
    public R<Void> updateRetrievalConfig(@PathVariable Long id,
                                         @Validated @RequestBody KnowledgeRetrievalConfig config) {
        knowledgeAclService.require(id, "MANAGE");
        return knowledgeInfoService.updateRetrievalConfig(id, config) ? R.ok() : R.fail("检索策略保存失败");
    }

    /**
     * 新增知识库
     */
    @SaCheckPermission("knowledge:library:add")
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody KnowledgeInfoBo bo) {
        bo.setUserId(LoginHelper.getUserId());
        return knowledgeInfoService.insertByBo(bo) ? R.ok(bo.getId()) : R.fail("新增失败");
    }

    /**
     * 修改知识库
     */
    @SaCheckPermission("knowledge:library:edit")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Validated(EditGroup.class) @RequestBody KnowledgeInfoBo bo) {
        knowledgeAclService.require(id, "MANAGE");
        bo.setId(id);
        return knowledgeInfoService.updateByBo(bo) ? R.ok() : R.fail("更新失败");
    }

    /**
     * 更新知识库状态
     */
    @SaCheckPermission("knowledge:library:edit")
    @RequestMapping(value = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam String status) {
        knowledgeAclService.require(id, "MANAGE");
        return knowledgeInfoService.updateStatus(id, status) ? R.ok() : R.fail("状态更新失败");
    }

    /**
     * 删除知识库
     */
    @SaCheckPermission("knowledge:library:remove")
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable List<Long> ids) {
        ids.forEach(id -> knowledgeAclService.require(id, "MANAGE"));
        return knowledgeInfoService.deleteByIds(ids) ? R.ok() : R.fail("删除失败");
    }
}
