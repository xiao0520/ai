package org.ruoyi.domain.bo.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 文档上传或重新解析时由管理端选择的模型处理配置。 */
@Data
public class KnowledgeDocumentProcessingBo {
    /** 目标索引版本编码。 */
    @NotBlank(message = "目标索引版本不能为空")
    private String targetIndexVersion;
    /** TEXT 或 MULTIMODAL。 */
    @NotBlank(message = "处理模式不能为空")
    private String processingMode;
    /** 文本 Embedding 模型。 */
    @NotNull(message = "文本 Embedding 模型不能为空")
    private Long textEmbeddingProfileId;
    /** 多模态 Embedding 模型。 */
    private Long multimodalEmbeddingProfileId;
    /** 图片理解/OCR 模型。 */
    private Long visionProfileId;
    /** NONE 或 PROFILE。 */
    @NotBlank(message = "Rerank 模式不能为空")
    private String rerankMode;
    /** 文本 Rerank 模型。 */
    private Long textRerankProfileId;
    /** 多模态 Rerank 模型。 */
    private Long multimodalRerankProfileId;
}
