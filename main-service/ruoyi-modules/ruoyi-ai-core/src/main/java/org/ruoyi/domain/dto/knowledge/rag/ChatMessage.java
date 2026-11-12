// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import org.apache.ibatis.type.Alias;
import org.hibernate.validator.constraints.CodePointLength;

/** 单条已受 Java 会话权限约束的上下文消息。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
@Alias("RagContractChatMessage")
public record ChatMessage(
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
