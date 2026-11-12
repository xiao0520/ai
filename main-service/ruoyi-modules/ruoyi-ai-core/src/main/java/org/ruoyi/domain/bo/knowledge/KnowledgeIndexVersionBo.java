package org.ruoyi.domain.bo.knowledge;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.entity.knowledge.KnowledgeIndexVersion;

/**
 * 知识库索引版本配置对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = KnowledgeIndexVersion.class, reverseConvertGenerate = false)
public class KnowledgeIndexVersionBo extends BaseEntity {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 知识库ID
     */
    @NotNull(message = "知识库ID不能为空")
    private Long knowledgeId;
    /**
     * 索引版本
     */
    @NotBlank(message = "索引版本不能为空")
    @Pattern(regexp = AiDomainConstants.KNOWLEDGE_INDEX_VERSION_PATTERN,
        message = "索引版本只能包含字母、数字、下划线和连字符，长度不能超过64个字符")
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
}
