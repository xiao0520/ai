// 由 main-service/docs/contracts/rag-v1/generate.py 生成，请修改 Python 契约后重新生成。
package org.ruoyi.domain.dto.knowledge.rag;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** unit 的封闭判别体，未知类型必须拒绝。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "unit", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = RequestMeasurement.class, name = "DOCUMENT_PAIRS"),
    @JsonSubTypes.Type(value = RequestMeasurement.class, name = "REQUESTS"),
    @JsonSubTypes.Type(value = TokenMeasurement.class, name = "TOKENS"),
    @JsonSubTypes.Type(value = UnknownMeasurement.class, name = "UNKNOWN")
})
public sealed interface ExecutionUsageItemMeasurement permits RequestMeasurement, TokenMeasurement, UnknownMeasurement {
}
