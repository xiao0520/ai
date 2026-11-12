// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** Java 保存清单原始字节后返回实际 OSS 对象及可复核摘要。  Python 上传清单到 OSS 后，Java 返回此响应确认保存成功， 包含对象 ID、内容哈希和实际大小，供 Python 在 IngestionResult 中引用。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ArtifactUploadResponse(
    @JsonProperty(value = "objectId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String objectId,
    @JsonProperty(value = "contentHash", required = true)
    @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String contentHash,
    @JsonProperty(value = "sizeBytes", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer sizeBytes
) {
}
