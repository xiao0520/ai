// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

/** 盘点 Qdrant generation；冻结保留集合仅供审计，删除由 Java 最新状态复核后另发 CLEANUP。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeReconcileTask(
    @JsonProperty(value = "jobType", required = true)
    @NotNull JobType jobType,
    @JsonProperty(value = "targetIndexVersion", required = true)
    @NotNull @CodePointLength(min = 1, max = 128) String targetIndexVersion,
    @JsonProperty(value = "retainedGenerationIds", required = true)
    @NotNull @Size(max = 100000) List<@NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String> retainedGenerationIds
) implements KnowledgeJobCommandTask {

    /** 保留契约定义的线枚举值。 */
    public enum JobType {
        V_RECONCILE("RECONCILE");
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
}
