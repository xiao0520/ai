package org.ruoyi.controller.knowledge;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentBo;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentUploadBo;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentProcessingBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVersionVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVo;
import org.ruoyi.service.knowledge.IKnowledgeAclService;
import org.ruoyi.service.knowledge.IKnowledgeDocumentService;
import org.ruoyi.service.knowledge.IKnowledgeDocumentVersionService;
import org.ruoyi.service.knowledge.IKnowledgeParseJobService;
import org.ruoyi.common.core.service.OssService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * 知识库文档与文档版本管理Controller
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge")
public class KnowledgeDocumentController {
    private final IKnowledgeDocumentService documentService;
    private final IKnowledgeDocumentVersionService versionService;
    private final IKnowledgeAclService knowledgeAclService;
    private final OssService ossService;

    /**
     * 分页查询知识库文档列表
     */
    @SaCheckPermission("knowledge:document:list")
    @GetMapping("/libraries/{knowledgeId}/documents")
    public TableDataInfo<KnowledgeDocumentVo> list(@PathVariable Long knowledgeId, KnowledgeDocumentBo bo, PageQuery pageQuery) {
        knowledgeAclService.require(knowledgeId, "READ");
        bo.setKnowledgeId(knowledgeId);
        return documentService.queryPageList(bo, pageQuery);
    }

    /**
     * 上传知识库文档
     */
    @SaCheckPermission("knowledge:document:add")
    @PostMapping("/libraries/{knowledgeId}/documents/upload")
    public R<KnowledgeDocumentVo> upload(@PathVariable Long knowledgeId,
                                         @Valid KnowledgeDocumentUploadBo bo) {
        knowledgeAclService.require(knowledgeId, "WRITE");
        bo.setKnowledgeId(knowledgeId);
        return R.ok(versionService.uploadDocument(bo));
    }

    /**
     * 查询文档版本列表
     */
    @SaCheckPermission("knowledge:document:query")
    @GetMapping("/documents/{documentId}/versions")
    public R<List<KnowledgeDocumentVersionVo>> versions(@PathVariable Long documentId) {
        KnowledgeDocumentVo document = requireDocument(documentId, "READ");
        return R.ok(versionService.listByDocumentId(document.getId()));
    }

    /**
     * 上传文档新版本
     */
    @SaCheckPermission("knowledge:document:add")
    @PostMapping("/documents/{documentId}/versions")
    public R<KnowledgeDocumentVersionVo> uploadVersion(@PathVariable Long documentId,
                                                        @Valid KnowledgeDocumentUploadBo bo) {
        KnowledgeDocumentVo document = requireDocument(documentId, "WRITE");
        bo.setKnowledgeId(document.getKnowledgeId());
        return R.ok(versionService.uploadVersion(documentId, bo));
    }

    /**
     * 重新解析文档
     */
    @SaCheckPermission("knowledge:document:edit")
    @PostMapping("/documents/{documentId}/reparse")
    public R<KnowledgeDocumentVersionVo> reparse(@PathVariable Long documentId,
                                                  @Valid @RequestBody KnowledgeDocumentProcessingBo bo) {
        requireDocument(documentId, "WRITE");
        return R.ok(versionService.reparse(documentId, bo));
    }

    /**
     * 更新文档状态
     */
    @SaCheckPermission("knowledge:document:edit")
    @RequestMapping(value = "/documents/{documentId}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public R<Void> updateStatus(@PathVariable Long documentId, @RequestParam @NotBlank String status) {
        requireDocument(documentId, "WRITE");
        return documentService.updateStatus(documentId, status) ? R.ok() : R.fail("状态更新失败");
    }

    /**
     * 删除知识库文档
     */
    @SaCheckPermission("knowledge:document:remove")
    @DeleteMapping("/documents/{documentId}")
    public R<Void> remove(@PathVariable Long documentId) {
        requireDocument(documentId, "WRITE");
        return documentService.deleteById(documentId) ? R.ok() : R.fail("删除失败");
    }

    /**
     * 下载文档版本文件
     */
    @SaCheckPermission("knowledge:document:query")
    @GetMapping("/document-versions/{versionId}/download")
    public void download(@PathVariable Long versionId, HttpServletResponse response) throws IOException {
        KnowledgeDocumentVersionVo version = versionService.queryById(versionId);
        if (version == null || "DELETED".equals(version.getParseStatus())) throw new ServiceException("文档版本不存在");
        knowledgeAclService.require(version.getKnowledgeId(), "READ");
        ossService.downloadFile(version.getOssId(), response);
    }

    /**
     * 校验文档权限并返回文档
     *
     * @param documentId 文档ID
     * @param permission 权限
     * @return 文档信息
     */
    private KnowledgeDocumentVo requireDocument(Long documentId, String permission) {
        KnowledgeDocumentVo document = documentService.queryById(documentId);
        if (document == null || "DELETED".equals(document.getStatus())) throw new ServiceException("知识库文档不存在");
        knowledgeAclService.require(document.getKnowledgeId(), permission);
        return document;
    }
}
