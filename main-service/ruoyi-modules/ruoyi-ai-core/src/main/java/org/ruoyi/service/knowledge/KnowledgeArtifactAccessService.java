package org.ruoyi.service.knowledge;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.ArtifactManifest;
import org.ruoyi.domain.dto.knowledge.rag.ArtifactManifestEntry;
import org.ruoyi.domain.dto.knowledge.rag.ArtifactTicket;
import org.ruoyi.domain.dto.knowledge.rag.ArtifactTicketRequest;
import org.ruoyi.domain.dto.knowledge.rag.ArtifactTicketResponse;
import org.ruoyi.domain.dto.knowledge.rag.ArtifactUploadResponse;
import org.ruoyi.domain.dto.knowledge.rag.IndexSnapshot;
import org.ruoyi.domain.dto.knowledge.rag.InternalError;
import org.ruoyi.domain.entity.knowledge.KnowledgeArtifactGeneration;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocumentVersion;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;
import org.ruoyi.integration.security.ArtifactAccessTokenCodec;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentVersionMapper;
import org.ruoyi.mapper.knowledge.KnowledgeParseJobMapper;
import org.ruoyi.support.InternalWorkerAuthProperties;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Date;
import java.util.Objects;
import java.util.Set;

/**
 * Python Worker 受控访问知识库源文件和产物清单的服务。
 *
 * <p>访问权同时绑定任务、请求、attempt、generation、Worker 和租约。短期 Bearer 票据通过后仍需
 * 复核数据库状态，使取消、换批次或换执行者立即失效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "knowledge.worker-auth", name = "enabled", havingValue = "true")
public class KnowledgeArtifactAccessService {
    private static final String READ_SOURCE = "READ_SOURCE";
    private static final String WRITE_MANIFEST = "WRITE_MANIFEST";
    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "QUEUED", "RUNNING", "RETRY_WAIT");
    private final KnowledgeParseJobMapper jobs;
    private final KnowledgeDocumentVersionMapper versions;
    private final ISysOssService ossService;
    private final ArtifactAccessTokenCodec tokenCodec;
    private final InternalWorkerAuthProperties properties;
    private final RagContractJson codec;
    private final KnowledgeArtifactPersistenceService persistence;

    /**
     * 为当前执行批次签发源文件读取或清单写入票据。
     *
     * @param jobId 解析任务主键
     * @param request Worker 提交的票据申请
     * @return 与具体对象、操作及租约绑定的短期票据
     */
    public ArtifactTicketResponse issue(Long jobId, ArtifactTicketRequest request) {
        KnowledgeParseJob job = requireBoundJob(jobId, request.requestId(), request.attemptId(),
            parseGeneration(request.generationId()), request.workerId());
        return switch (request.operation()) {
            case V_READ_SOURCE -> issueSource(jobId, job, request);
            case V_WRITE_MANIFEST -> issueManifest(jobId, job, request);
            default -> throw failure(HttpStatus.SERVICE_UNAVAILABLE,
                InternalError.ErrorCode.V_KNOWLEDGE_NOT_READY, true);
        };
    }

    /**
     * 签发只允许读取当前文档版本 OSS 对象的票据。
     */
    private ArtifactTicketResponse issueSource(Long jobId, KnowledgeParseJob job,
                                               ArtifactTicketRequest request) {
        KnowledgeDocumentVersion version = requireSource(job, request.objectId());
        long expires = expiry(job);
        ArtifactAccessTokenCodec.ArtifactGrant grant = new ArtifactAccessTokenCodec.ArtifactGrant(
            jobId, job.getRequestId(), job.getAttemptId(), job.getGenerationId(), job.getLeaseOwner(),
            READ_SOURCE, version.getOssId(), expires);
        ArtifactTicket ticket = new ArtifactTicket(version.getOssId().toString(),
            ArtifactTicket.Operation.V_READ_SOURCE,
            normalizedBaseUrl() + "/internal/knowledge/jobs/" + jobId + "/artifacts/" + version.getOssId(),
            issueToken(grant), OffsetDateTime.ofInstant(Instant.ofEpochSecond(expires), ZoneOffset.UTC),
            version.getFileHash(), Math.toIntExact(version.getFileSize()));
        return new ArtifactTicketResponse(ticket);
    }

    /**
     * 签发只允许写入当前 generation 清单的票据。
     */
    private ArtifactTicketResponse issueManifest(Long jobId, KnowledgeParseJob job,
                                                 ArtifactTicketRequest request) {
        if (!Objects.equals(request.objectId(), job.getGenerationId().toString())) {
            throw failure(HttpStatus.FORBIDDEN, InternalError.ErrorCode.V_AUTHORIZATION, false);
        }
        long expires = expiry(job);
        ArtifactAccessTokenCodec.ArtifactGrant grant = new ArtifactAccessTokenCodec.ArtifactGrant(
            jobId, job.getRequestId(), job.getAttemptId(), job.getGenerationId(), job.getLeaseOwner(),
            WRITE_MANIFEST, job.getGenerationId(), expires);
        ArtifactTicket ticket = new ArtifactTicket(job.getGenerationId().toString(),
            ArtifactTicket.Operation.V_WRITE_MANIFEST,
            normalizedBaseUrl() + "/internal/knowledge/jobs/" + jobId + "/manifests/" + job.getGenerationId(),
            issueToken(grant), OffsetDateTime.ofInstant(Instant.ofEpochSecond(expires), ZoneOffset.UTC),
            null, properties.getManifestMaxBytes());
        return new ArtifactTicketResponse(ticket);
    }

    /**
     * 下载当前任务绑定的源文件。
     *
     * <p>Bearer 校验通过后仍复核数据库状态，使取消、换批次和换执行者立即失效。</p>
     *
     * @param jobId 解析任务主键
     * @param objectId OSS 对象主键
     * @param bearerToken 受控访问票据
     * @param response HTTP 响应
     * @throws IOException 响应流写入失败时抛出
     */
    public void download(Long jobId, Long objectId, String bearerToken, HttpServletResponse response)
        throws IOException {
        ArtifactAccessTokenCodec.ArtifactGrant grant = verifyGrant(bearerToken);
        if (!jobId.equals(grant.jobId()) || !objectId.equals(grant.objectId())
            || !READ_SOURCE.equals(grant.operation())) {
            throw failure(HttpStatus.FORBIDDEN, InternalError.ErrorCode.V_AUTHORIZATION, false);
        }
        KnowledgeParseJob job = requireBoundJob(jobId, grant.requestId(), grant.attemptId(),
            grant.generationId(), grant.workerId());
        KnowledgeDocumentVersion version = requireSource(job, objectId.toString());
        try {
            ossService.download(version.getOssId(), response);
        } catch (IOException | RuntimeException error) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, InternalError.ErrorCode.V_INTERNAL, true);
        }
    }

    /**
     * 验收并上传当前 generation 的不可变产物清单。
     *
     * <p>原始清单的任务绑定、配置哈希、源文件哈希和向量维度全部通过后才写 OSS，随后按
     * generation 幂等登记，供 Kafka 结果回报再次验收。</p>
     *
     * @param jobId 解析任务主键
     * @param generationId 产物批次主键
     * @param bearerToken 受控访问票据
     * @param body 原始清单字节
     * @return 已登记的清单对象信息
     */
    public ArtifactUploadResponse uploadManifest(Long jobId, Long generationId,
                                                 String bearerToken, byte[] body) {
        ArtifactAccessTokenCodec.ArtifactGrant grant = verifyGrant(bearerToken);
        if (!jobId.equals(grant.jobId()) || !generationId.equals(grant.objectId())
            || !WRITE_MANIFEST.equals(grant.operation()) || body.length == 0
            || body.length > properties.getManifestMaxBytes()) {
            throw failure(HttpStatus.FORBIDDEN, InternalError.ErrorCode.V_AUTHORIZATION, false);
        }
        KnowledgeParseJob job = requireBoundJob(jobId, grant.requestId(), grant.attemptId(),
            grant.generationId(), grant.workerId());
        ArtifactManifest manifest = readAndValidateManifest(job, body);
        String manifestHash = DigestUtil.sha256Hex(body);
        Long indexVersionId = parseIndexVersion(manifest.indexVersion());
        KnowledgeArtifactGeneration existing = persistence.find(generationId);
        if (existing != null) {
            return responseForExisting(job, manifest, indexVersionId, manifestHash, body.length, existing);
        }
        KnowledgeArtifactGeneration candidate = uploadAndBuildGeneration(
            job, manifest, indexVersionId, manifestHash, body);
        KnowledgeArtifactGeneration stored;
        try {
            stored = persistence.storeUploaded(candidate);
        } catch (RuntimeException error) {
            deleteQuietly(candidate.getManifestOssId());
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, InternalError.ErrorCode.V_INTERNAL, true);
        }
        if (!Objects.equals(candidate.getManifestOssId(), stored.getManifestOssId())) {
            deleteQuietly(candidate.getManifestOssId());
        }
        return responseForExisting(job, manifest, indexVersionId, manifestHash, body.length, stored);
    }

    /**
     * 校验幂等重传的清单摘要必须与已登记产物完全一致。
     */
    private ArtifactUploadResponse responseForExisting(KnowledgeParseJob job,
                                                        ArtifactManifest manifest,
                                                        Long indexVersionId,
                                                        String manifestHash,
                                                        int manifestSize,
                                                        KnowledgeArtifactGeneration existing) {
        if (!sameUploadedArtifact(job, manifest, indexVersionId, manifestHash, manifestSize, existing)) {
            throw failure(HttpStatus.CONFLICT, InternalError.ErrorCode.V_INDEX_MISMATCH, false);
        }
        return new ArtifactUploadResponse(existing.getManifestOssId().toString(),
            existing.getManifestHash(), Math.toIntExact(existing.getManifestSize()));
    }

    /**
     * 先上传 OSS 清单，再构造待登记的 generation；登记失败由调用方补偿删除。
     */
    private KnowledgeArtifactGeneration uploadAndBuildGeneration(KnowledgeParseJob job,
                                                                  ArtifactManifest manifest,
                                                                  Long indexVersionId,
                                                                  String manifestHash,
                                                                  byte[] body) {
        Path path = null;
        SysOssVo uploaded = null;
        try {
            path = Files.createTempFile("mjn-ai-manifest-" + job.getGenerationId() + "-", ".json");
            Files.write(path, body);
            uploaded = ossService.upload(path.toFile());
            if (uploaded == null || uploaded.getOssId() == null) {
                throw new IOException("OSS upload failed");
            }
            KnowledgeArtifactGeneration generation = new KnowledgeArtifactGeneration();
            generation.setGenerationId(job.getGenerationId());
            generation.setJobId(job.getId());
            generation.setAttemptId(job.getAttemptId());
            generation.setKnowledgeId(job.getKnowledgeId());
            generation.setIndexVersionId(indexVersionId);
            generation.setDocumentId(job.getDocumentId());
            generation.setDocumentVersionId(job.getDocumentVersionId());
            generation.setManifestOssId(uploaded.getOssId());
            generation.setManifestHash(manifestHash);
            generation.setManifestSize((long) body.length);
            generation.setConfigHash(manifest.configHash());
            generation.setSourceHash(manifest.sourceHash());
            generation.setVectorDimensions(manifest.vectorDimensions());
            generation.setVisionVectorDimensions(manifest.visionVectorDimensions());
            generation.setChunkCount(manifest.entries().size());
            generation.setVisionCount(Math.toIntExact(manifest.entries().stream()
                .filter(entry -> entry.modality() == ArtifactManifestEntry.Modality.V_IMAGE).count()));
            generation.setStatus("UPLOADED");
            return generation;
        } catch (IOException | RuntimeException error) {
            if (uploaded != null && uploaded.getOssId() != null) {
                deleteQuietly(uploaded.getOssId());
            }
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, InternalError.ErrorCode.V_INTERNAL, true);
        } finally {
            if (path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupError) {
                    log.warn("临时清单文件删除失败 path={}", path, cleanupError);
                }
            }
        }
    }

    /**
     * 将 Worker 清单与 Java 冻结的任务、索引和源文件快照逐项比对。
     */
    private ArtifactManifest readAndValidateManifest(KnowledgeParseJob job, byte[] body) {
        try {
            ArtifactManifest manifest = codec.read(body, ArtifactManifest.class);
            IndexSnapshot snapshot = codec.read(job.getIndexSnapshot().getBytes(StandardCharsets.UTF_8),
                IndexSnapshot.class);
            KnowledgeDocumentVersion version = versions.selectById(job.getDocumentVersionId());
            boolean hasImages = manifest.entries().stream()
                .anyMatch(entry -> entry.modality() == ArtifactManifestEntry.Modality.V_IMAGE);
            Integer expectedVisionDimensions = hasImages && snapshot.multimodalEmbedding() != null
                ? snapshot.multimodalEmbedding().dimensions() : null;
            if (version == null || !Objects.equals(manifest.jobId(), job.getId().toString())
                || !Objects.equals(manifest.attemptId(), job.getAttemptId())
                || !Objects.equals(manifest.generationId(), job.getGenerationId().toString())
                || !Objects.equals(manifest.knowledgeId(), job.getKnowledgeId().toString())
                || !Objects.equals(manifest.indexVersion(), snapshot.indexVersion())
                || !Objects.equals(manifest.documentId(), job.getDocumentId().toString())
                || !Objects.equals(manifest.documentVersionId(), job.getDocumentVersionId().toString())
                || !Objects.equals(manifest.configHash(), snapshot.configHash())
                || !Objects.equals(manifest.sourceHash(), version.getFileHash())
                || !Objects.equals(manifest.vectorDimensions(), snapshot.embedding().dimensions())
                || !Objects.equals(manifest.visionVectorDimensions(), expectedVisionDimensions)) {
                throw new IllegalArgumentException("INDEX_MISMATCH");
            }
            return manifest;
        } catch (Exception error) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, InternalError.ErrorCode.V_INDEX_MISMATCH, false);
        }
    }

    /**
     * 比较影响发布和检索的全部不可变字段，禁止同一 generation 指向不同产物。
     */
    private boolean sameUploadedArtifact(KnowledgeParseJob job, ArtifactManifest manifest,
                                         Long indexVersionId, String hash, int size,
                                         KnowledgeArtifactGeneration stored) {
        return stored != null && Objects.equals(stored.getGenerationId(), job.getGenerationId())
            && Objects.equals(stored.getJobId(), job.getId())
            && Objects.equals(stored.getAttemptId(), job.getAttemptId())
            && Objects.equals(stored.getKnowledgeId(), job.getKnowledgeId())
            && Objects.equals(stored.getIndexVersionId(), indexVersionId)
            && Objects.equals(stored.getDocumentId(), job.getDocumentId())
            && Objects.equals(stored.getDocumentVersionId(), job.getDocumentVersionId())
            && stored.getManifestOssId() != null
            && Objects.equals(stored.getManifestHash(), hash)
            && Objects.equals(stored.getManifestSize(), (long) size)
            && Objects.equals(stored.getConfigHash(), manifest.configHash())
            && Objects.equals(stored.getSourceHash(), manifest.sourceHash())
            && Objects.equals(stored.getVectorDimensions(), manifest.vectorDimensions())
            && Objects.equals(stored.getVisionVectorDimensions(), manifest.visionVectorDimensions())
            && Objects.equals(stored.getChunkCount(), manifest.entries().size())
            && Objects.equals(stored.getVisionCount(), Math.toIntExact(manifest.entries().stream()
                .filter(entry -> entry.modality() == ArtifactManifestEntry.Modality.V_IMAGE).count()));
    }

    /**
     * 校验短期票据的签名与有效期，并统一映射为稳定授权错误。
     */
    private ArtifactAccessTokenCodec.ArtifactGrant verifyGrant(String bearerToken) {
        try {
            return tokenCodec.verify(bearerToken);
        } catch (ArtifactAccessTokenCodec.ArtifactAccessDeniedException error) {
            throw failure(HttpStatus.FORBIDDEN, InternalError.ErrorCode.V_AUTHORIZATION, false);
        }
    }

    /**
     * 签发短期票据；密钥或编码异常视为可重试的内部错误。
     */
    private String issueToken(ArtifactAccessTokenCodec.ArtifactGrant grant) {
        try {
            return tokenCodec.issue(grant);
        } catch (RuntimeException error) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, InternalError.ErrorCode.V_INTERNAL, true);
        }
    }

    /**
     * 票据到期时间取配置 TTL 与任务租约到期时间中的较早值。
     */
    private long expiry(KnowledgeParseJob job) {
        long expires = Math.min(Instant.now().plusSeconds(properties.getTicketTtlSeconds()).getEpochSecond(),
            job.getLeaseExpireTime().toInstant().getEpochSecond());
        if (expires <= Instant.now().getEpochSecond()) {
            throw failure(HttpStatus.CONFLICT, InternalError.ErrorCode.V_LEASE_EXPIRED, true);
        }
        return expires;
    }

    /**
     * 复核任务身份、执行批次、租约持有者和租约时效，防止旧 Worker 继续访问。
     */
    private KnowledgeParseJob requireBoundJob(Long jobId, String requestId, String attemptId,
                                               Long generationId, String workerId) {
        KnowledgeParseJob job = jobs.selectById(jobId);
        if (job == null || !Objects.equals(job.getRequestId(), requestId)
            || !Objects.equals(job.getAttemptId(), attemptId)
            || !Objects.equals(job.getGenerationId(), generationId)
            || !Objects.equals(job.getLeaseOwner(), workerId)) {
            throw failure(HttpStatus.CONFLICT, InternalError.ErrorCode.V_STALE_ATTEMPT, false);
        }
        if (!ACTIVE_STATUSES.contains(job.getStatus()) || job.getLeaseExpireTime() == null
            || !job.getLeaseExpireTime().after(new Date())) {
            throw failure(HttpStatus.CONFLICT, InternalError.ErrorCode.V_LEASE_EXPIRED, true);
        }
        return job;
    }

    /**
     * 确认请求对象正是任务冻结的文档版本，并限制可下载文件大小。
     */
    private KnowledgeDocumentVersion requireSource(KnowledgeParseJob job, String objectId) {
        if (job.getDocumentVersionId() == null || job.getDocumentId() == null) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, InternalError.ErrorCode.V_KNOWLEDGE_NOT_READY, false);
        }
        KnowledgeDocumentVersion version = versions.selectById(job.getDocumentVersionId());
        if (version == null || version.getOssId() == null || version.getFileHash() == null
            || version.getFileSize() == null || version.getFileSize() < 1
            || version.getFileSize() > Integer.MAX_VALUE
            || !Objects.equals(version.getDocumentId(), job.getDocumentId())
            || !Objects.equals(version.getKnowledgeId(), job.getKnowledgeId())
            || !version.getOssId().toString().equals(objectId)) {
            throw failure(HttpStatus.FORBIDDEN, InternalError.ErrorCode.V_AUTHORIZATION, false);
        }
        return version;
    }

    /**
     * 解析机器契约中的索引版本并映射稳定错误码。
     */
    private Long parseIndexVersion(String indexVersion) {
        try {
            return Long.valueOf(indexVersion);
        } catch (NumberFormatException error) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, InternalError.ErrorCode.V_INDEX_MISMATCH, false);
        }
    }
    /**
     * 解析机器契约中的 generation 标识并映射稳定错误码。
     */
    private Long parseGeneration(String generationId) {
        try {
            return Long.valueOf(generationId);
        } catch (NumberFormatException error) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, InternalError.ErrorCode.V_VALIDATION, false);
        }
    }
    /**
     * 补偿删除未能登记的 OSS 清单；失败只记录，由孤儿对账继续处理。
     */
    private void deleteQuietly(Long ossId) {
        try {
            ossService.deleteWithValidByIds(List.of(ossId), false);
        } catch (RuntimeException cleanupError) {
            log.warn("未登记的 OSS 清单补偿删除失败 ossId={}", ossId, cleanupError);
        }
    }

    /**
     * 标准化内部资源地址，避免拼接路径时出现双斜杠。
     */
    private String normalizedBaseUrl() {
        String value = properties.getResourceBaseUrl();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private KnowledgeArtifactAccessException failure(HttpStatus status,
                                                      InternalError.ErrorCode code,
                                                      boolean retryable) {
        return new KnowledgeArtifactAccessException(status, code, retryable);
    }
}
