package org.ruoyi.service.knowledge;

import org.ruoyi.domain.dto.knowledge.rag.CandidateValidationResponse;
import org.ruoyi.domain.dto.knowledge.rag.ManifestPageRequest;
import org.ruoyi.domain.dto.knowledge.rag.ManifestPageResponse;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.knowledge.rag.RetrievalProfile;
import org.ruoyi.domain.dto.knowledge.rag.ValidateCandidatesRequest;

import java.util.Collection;

/**
 * RAG 检索范围裁定服务。
 *
 * <p>Java 统一裁定并固化单次请求可检索的资源范围；Python 只能执行快照，不能根据用户、
 * 角色或部门自行扩大授权范围。</p>
 */
public interface IKnowledgeRetrievalScopeService {

    /**
     * 校验知识库当前是否具备创建检索范围所需的索引快照和成员清单。
     *
     * @param knowledgeId 知识库主键
     * @param retrievalProfile 本轮检索配置
     * @return true 表示当前可进入 RAG 检索链
     */
    boolean isReady(Long knowledgeId, RetrievalProfile retrievalProfile);

    /**
     * 为一次已鉴权的 Java 请求创建短期检索范围。
     *
     * @param userId 当前用户
     * @param requestId 请求追踪标识
     * @param knowledgeIds Java 业务层计算后的候选知识库
     * @param retrievalProfile 已发布检索参数快照
     * @param contextBudgetTokens 上下文硬上限
     * @return 不含角色、部门和任意用户查询条件的执行快照
     */
    RagExecutionScope create(Long userId, String requestId, Collection<Long> knowledgeIds,
                             RetrievalProfile retrievalProfile,
                             int contextBudgetTokens);

    /**
     * 读取绑定范围和清单修订的索引成员分页。
     *
     * @param scopeSnapshotId 检索范围快照标识
     * @param request         清单分页条件
     * @return 当前快照允许检索的索引成员分页
     */
    ManifestPageResponse pageManifest(String scopeSnapshotId, ManifestPageRequest request);

    /**
     * 使用当前权限及内容状态复核 Python 返回的完整来源元组。
     *
     * @param scopeSnapshotId 检索范围快照标识
     * @param request         Python 返回的候选来源
     * @return 通过权限和内容有效性复核的候选结果
     */
    CandidateValidationResponse validateCandidates(String scopeSnapshotId,
                                                    ValidateCandidatesRequest request);
}
