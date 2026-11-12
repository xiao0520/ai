package org.ruoyi.controller.knowledge;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.knowledge.KnowledgeParseJobBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeParseJobVo;
import org.ruoyi.service.knowledge.IKnowledgeParseJobService;
import org.ruoyi.service.knowledge.IKnowledgeAclService;
import org.ruoyi.common.core.exception.ServiceException;
import org.springframework.web.bind.annotation.*;

/**
 * 知识库解析任务管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge/jobs")
public class KnowledgeParseJobController {
    private final IKnowledgeParseJobService jobService;
    private final IKnowledgeAclService knowledgeAclService;

    /**
     * 分页查询知识库解析任务列表
     */
    @SaCheckPermission("knowledge:job:list")
    @GetMapping
    public TableDataInfo<KnowledgeParseJobVo> list(KnowledgeParseJobBo bo, PageQuery pageQuery) {
        if (bo.getKnowledgeId() == null) throw new ServiceException("knowledgeId 不能为空");
        knowledgeAclService.require(bo.getKnowledgeId(), "READ");
        return jobService.queryPageList(bo, pageQuery);
    }

    /**
     * 根据ID获取知识库解析任务详情
     */
    @SaCheckPermission("knowledge:job:query")
    @GetMapping("/{id}")
    public R<KnowledgeParseJobVo> get(@PathVariable Long id) {
        KnowledgeParseJobVo job = jobService.queryById(id);
        if (job == null) return R.fail("任务不存在");
        knowledgeAclService.require(job.getKnowledgeId(), "READ");
        return R.ok(job);
    }

    /**
     * 取消知识库解析任务
     */
    @SaCheckPermission("knowledge:job:edit")
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        KnowledgeParseJobVo job = jobService.queryById(id);
        if (job == null) return R.fail("任务不存在");
        knowledgeAclService.require(job.getKnowledgeId(), "WRITE");
        return jobService.cancel(id) ? R.ok() : R.fail("任务已结束，无法取消");
    }

    /**
     * 重试知识库解析任务
     */
    @SaCheckPermission("knowledge:job:edit")
    @PostMapping("/{id}/retry")
    public R<Void> retry(@PathVariable Long id) {
        KnowledgeParseJobVo job = jobService.queryById(id);
        if (job == null) return R.fail("任务不存在");
        knowledgeAclService.require(job.getKnowledgeId(), "WRITE");
        return jobService.retry(id) ? R.ok() : R.fail("任务状态已变化，请刷新后重试");
    }

    /**
     * 从管理列表删除知识库解析任务
     */
    @SaCheckPermission("knowledge:job:edit")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        KnowledgeParseJobVo job = jobService.queryById(id);
        if (job == null) {
            return R.fail("任务不存在");
        }
        knowledgeAclService.require(job.getKnowledgeId(), "WRITE");
        return jobService.hideFromList(id) ? R.ok() : R.fail("任务状态已变化或已从列表删除");
    }
}
