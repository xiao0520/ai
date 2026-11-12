package org.ruoyi.support;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Java 生成不可变索引执行快照时使用的技术版本和运行边界。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "knowledge.index-snapshot")
public class KnowledgeIndexSnapshotProperties {
    @NotBlank private String denseVectorName = "dense_text";
    @NotBlank private String denseVisionVectorName = "dense_vision";
    @NotBlank private String sparseVectorName = "sparse";
    @Pattern(regexp = "COSINE|DOT|EUCLID")
    private String distance = "COSINE";
    @NotBlank private String parserRevision = "python-parser-v1";
    @NotBlank private String splitterRevision = "recursive-token-v1";
    @NotBlank private String tokenizerRevision = "cl100k-base-v1";
    @NotBlank private String sparseRevision = "jieba-bm25-v1";
    @NotBlank private String sparseCorpusRevision = "corpus-v1";
    @Min(1) private int defaultChunkSizeTokens = 800;
    @Min(0) private int defaultChunkOverlapTokens = 100;
    @Min(1) @Max(300) private int embeddingTimeoutSeconds = 60;
    @Min(1) @Max(300) private int rerankTimeoutSeconds = 60;
    @Min(1) private int manifestRevision = 1;
}
