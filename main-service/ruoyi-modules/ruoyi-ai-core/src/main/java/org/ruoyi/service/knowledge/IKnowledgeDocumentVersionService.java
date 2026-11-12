package org.ruoyi.service.knowledge;

import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentUploadBo;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentProcessingBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVersionVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVo;

import java.util.List;

/**
 * 知识库文档版本管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IKnowledgeDocumentVersionService {

    /**
     * 上传知识库文档
     *
     * @param bo 上传参数
     * @return 知识库文档
     */
    KnowledgeDocumentVo uploadDocument(KnowledgeDocumentUploadBo bo);

    /**
     * 上传文档新版本
     *
     * @param documentId 文档ID
     * @param bo         上传参数
     * @return 文档版本
     */
    KnowledgeDocumentVersionVo uploadVersion(Long documentId, KnowledgeDocumentUploadBo bo);

    /**
     * 为最新文件版本冻结处理配置并提交重新解析任务。
     *
     * @param documentId 文档主键
     * @param bo         本次重新解析使用的模型与切片配置
     * @return 已冻结配置的文档版本
     */
    KnowledgeDocumentVersionVo reparse(Long documentId, KnowledgeDocumentProcessingBo bo);

    /**
     * 根据文档ID查询版本列表
     *
     * @param documentId 文档ID
     * @return 版本列表
     */
    List<KnowledgeDocumentVersionVo> listByDocumentId(Long documentId);

    /**
     * 根据ID查询文档版本
     *
     * @param id 主键
     * @return 文档版本
     */
    KnowledgeDocumentVersionVo queryById(Long id);
}
