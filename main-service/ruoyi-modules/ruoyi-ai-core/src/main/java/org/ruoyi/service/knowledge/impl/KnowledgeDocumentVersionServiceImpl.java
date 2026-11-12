package org.ruoyi.service.knowledge.impl;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.dto.OssDTO;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentUploadBo;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentProcessingBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocument;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocumentVersion;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVersionVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVo;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentMapper;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentVersionMapper;
import org.ruoyi.service.knowledge.IKnowledgeDocumentVersionService;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.IKnowledgeParseJobService;
import org.ruoyi.service.knowledge.KnowledgeDocumentProcessingSnapshotFactory;
import org.ruoyi.support.AiRuntimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库文档版本管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentVersionServiceImpl implements IKnowledgeDocumentVersionService {
    private static final long DEFAULT_MAX_FILE_SIZE_MB = 50L;

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final IKnowledgeParseJobService parseJobService;
    private final KnowledgeDocumentProcessingSnapshotFactory processingSnapshots;
    private final OssService ossService;
    private final AiRuntimeConfig runtimeConfig;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVo uploadDocument(KnowledgeDocumentUploadBo bo) {
        // 校验文件、知识库归属及管理员可配置的上传限制。
        requireUpload(bo);
        // 与知识库删除串行，避免删除过程中新建出无法清理的文档。
        knowledgeInfoService.lockForIndexActivation(bo.getKnowledgeId());

        // 先持久化 OSS 文件，再以返回的文件元数据创建文档和首个版本。
        UploadMetadata metadata = uploadToOss(bo.getFile());
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeId(bo.getKnowledgeId());
        document.setDocumentCode("doc-" + UUID.randomUUID());
        document.setName(metadata.originalName());
        document.setSourceType(AiDomainConstants.KNOWLEDGE_DOCUMENT_SOURCE_UPLOAD);
        document.setStatus(AiDomainConstants.STATUS_PENDING);
        document.setCurrentVersionNo(0);
        documentMapper.insert(document);
        KnowledgeDocumentVersion version = createVersion(document, metadata, 1, bo);

        // 自动解析只提交控制面任务，实际解析由 Python 服务异步执行。
        if (Boolean.TRUE.equals(bo.getAutoParse())) parseJobService.submitDocumentVersion(version.getId());
        return documentMapper.selectVoById(document.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVersionVo uploadVersion(Long documentId, KnowledgeDocumentUploadBo bo) {
        requireUpload(bo);
        UploadMetadata metadata = uploadToOss(bo.getFile());

        // 锁定逻辑文档行，使同一文档的多个上传请求按事务顺序分配版本号。
        KnowledgeDocument document = documentMapper.selectByIdForUpdate(documentId);
        if (document == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(document.getStatus())) {
            throw new ServiceException("知识库文档不存在");
        }

        // 版本号基于已落库的最大值递增，表唯一约束同时作为绕过业务层写入时的最终兜底。
        Integer maxVersionNo = versionMapper.selectMaxVersionNoByDocumentId(documentId);
        int versionNo = (maxVersionNo == null ? 0 : maxVersionNo) + 1;
        KnowledgeDocumentVersion version = createVersion(document, metadata, versionNo, bo);
        // 新版本待解析时保留旧版本的在线状态，失败或重试不能让已有内容下线。
        if (document.getCurrentVersionId() == null) {
            documentMapper.updateStatus(documentId, AiDomainConstants.STATUS_PENDING);
        }

        // 新版本可按请求选择是否立即提交异步解析任务。
        if (Boolean.TRUE.equals(bo.getAutoParse())) parseJobService.submitDocumentVersion(version.getId());
        return versionMapper.selectVoById(version.getId());
    }

    @Override
    public List<KnowledgeDocumentVersionVo> listByDocumentId(Long documentId) {
        return versionMapper.selectVoListByDocumentId(documentId);
    }

    @Override
    public KnowledgeDocumentVersionVo queryById(Long id) {
        return versionMapper.selectVoById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVersionVo reparse(Long documentId, KnowledgeDocumentProcessingBo bo) {
        KnowledgeDocument document = documentMapper.selectByIdForUpdate(documentId);
        if (document == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(document.getStatus())) {
            throw new ServiceException("知识库文档不存在");
        }
        List<KnowledgeDocumentVersionVo> versions = versionMapper.selectVoListByDocumentId(documentId);
        if (versions.isEmpty()) {
            throw new ServiceException("文档没有可解析的文件版本");
        }
        KnowledgeDocumentVersion version = versionMapper.selectById(versions.get(0).getId());
        int nextRevision = version.getConfigurationRevision() == null ? 1 : version.getConfigurationRevision() + 1;
        processingSnapshots.apply(version, bo, nextRevision);
        version.setParseStatus(AiDomainConstants.STATUS_PENDING);
        version.setParseErrorCode(null);
        version.setParseErrorMessage(null);
        version.setParsedAt(null);
        versionMapper.updateById(version);
        parseJobService.submitDocumentVersion(version.getId());
        return versionMapper.selectVoById(version.getId());
    }

    /**
     * 创建文档版本
     *
     * @param document  文档
     * @param metadata  上传元数据
     * @param versionNo 版本号
     * @return 文档版本
     */
    private KnowledgeDocumentVersion createVersion(KnowledgeDocument document, UploadMetadata metadata, int versionNo,
                                                   KnowledgeDocumentProcessingBo processing) {
        // 版本持久化文件元数据，解析状态由异步任务后续推进。
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setDocumentId(document.getId());
        version.setKnowledgeId(document.getKnowledgeId());
        version.setVersionNo(versionNo);
        version.setOssId(metadata.ossId());
        version.setOriginalName(metadata.originalName());
        version.setFileSuffix(metadata.suffix());
        version.setMimeType(metadata.mimeType());
        version.setFileSize(metadata.fileSize());
        version.setFileHash(metadata.fileHash());
        version.setParseStatus(AiDomainConstants.STATUS_PENDING);
        processingSnapshots.apply(version, processing, 1);
        versionMapper.insert(version);
        return version;
    }

    /**
     * 校验上传参数
     *
     * @param bo 上传参数
     */
    private void requireUpload(KnowledgeDocumentUploadBo bo) {
        // 校验请求基础字段，避免无归属文件被写入 OSS。
        if (bo == null || bo.getKnowledgeId() == null) {
            throw new ServiceException("知识库ID不能为空");
        }
        MultipartFile file = bo.getFile();
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传文件不能为空");
        }
        long maxFileSizeMb = runtimeConfig.getPositiveLong(AiSystemConfigKeys.KNOWLEDGE_UPLOAD_MAX_SIZE_MB,
            DEFAULT_MAX_FILE_SIZE_MB, 1024L);

        // 文件大小和后缀均由系统参数控制，后缀仍受系统支持能力白名单约束。
        if (file.getSize() > maxFileSizeMb * 1024 * 1024) {
            throw new ServiceException("知识库文件不能超过" + maxFileSizeMb + "MB");
        }
        String suffix = suffix(file.getOriginalFilename());
        if (!AiDomainConstants.KNOWLEDGE_DOCUMENT_SUPPORTED_SUFFIXES.contains(suffix)) {
            throw new ServiceException("系统暂不支持解析该知识库文件类型：" + suffix);
        }
        Set<String> allowedSuffixes = runtimeConfig.getStringSet(
            AiSystemConfigKeys.KNOWLEDGE_UPLOAD_ALLOWED_SUFFIXES);
        if (!allowedSuffixes.isEmpty() && !allowedSuffixes.contains(suffix)) {
            throw new ServiceException("不支持的知识库文件类型：" + suffix);
        }
    }

    /**
     * 上传文件到OSS
     *
     * @param file 文件
     * @return 上传元数据
     */
    private UploadMetadata uploadToOss(MultipartFile file) {
        String hash;
        // 先计算内容摘要，供后续 Python 解析端识别文件版本与重复内容。
        try (InputStream input = file.getInputStream()) {
            hash = DigestUtil.sha256Hex(input);
        } catch (Exception e) {
            throw new ServiceException("计算文件摘要失败");
        }
        // OSS 业务目录由系统参数指定，禁止将前端文件名直接作为存储路径。
        // 上传层已注册事务回滚清理，本方法后续数据库写入失败时物理文件会被自动删除。
        String businessPath = runtimeConfig.getRequiredString(AiSystemConfigKeys.KNOWLEDGE_ATTACHMENT_OSS_CONFIG_KEY);
        OssDTO oss = ossService.uploadBusinessFile(file, businessPath);
        return new UploadMetadata(oss.getOssId(), oss.getOriginalName(),
            suffix(oss.getOriginalName()), file.getContentType(), file.getSize(), hash);
    }

    /**
     * 获取文件后缀名
     *
     * @param filename 文件名
     * @return 后缀名
     */
    private String suffix(String filename) {
        if (StringUtils.isBlank(filename) || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 上传元数据
     *
     * @param ossId        OSS ID
     * @param originalName 原始文件名
     * @param suffix       后缀名
     * @param mimeType     MIME类型
     * @param fileSize     文件大小
     * @param fileHash     文件哈希
     */
    private record UploadMetadata(Long ossId, String originalName, String suffix,
                                  String mimeType, Long fileSize, String fileHash) {
    }
}
