// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import org.hibernate.validator.constraints.CodePointLength;

/** Java 签发的对象级短期能力；禁止传递 OSS 全局密钥。  每个 ticket 绑定单个 OSS object_id 和单一操作权限，包含：   - object_id / operation — 操作对象与权限类型   - url / token — 实际访问 URL 和临时认证 Token   - expires_at — 过期时间，过期后票据失效   - content_hash / max_bytes — 可选的下载校验用哈希和大小上限 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ArtifactTicket(
    @JsonProperty(value = "objectId", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String objectId,
    @JsonProperty(value = "operation", required = true)
    @NotNull Operation operation,
    @JsonProperty(value = "url", required = true)
    @NotNull @Pattern(regexp = "^https?://[^\\s#]+$") @CodePointLength(max = 8192) String url,
    @JsonProperty(value = "token", required = true)
    @NotNull @CodePointLength(min = 16, max = 4096) String token,
    @JsonProperty(value = "expiresAt", required = true)
    @NotNull OffsetDateTime expiresAt,
    @JsonProperty(value = "contentHash", required = true)
    @Pattern(regexp = "^[a-f0-9]{64}$") String contentHash,
    @JsonProperty(value = "maxBytes", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer maxBytes
) {

    /** 保留契约定义的线枚举值。 */
    public enum Operation {
        V_READ_SOURCE("READ_SOURCE"),
        V_WRITE_MANIFEST("WRITE_MANIFEST"),
        V_READ_MANIFEST("READ_MANIFEST");
        private final String value;
        Operation(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Operation fromValue(String value) {
            for (Operation candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
