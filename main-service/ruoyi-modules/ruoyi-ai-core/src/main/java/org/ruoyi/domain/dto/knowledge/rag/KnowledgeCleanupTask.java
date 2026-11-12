// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 删除 Java 已确认无发布引用的单个 generation。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeCleanupTask(
    @JsonProperty(value = "jobType", required = true)
    @NotNull JobType jobType,
    @JsonProperty(value = "targetIndexVersion", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String targetIndexVersion,
    @JsonProperty(value = "targetGenerationId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String targetGenerationId,
    @JsonProperty(value = "reason", required = true)
    @NotNull Reason reason
) implements KnowledgeJobCommandTask {

    /** 保留契约定义的线枚举值。 */
    public enum JobType {
        V_CLEANUP("CLEANUP");
        private final String value;
        JobType(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static JobType fromValue(String value) {
            for (JobType candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }

    /** 保留契约定义的线枚举值。 */
    public enum Reason {
        V_FAILED("FAILED"),
        V_CANCELLED("CANCELLED"),
        V_EXPIRED("EXPIRED"),
        V_SUPERSEDED("SUPERSEDED"),
        V_ORPHANED("ORPHANED");
        private final String value;
        Reason(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Reason fromValue(String value) {
            for (Reason candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
