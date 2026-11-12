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
import jakarta.validation.constraints.Size;
import java.util.List;

/** 清理或对账只回报 generation 摘要，不携带向量、正文或凭证。  字段分为三组：   操作目标 — 清理时指向单个 generation，对账时为 None   盘点结果 — Qdrant 中实际观察到的 generation 集合   删除明细 — 本次实际删除的 generation 及点数 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeMaintenanceResult(
    @JsonProperty(value = "jobType", required = true)
    @NotNull JobType jobType,
    @JsonProperty(value = "targetGenerationId", required = true)
    @Pattern(regexp = "^[1-9][0-9]{0,18}$") String targetGenerationId,
    @JsonProperty(value = "observedGenerationIds", required = true)
    @NotNull @Size(max = 100000) List<@NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String> observedGenerationIds,
    @JsonProperty(value = "observedPointCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer observedPointCount,
    @JsonProperty(value = "deletedGenerationIds", required = true)
    @NotNull @Size(max = 100000) List<@NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String> deletedGenerationIds,
    @JsonProperty(value = "deletedPointCount", required = true)
    @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer deletedPointCount
) {

    /** 保留契约定义的线枚举值。 */
    public enum JobType {
        V_CLEANUP("CLEANUP"),
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
