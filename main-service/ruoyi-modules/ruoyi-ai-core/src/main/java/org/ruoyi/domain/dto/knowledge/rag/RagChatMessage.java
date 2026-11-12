// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/** RAG 上下文消息拒绝未知字段与隐式类型转换。  同样通过多重继承叠加严格校验，确保 RAG 场景下消息结构完全受控。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagChatMessage(
    @JsonProperty(value = "role", required = true)
    @NotNull Role role,
    @JsonProperty(value = "content", required = true)
    @NotNull @CodePointLength(min = 1, max = 20000) String content
) {

    /** 保留契约定义的线枚举值。 */
    public enum Role {
        V_SYSTEM("system"),
        V_USER("user"),
        V_ASSISTANT("assistant");
        private final String value;
        Role(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Role fromValue(String value) {
            for (Role candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
