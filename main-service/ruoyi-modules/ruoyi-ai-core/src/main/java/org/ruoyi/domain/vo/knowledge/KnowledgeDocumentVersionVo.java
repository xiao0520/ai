package org.ruoyi.domain.vo.knowledge;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocumentVersion;

import java.io.Serializable;
import java.util.Date;

/**
 * 知识库文档版本视图。
 */
@Data
@AutoMapper(target = KnowledgeDocumentVersion.class)
public class KnowledgeDocumentVersionVo implements Serializable {
    /**
     * 主键ID
     */
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
    private String targetIndexVersion;
    private Integer configurationRevision;
    private String processingMode;
    private Long textEmbeddingProfileId;
    private Integer textEmbeddingProfileRevision;
    private Long multimodalEmbeddingProfileId;
    private Integer multimodalEmbeddingProfileRevision;
    private Long visionProfileId;
    private Integer visionProfileRevision;
    private String rerankMode;
    private Long textRerankProfileId;
    private Integer textRerankProfileRevision;
    private Long multimodalRerankProfileId;
    private Integer multimodalRerankProfileRevision;
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
    /**
     * 创建时间
     */
    private Date createTime;
    /**
     * 更新时间
     */
    private Date updateTime;
}
