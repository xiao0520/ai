package org.ruoyi.domain.vo.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库配置可选择的已发布模型。
 */
@Data
public class KnowledgeModelOptionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String modelName;
    private String modelDescribe;
    private String providerCode;
    private String category;
    private String apiMode;
    private String adapterCode;
    private String inputModalities;
    private Integer modelDimension;
    private Boolean normalized;
    private Integer maxInputTokens;
    private Integer maxDocumentTokens;
    private Integer publishedRevision;
}
