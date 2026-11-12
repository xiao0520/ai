package org.ruoyi.support;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 请求级 RAG 授权范围、分页游标和候选票据的短期有效期。 */
@Data
@Validated
@ConfigurationProperties(prefix = "knowledge.retrieval-scope")
public class KnowledgeRetrievalScopeProperties {
    @Min(15)
    @Max(900)
    private int scopeTtlSeconds = 120;
    @Min(5)
    @Max(300)
    private int validationTtlSeconds = 60;
}
