// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/** 保留旧文档处理快照的 Rerank 字段，仅用于历史契约兼容。  新查询不读取该字段，运行时只使用 KnowledgeScope.retrievalSettings。 NONE — 旧快照关闭重排 PROFILE — 旧快照曾绑定重排模型 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DocumentRerankPolicy(
    @JsonProperty(value = "mode", required = true)
    @NotNull Mode mode,
    @JsonProperty(value = "textProfile", required = true)
    @Valid RerankProfile textProfile,
    @JsonProperty(value = "multimodalProfile", required = true)
    @Valid RerankProfile multimodalProfile
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (mode == null) { return true; }
        return mode == Mode.V_NONE
            ? textProfile == null && multimodalProfile == null
            : textProfile != null;
    }

    /** 保留契约定义的线枚举值。 */
    public enum Mode {
        V_NONE("NONE"),
        V_PROFILE("PROFILE");
        private final String value;
        Mode(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static Mode fromValue(String value) {
            for (Mode candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }
}
