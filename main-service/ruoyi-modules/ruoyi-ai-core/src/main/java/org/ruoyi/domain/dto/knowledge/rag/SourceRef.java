// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 候选复核使用完整不可变来源元组，禁止只按 chunkId 授权。  六元组唯一标识一个 chunk：(knowledge_id, index_version, document_id, document_version_id, generation_id, chunk_id)，加上 content_hash 确保指向的内容未被篡改。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SourceRef(
    @JsonProperty(value = "knowledgeId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String knowledgeId,
    @JsonProperty(value = "indexVersion", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String indexVersion,
    @JsonProperty(value = "documentId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String documentId,
    @JsonProperty(value = "documentVersionId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String documentVersionId,
    @JsonProperty(value = "generationId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String generationId,
    @JsonProperty(value = "chunkId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String chunkId,
    @JsonProperty(value = "contentHash", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String contentHash
) {
}
