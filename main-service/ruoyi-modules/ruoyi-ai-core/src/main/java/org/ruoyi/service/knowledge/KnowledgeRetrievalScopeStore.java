package org.ruoyi.service.knowledge;

import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.knowledge.rag.SourceRef;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 请求级授权范围及其派生游标、验证票据的短期存储边界。
 *
 * <p>实现必须支持多实例共享，并严格按照调用方传入的 TTL 过期数据。</p>
 */
public interface KnowledgeRetrievalScopeStore {

    /**
     * 保存一次 RAG 请求的授权范围。
     *
     * @param state 授权范围状态
     * @param ttl 有效期
     */
    void saveScope(StoredScope state, Duration ttl);

    /**
     * 查询授权范围。
     *
     * @param scopeSnapshotId 授权范围快照标识
     * @return 授权范围；不存在或已过期时返回 {@code null}
     */
    StoredScope getScope(String scopeSnapshotId);

    /**
     * 保存不可伪造的分页游标状态。
     *
     * @param state 游标状态
     * @param ttl 有效期
     * @return 对外使用的随机游标
     */
    String saveCursor(CursorState state, Duration ttl);

    /**
     * 查询分页游标状态。
     *
     * @param cursor 随机游标
     * @return 游标状态；不存在或已过期时返回 {@code null}
     */
    CursorState getCursor(String cursor);

    /**
     * 保存候选复核结果及其用途约束。
     *
     * @param state 复核状态
     * @param ttl 有效期
     * @return 候选验证票据
     */
    String saveValidation(ValidationState state, Duration ttl);

    /**
     * 查询候选验证票据绑定的状态。
     *
     * @param ticket 候选验证票据
     * @return 复核状态；不存在或已过期时返回 {@code null}
     */
    ValidationState getValidation(String ticket);

    /**
     * @param userId 发起请求的用户主键
     * @param requestId 请求追踪标识
     * @param scope 已固化的 RAG 执行范围
     */
    record StoredScope(Long userId, String requestId, RagExecutionScope scope) {
    }

    /**
     * @param scopeSnapshotId 授权范围快照标识
     * @param requestId 请求追踪标识
     * @param knowledgeId 知识库主键
     * @param indexVersion 索引版本
     * @param manifestRevision 清单修订号
     * @param offset 下一页偏移量
     * @param expiresAt 到期时间
     */
    record CursorState(String scopeSnapshotId, String requestId, String knowledgeId,
                       String indexVersion, Integer manifestRevision, int offset,
                       OffsetDateTime expiresAt) {
    }

    /**
     * @param scopeSnapshotId 授权范围快照标识
     * @param requestId 请求追踪标识
     * @param userId 发起请求的用户主键
     * @param purpose 验证结果允许使用的用途
     * @param allowedCandidates 通过复核的候选来源
     * @param expiresAt 到期时间
     */
    record ValidationState(String scopeSnapshotId, String requestId, Long userId,
                           String purpose, List<SourceRef> allowedCandidates,
                           OffsetDateTime expiresAt) {
    }
}