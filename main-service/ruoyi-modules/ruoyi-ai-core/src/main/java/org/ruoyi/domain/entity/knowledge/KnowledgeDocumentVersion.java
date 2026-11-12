package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * 知识库文档物理版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document_version")
public class KnowledgeDocumentVersion extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
    private Long id;
    /**
     * 文档ID
     */
    private Long documentId;
    /**
     * 知识库ID
     */
    private Long knowledgeId;
    /**
     * 版本号
     */
    private Integer versionNo;
    /**
     * 对象存储主键
     */
    private Long ossId;
    /**
     * 原始文件名
     */
    private String originalName;
    /**
     * 文件扩展名
     */
    private String fileSuffix;
    /**
     * MIME类型
     */
    private String mimeType;
    /**
     * 文件大小
     */
    private Long fileSize;
    /**
     * 文件哈希
     */
    private String fileHash;
    /** 目标索引版本编码。 */
    private String targetIndexVersion;
    /** 同一文件版本处理配置的递增修订。 */
    private Integer configurationRevision;
    /** TEXT 或 MULTIMODAL。 */
    private String processingMode;
    private Long textEmbeddingProfileId;
    private Integer textEmbeddingProfileRevision;
    private Long multimodalEmbeddingProfileId;
    private Integer multimodalEmbeddingProfileRevision;
    private Long visionProfileId;
    private Integer visionProfileRevision;
    /** NONE 或 PROFILE。 */
    private String rerankMode;
    private Long textRerankProfileId;
    private Integer textRerankProfileRevision;
    private Long multimodalRerankProfileId;
    private Integer multimodalRerankProfileRevision;
    /** 完整 DocumentProcessingSnapshot JSON。 */
    private String documentProcessingSnapshot;
    /**
     * 解析状态
     */
    private String parseStatus;
    /**
     * 解析错误编码
     */
    private String parseErrorCode;
    /**
     * 解析错误信息
     */
    private String parseErrorMessage;
    /**
     * 解析时间
     */
    private Date parsedAt;
}
