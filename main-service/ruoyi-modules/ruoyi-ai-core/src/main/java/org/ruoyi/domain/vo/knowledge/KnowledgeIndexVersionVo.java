package org.ruoyi.domain.vo.knowledge;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.knowledge.KnowledgeIndexVersion;

import java.io.Serializable;
import java.util.Date;

/**
 * 知识库索引版本视图。
 */
@Data
@AutoMapper(target = KnowledgeIndexVersion.class)
public class KnowledgeIndexVersionVo implements Serializable {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 知识库ID
     */
    private Long knowledgeId;
    /**
     * 索引版本
     */
    private String indexVersion;
    /**
     * 嵌入配置ID
     */
    private Long embeddingProfileId;
    /**
     * 嵌入配置修订号
     */
    private Integer embeddingProfileRevision;
    /**
     * 多模态嵌入配置ID
     */
    private Long multimodalEmbeddingProfileId;
    /**
     * 多模态嵌入配置修订号
     */
    private Integer multimodalEmbeddingProfileRevision;
    /**
     * 重排序配置ID
     */
    private Long rerankProfileId;
    /**
     * 重排序配置修订号
     */
    private Integer rerankProfileRevision;
    /**
     * 分割器配置
     */
    private String splitterConfig;
    /**
     * 状态
     */
    private String status;
    /**
     * 文档数量
     */
    private Integer documentCount;
    /**
     * 片段数量
     */
    private Integer fragmentCount;
    /**
     * 激活时间
     */
    private Date activatedAt;
    /**
     * 创建时间
     */
    private Date createTime;
    /**
     * 更新时间
     */
    private Date updateTime;
}
