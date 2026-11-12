// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** Java 为单次调用绑定授权和执行配置；空范围禁止执行检索。  字段分为四层：   授权 — 快照 ID + 过期时间 + 知识库范围列表   检索 — 检索策略引用与完整快照   索引 — 每个知识库的完整索引配置快照   限额 — 模型可使用的知识上下文预算 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagExecutionScope(
    @JsonProperty(value = "contractVersion", required = true)
    @NotNull ContractVersion contractVersion,
    @JsonProperty(value = "mode", required = true)
    @NotNull Mode mode,
    @JsonProperty(value = "scopeSnapshotId", required = true)
    @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String scopeSnapshotId,
    @JsonProperty(value = "expiresAt", required = true)
    @NotNull OffsetDateTime expiresAt,
    @JsonProperty(value = "knowledgeScopes", required = true)
    @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid KnowledgeScope> knowledgeScopes,
    @JsonProperty(value = "retrievalProfile", required = true)
    @NotNull @Valid ProfileRef retrievalProfile,
    @JsonProperty(value = "retrievalSnapshot", required = true)
    @NotNull @Valid RetrievalProfile retrievalSnapshot,
    @JsonProperty(value = "indexSnapshots", required = true)
    @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid IndexSnapshot> indexSnapshots,
    @JsonProperty(value = "contextBudgetTokens", required = true)
    @NotNull @DecimalMin(value = "1", inclusive = true) @DecimalMax(value = "2147483647", inclusive = true) Integer contextBudgetTokens
) {

    /** 补充 Schema 无法表达的字段关联校验；缺失字段由 Bean Validation 处理。 */
    @JsonIgnore
    @AssertTrue(message = "契约字段关联不一致")
    public boolean isContractConsistent() {
        if (retrievalProfile == null || retrievalSnapshot == null || knowledgeScopes == null
            || indexSnapshots == null || knowledgeScopes.stream().anyMatch(java.util.Objects::isNull)
            || indexSnapshots.stream().anyMatch(java.util.Objects::isNull)) { return true; }
        if (!retrievalProfile.equals(retrievalSnapshot.profile())) { return false; }
        if (knowledgeScopes.stream().anyMatch(s -> s.retrievalSettings() == null)) { return false; }
        record Member(String knowledge, String index, Integer revision) { }
        var scopes = knowledgeScopes.stream().map(s -> new Member(s.knowledgeId(), s.indexVersion(),
            s.manifestRevision())).collect(java.util.stream.Collectors.toSet());
        var indexes = indexSnapshots.stream().map(s -> new Member(s.knowledgeId(), s.indexVersion(),
            s.manifestRevision())).collect(java.util.stream.Collectors.toSet());
        return scopes.size() == knowledgeScopes.size() && indexes.size() == indexSnapshots.size()
            && scopes.equals(indexes)
            && knowledgeScopes.stream().map(KnowledgeScope::knowledgeId).distinct().count() == scopes.size();
    }

    /** 保留契约定义的线枚举值。 */
    public enum ContractVersion {
        V_RAG_V1("rag-v1");
        private final String value;
        ContractVersion(String value) { this.value = value; }
        @JsonValue
        public String value() { return value; }
        @JsonCreator
        public static ContractVersion fromValue(String value) {
            for (ContractVersion candidate : values()) {
                if (candidate.value.equals(value)) { return candidate; }
            }
            throw new IllegalArgumentException("未知契约枚举");
        }
    }

    /** 保留契约定义的线枚举值。 */
    public enum Mode {
        V_KNOWLEDGE_QA("KNOWLEDGE_QA");
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
