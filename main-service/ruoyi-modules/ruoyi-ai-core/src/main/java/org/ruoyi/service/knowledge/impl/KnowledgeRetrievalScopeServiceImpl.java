package org.ruoyi.service.knowledge.impl;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.KnowledgeRetrievalConfig;
import org.ruoyi.domain.dto.knowledge.rag.CandidateValidationResponse;
import org.ruoyi.domain.dto.knowledge.rag.IndexSnapshot;
import org.ruoyi.domain.dto.knowledge.rag.InternalError;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeScope;
import org.ruoyi.domain.dto.knowledge.rag.KnowledgeRetrievalSettings;
import org.ruoyi.domain.dto.knowledge.rag.ProfileRef;
import org.ruoyi.domain.dto.knowledge.rag.RerankProfile;
import org.ruoyi.domain.dto.knowledge.rag.ManifestMember;
import org.ruoyi.domain.dto.knowledge.rag.ManifestPageRequest;
import org.ruoyi.domain.dto.knowledge.rag.ManifestPageResponse;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.knowledge.rag.RetrievalProfile;
import org.ruoyi.domain.dto.knowledge.rag.SourceRef;
import org.ruoyi.domain.dto.knowledge.rag.ValidateCandidatesRequest;
import org.ruoyi.domain.vo.knowledge.KnowledgeManifestMemberRow;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalScopeRow;
import org.ruoyi.mapper.knowledge.KnowledgeArtifactMapper;
import org.ruoyi.service.knowledge.IKnowledgeAclService;
import org.ruoyi.service.knowledge.IKnowledgeRetrievalScopeService;
import org.ruoyi.service.knowledge.KnowledgeArtifactAccessException;
import org.ruoyi.service.knowledge.KnowledgeRetrievalScopeStore;
import org.ruoyi.service.model.IModelProfileSnapshotService;
import org.ruoyi.service.model.IModelProfileSnapshotService.ModelProfileSnapshot;
import org.ruoyi.enums.ModelType;
import org.ruoyi.support.KnowledgeIndexSnapshotProperties;
import org.ruoyi.support.KnowledgeRetrievalScopeProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 创建并执行短期授权范围；Python 不接触角色、部门或业务数据库。 */
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalScopeServiceImpl implements IKnowledgeRetrievalScopeService {
    private static final int MAX_KNOWLEDGE_SCOPES = 100;
    private final KnowledgeArtifactMapper artifacts;
    private final IKnowledgeAclService aclService;
    private final KnowledgeRetrievalScopeStore store;
    private final KnowledgeRetrievalScopeProperties properties;
    private final RagContractJson codec;
    private final ObjectMapper objectMapper;
    private final IModelProfileSnapshotService modelSnapshots;
    private final KnowledgeIndexSnapshotProperties indexProperties;

    @Override
    public boolean isReady(Long knowledgeId, RetrievalProfile retrievalProfile) {
        if (knowledgeId == null || retrievalProfile == null) {
            return false;
        }
        try {
            KnowledgeRetrievalScopeRow row = requirePublishedScope(knowledgeId);
            readIndexSnapshot(row);
            readRetrievalSettings(row);
            return true;
        } catch (KnowledgeArtifactAccessException | ServiceException error) {
            return false;
        }
    }

    @Override
    public RagExecutionScope create(Long userId, String requestId, Collection<Long> knowledgeIds,
                                    RetrievalProfile retrievalProfile,
                                    int contextBudgetTokens) {
        requireCreateInput(userId, requestId, knowledgeIds, retrievalProfile, contextBudgetTokens);

        // Java 逐库执行当前 ACL 和发布状态校验，并冻结精确索引及成员修订。
        List<KnowledgeScope> knowledgeScopes = new ArrayList<>();
        List<IndexSnapshot> indexSnapshots = new ArrayList<>();
        for (Long knowledgeId : new LinkedHashSet<>(knowledgeIds)) {
            if (!aclService.canAccess(userId, knowledgeId, AiDomainConstants.PERMISSION_READ)) {
                throw access(HttpStatus.FORBIDDEN, InternalError.ErrorCode.V_AUTHORIZATION);
            }
            KnowledgeRetrievalScopeRow row = requirePublishedScope(knowledgeId);
            KnowledgeRetrievalSettings settings = readRetrievalSettings(row);
            knowledgeScopes.add(new KnowledgeScope(row.getKnowledgeId().toString(),
                row.getIndexVersionId().toString(), row.getPermissionRevision(),
                row.getManifestRevision(), settings));
            indexSnapshots.add(readIndexSnapshot(row));
        }

        // 范围只保存业务请求和用户 ID，角色、部门在候选复核时重新读取。
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC)
            .plusSeconds(properties.getScopeTtlSeconds());
        String scopeSnapshotId = IdUtil.getSnowflakeNextIdStr();
        RagExecutionScope scope = new RagExecutionScope(
            RagExecutionScope.ContractVersion.V_RAG_V1,
            RagExecutionScope.Mode.V_KNOWLEDGE_QA,
            scopeSnapshotId,
            expiresAt,
            List.copyOf(knowledgeScopes),
            retrievalProfile.profile(),
            retrievalProfile,
            List.copyOf(indexSnapshots),
            contextBudgetTokens
        );
        validateOutbound(scope);
        store.saveScope(new KnowledgeRetrievalScopeStore.StoredScope(userId, requestId, scope),
            Duration.ofSeconds(properties.getScopeTtlSeconds()));
        return scope;
    }

    @Override
    public ManifestPageResponse pageManifest(String scopeSnapshotId,
                                             ManifestPageRequest request) {
        KnowledgeRetrievalScopeStore.StoredScope state =
            requireState(scopeSnapshotId, request.requestId());
        KnowledgeScope scope = requireKnowledgeScope(state.scope(), request.knowledgeId(),
            request.indexVersion(), request.manifestRevision());
        int offset = resolveOffset(state, scope, request);

        // SQL 同时过滤当前文档、版本和 generation 状态，停用内容不会进入新分页。
        Long indexVersionId = parseId(scope.indexVersion());
        List<KnowledgeManifestMemberRow> rows = artifacts.selectManifestMembers(
            indexVersionId,
            SystemConstants.NORMAL,
            SystemConstants.NORMAL,
            AiDomainConstants.STATUS_ACTIVE,
            AiDomainConstants.KNOWLEDGE_DOCUMENT_STATUS_READY,
            AiDomainConstants.KNOWLEDGE_DOCUMENT_STATUS_READY,
            AiDomainConstants.STATUS_READY,
            offset,
            request.pageSize() + 1
        );
        boolean hasMore = rows.size() > request.pageSize();
        List<ManifestMember> members = rows.stream()
            .limit(request.pageSize())
            .map(this::toManifestMember)
            .toList();
        String nextCursor = hasMore
            ? saveCursor(state, scope, offset + request.pageSize())
            : null;
        ManifestPageResponse response = new ManifestPageResponse(
            state.scope().scopeSnapshotId(),
            scope.knowledgeId(),
            scope.indexVersion(),
            scope.manifestRevision(),
            members,
            nextCursor,
            state.scope().expiresAt()
        );
        validateOutbound(response);
        return response;
    }

    @Override
    public CandidateValidationResponse validateCandidates(
        String scopeSnapshotId, ValidateCandidatesRequest request
    ) {
        KnowledgeRetrievalScopeStore.StoredScope state =
            requireState(scopeSnapshotId, request.requestId());

        // 先按库和索引分组，避免逐候选查询；每组重新读取当前 ACL 和内容有效性。
        Map<ScopeKey, List<SourceRef>> groups = new LinkedHashMap<>();
        for (SourceRef candidate : new LinkedHashSet<>(request.candidates())) {
            groups.computeIfAbsent(
                new ScopeKey(candidate.knowledgeId(), candidate.indexVersion()),
                ignored -> new ArrayList<>()
            ).add(candidate);
        }
        List<SourceRef> allowed = new ArrayList<>();
        for (Map.Entry<ScopeKey, List<SourceRef>> entry : groups.entrySet()) {
            KnowledgeScope scope = requireKnowledgeScope(state.scope(),
                entry.getKey().knowledgeId(), entry.getKey().indexVersion(), null);
            Long knowledgeId = parseId(scope.knowledgeId());
            if (!aclService.canAccess(state.userId(), knowledgeId,
                AiDomainConstants.PERMISSION_READ)) {
                continue;
            }
            allowed.addAll(validateGroup(scope, entry.getValue()));
        }

        // 验证票据绑定完整允许集合、用途和剩余期限，后续阶段不能扩大范围。
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusSeconds(properties.getValidationTtlSeconds());
        if (expiresAt.isAfter(state.scope().expiresAt())) {
            expiresAt = state.scope().expiresAt();
        }
        KnowledgeRetrievalScopeStore.ValidationState validation =
            new KnowledgeRetrievalScopeStore.ValidationState(
                scopeSnapshotId,
                request.requestId(),
                state.userId(),
                request.purpose().value(),
                List.copyOf(allowed),
                expiresAt
            );
        String ticket = store.saveValidation(validation, Duration.between(now, expiresAt));
        CandidateValidationResponse response = new CandidateValidationResponse(
            scopeSnapshotId, List.copyOf(allowed), ticket, expiresAt);
        validateOutbound(response);
        return response;
    }

    /**
     * 校验授权范围创建参数及问题改写快照的一致性。
     */
    private void requireCreateInput(Long userId, String requestId,
                                    Collection<Long> knowledgeIds,
                                    RetrievalProfile retrievalProfile,
                                    int contextBudgetTokens) {
        if (userId == null || userId <= 0 || requestId == null
            || requestId.length() < 6 || requestId.length() > 128
            || knowledgeIds == null || knowledgeIds.isEmpty()
            || knowledgeIds.size() > MAX_KNOWLEDGE_SCOPES
            || knowledgeIds.stream().anyMatch(id -> id == null || id <= 0)
            || retrievalProfile == null || contextBudgetTokens < 1) {
            throw access(HttpStatus.UNPROCESSABLE_ENTITY, InternalError.ErrorCode.V_VALIDATION);
        }
    }

    /**
     * 读取当前可检索的已发布索引和权限、清单修订。
     */
    private KnowledgeRetrievalScopeRow requirePublishedScope(Long knowledgeId) {
        KnowledgeRetrievalScopeRow row = artifacts.selectRetrievalScope(
            knowledgeId,
            SystemConstants.NORMAL,
            SystemConstants.NORMAL,
            AiDomainConstants.STATUS_ACTIVE,
            AiDomainConstants.KNOWLEDGE_PARSE_JOB_STATUS_SUCCEEDED
        );
        if (row == null || row.getIndexVersionId() == null
            || row.getPermissionRevision() == null || row.getPermissionRevision() < 1
            || row.getManifestRevision() == null || row.getManifestRevision() < 1
            || row.getMemberCount() == null || row.getMemberCount() < 1
            || StringUtils.isBlank(row.getIndexSnapshot())) {
            throw access(HttpStatus.CONFLICT, InternalError.ErrorCode.V_KNOWLEDGE_NOT_READY);
        }
        return row;
    }

    /**
     * 读取索引快照并以数据库主键和最新清单修订重新封装。
     */
    private IndexSnapshot readIndexSnapshot(KnowledgeRetrievalScopeRow row) {
        try {
            IndexSnapshot stored = codec.read(
                row.getIndexSnapshot().getBytes(StandardCharsets.UTF_8),
                IndexSnapshot.class
            );
            if (!row.getKnowledgeId().toString().equals(stored.knowledgeId())
                || !row.getIndexVersionId().toString().equals(stored.indexVersion())) {
                throw access(HttpStatus.CONFLICT, InternalError.ErrorCode.V_INDEX_MISMATCH);
            }
            return new IndexSnapshot(stored.knowledgeId(), stored.indexVersion(),
                stored.collectionName(), stored.distance(), stored.denseVectorName(),
                stored.denseVisionVectorName(), stored.sparseVectorName(),
                stored.embedding(), stored.multimodalEmbedding(), stored.parserRevision(),
                stored.splitterRevision(), stored.tokenizerRevision(), stored.sparseRevision(),
                stored.sparseCorpusRevision(), stored.chunkSizeTokens(),
                stored.chunkOverlapTokens(), stored.configHash(), row.getManifestRevision());
        } catch (KnowledgeArtifactAccessException error) {
            throw error;
        } catch (Exception error) {
            throw access(HttpStatus.CONFLICT, InternalError.ErrorCode.V_INDEX_MISMATCH);
        }
    }

    /** 将管理配置与当前发布模型修订一起冻结到请求范围。 */
    private KnowledgeRetrievalSettings readRetrievalSettings(KnowledgeRetrievalScopeRow row) {
        KnowledgeRetrievalConfig config;
        try {
            config = StringUtils.isBlank(row.getRetrievalConfig())
                ? KnowledgeRetrievalConfig.defaults()
                : objectMapper.readValue(row.getRetrievalConfig(), KnowledgeRetrievalConfig.class);
            config.validate();
        } catch (Exception error) {
            throw new ServiceException("知识库检索配置无效，请重新保存");
        }
        RerankProfile rerank = null;
        if (config.rerankEnabled()) {
            ModelProfileSnapshot model = modelSnapshots.requireCurrent(
                config.rerankModelId(), ModelType.RERANK.getKey());
            if (StringUtils.isAnyBlank(model.providerCode(), model.modelName(),
                    model.apiHost(), model.adapterCode(), model.credentialKey())
                || model.maxDocumentTokens() == null || model.maxDocumentTokens() < 1
                || model.inputModalities() == null || !model.inputModalities().contains("TEXT")) {
                throw new ServiceException("所选重排序模型的文本能力或发布参数不完整");
            }
            rerank = new RerankProfile(
                new ProfileRef(model.id().toString(), model.revision()), model.providerCode(),
                model.modelName(), model.apiHost(), model.adapterCode(), model.credentialKey(),
                RerankProfile.InputMode.V_TEXT, model.maxDocumentTokens(),
                indexProperties.getRerankTimeoutSeconds());
        }
        return new KnowledgeRetrievalSettings(
            config.denseEnabled(), config.sparseEnabled(), config.recallCount(), config.rrfK(),
            config.rerankEnabled(), rerank, config.rerankInputCount(),
            config.rerankOutputCount(), config.relevanceThreshold());
    }

    /**
     * 校验范围快照存在、未过期且仍绑定原请求。
     */
    private KnowledgeRetrievalScopeStore.StoredScope requireState(
        String scopeSnapshotId, String requestId
    ) {
        if (scopeSnapshotId == null || !scopeSnapshotId.matches("^[1-9][0-9]{0,18}$")) {
            throw access(HttpStatus.UNPROCESSABLE_ENTITY, InternalError.ErrorCode.V_VALIDATION);
        }
        KnowledgeRetrievalScopeStore.StoredScope state = store.getScope(scopeSnapshotId);
        if (state == null || state.scope() == null
            || state.scope().expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw access(HttpStatus.GONE, InternalError.ErrorCode.V_SCOPE_EXPIRED);
        }
        if (!state.requestId().equals(requestId)
            || !state.scope().scopeSnapshotId().equals(scopeSnapshotId)) {
            throw access(HttpStatus.FORBIDDEN, InternalError.ErrorCode.V_AUTHORIZATION);
        }
        return state;
    }

    private KnowledgeScope requireKnowledgeScope(RagExecutionScope executionScope,
                                                 String knowledgeId,
                                                 String indexVersion,
                                                 Integer manifestRevision) {
        return executionScope.knowledgeScopes().stream()
            .filter(item -> item.knowledgeId().equals(knowledgeId)
                && item.indexVersion().equals(indexVersion)
                && (manifestRevision == null
                    || item.manifestRevision().equals(manifestRevision)))
            .findFirst()
            .orElseThrow(() ->
                access(HttpStatus.FORBIDDEN, InternalError.ErrorCode.V_AUTHORIZATION));
    }

    /**
     * 校验不透明游标与当前范围完全绑定后返回分页偏移。
     */
    private int resolveOffset(KnowledgeRetrievalScopeStore.StoredScope state,
                              KnowledgeScope scope,
                              ManifestPageRequest request) {
        if (request.cursor() == null) {
            return 0;
        }
        KnowledgeRetrievalScopeStore.CursorState cursor = store.getCursor(request.cursor());
        if (cursor == null || cursor.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw access(HttpStatus.GONE, InternalError.ErrorCode.V_SCOPE_EXPIRED);
        }
        if (!cursor.scopeSnapshotId().equals(state.scope().scopeSnapshotId())
            || !cursor.requestId().equals(request.requestId())
            || !cursor.knowledgeId().equals(scope.knowledgeId())
            || !cursor.indexVersion().equals(scope.indexVersion())
            || !cursor.manifestRevision().equals(scope.manifestRevision())
            || cursor.offset() < 0) {
            throw access(HttpStatus.FORBIDDEN, InternalError.ErrorCode.V_AUTHORIZATION);
        }
        return cursor.offset();
    }

    private String saveCursor(KnowledgeRetrievalScopeStore.StoredScope state,
                              KnowledgeScope scope, int offset) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return store.saveCursor(
            new KnowledgeRetrievalScopeStore.CursorState(
                state.scope().scopeSnapshotId(), state.requestId(), scope.knowledgeId(),
                scope.indexVersion(), scope.manifestRevision(), offset,
                state.scope().expiresAt()
            ),
            Duration.between(now, state.scope().expiresAt())
        );
    }

    /**
     * 批量复核同一知识库和索引下的候选来源是否仍有效。
     */
    private List<SourceRef> validateGroup(KnowledgeScope scope,
                                          List<SourceRef> candidates) {
        List<Long> generationIds;
        try {
            generationIds = candidates.stream()
                .map(SourceRef::generationId)
                .map(Long::valueOf)
                .distinct()
                .toList();
        } catch (NumberFormatException error) {
            throw access(HttpStatus.UNPROCESSABLE_ENTITY, InternalError.ErrorCode.V_VALIDATION);
        }
        List<KnowledgeManifestMemberRow> rows = artifacts.selectValidMembers(
            parseId(scope.indexVersion()),
            generationIds,
            SystemConstants.NORMAL,
            SystemConstants.NORMAL,
            AiDomainConstants.STATUS_ACTIVE,
            AiDomainConstants.KNOWLEDGE_DOCUMENT_STATUS_READY,
            AiDomainConstants.KNOWLEDGE_DOCUMENT_STATUS_READY,
            AiDomainConstants.STATUS_READY
        );
        Map<String, KnowledgeManifestMemberRow> byGeneration = new LinkedHashMap<>();
        for (KnowledgeManifestMemberRow row : rows) {
            byGeneration.put(row.getGenerationId().toString(), row);
        }
        return candidates.stream().filter(candidate -> {
            KnowledgeManifestMemberRow row = byGeneration.get(candidate.generationId());
            return row != null
                && row.getDocumentId().toString().equals(candidate.documentId())
                && row.getDocumentVersionId().toString().equals(candidate.documentVersionId());
        }).toList();
    }

    private ManifestMember toManifestMember(KnowledgeManifestMemberRow row) {
        return new ManifestMember(row.getDocumentId().toString(),
            row.getDocumentVersionId().toString(), row.getGenerationId().toString());
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException error) {
            throw access(HttpStatus.UNPROCESSABLE_ENTITY, InternalError.ErrorCode.V_VALIDATION);
        }
    }

    /**
     * 通过共享 JSON 契约再次校验 Java 即将返回的对象。
     */
    private void validateOutbound(Object value) {
        try {
            codec.write(value);
        } catch (Exception error) {
            throw access(HttpStatus.SERVICE_UNAVAILABLE, InternalError.ErrorCode.V_INTERNAL);
        }
    }

    private KnowledgeArtifactAccessException access(HttpStatus status,
                                                    InternalError.ErrorCode code) {
        return new KnowledgeArtifactAccessException(status, code, false);
    }

    private record ScopeKey(String knowledgeId, String indexVersion) { }
}
