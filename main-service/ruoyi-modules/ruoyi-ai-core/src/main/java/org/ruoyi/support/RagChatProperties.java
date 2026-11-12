package org.ruoyi.support;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.ruoyi.domain.dto.knowledge.rag.ProfileRef;
import org.ruoyi.domain.dto.knowledge.rag.RetrievalProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** RAG 聊天的检索编排参数；模型快照仍由模型管理和索引版本动态提供。 */
@Data
@Validated
@ConfigurationProperties(prefix = "knowledge.rag-chat")
public class RagChatProperties {
    @Pattern(regexp = "^[1-9][0-9]{0,18}$")
    private String retrievalProfileId = "1";
    @Min(1)
    private int retrievalProfileRevision = 1;
    @Min(1) @Max(200)
    private int denseTopK = 10;
    @Min(1) @Max(200)
    private int sparseTopK = 10;
    @Min(1) @Max(1000)
    private int rrfK = 60;
    @Min(1) @Max(200)
    private int rerankTopK = 10;
    @Min(1) @Max(100)
    private int finalTopK = 8;
    @Min(0) @Max(3)
    private int neighborWindow = 1;
    @Min(1) @Max(100)
    private int maxContextChunks = 12;
    @Min(1)
    private int contextBudgetTokens = 6000;

    /** 构造一次请求使用的不可变检索参数，禁止在 Python 端补默认值。 */
    public RetrievalProfile toRetrievalProfile() {
        return new RetrievalProfile(
            new ProfileRef(retrievalProfileId, retrievalProfileRevision),
            RetrievalProfile.Mode.V_DENSE_BM25_RRF_RERANK,
            denseTopK, sparseTopK, rrfK, rerankTopK, finalTopK,
            neighborWindow, maxContextChunks
        );
    }
}
