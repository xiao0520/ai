package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * 知识库索引版本元数据，不包含向量数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_index_version")
public class KnowledgeIndexVersion extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
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
}
