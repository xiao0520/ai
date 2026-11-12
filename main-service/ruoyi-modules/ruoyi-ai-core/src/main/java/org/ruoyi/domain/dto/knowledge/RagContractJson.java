package org.ruoyi.domain.dto.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validator;

import java.io.IOException;
import java.util.Objects;

/**
 * RAG DTO 专用 JSON 编解码，不修改项目全局 Jackson 配置。
 * 已用于 Kafka、内部资源、授权检索和 RAG 聊天边界，保持独立于项目全局 Jackson 配置。
 */
public final class RagContractJson {
    private final ObjectMapper mapper;
    private final Validator validator;

    public RagContractJson(Validator validator) {
        this.validator = Objects.requireNonNull(validator);
        this.mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
        // Jackson 对部分字符串转换有独立开关，禁止将数值业务 ID 偷换为字符串。
        mapper.coercionConfigFor(LogicalType.Textual)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    /** 先严格解析，再执行字段和关联约束；不裁定权限、租约或业务状态。 */
    public <T> T read(byte[] body, Class<T> type) throws IOException {
        T value = mapper.readValue(body, type);
        validate(value);
        return value;
    }

    /** 出站 DTO 也需校验，避免 Java 构造器产生非法快照。 */
    public byte[] write(Object value) throws JsonProcessingException {
        validate(value);
        return mapper.writeValueAsBytes(value);
    }

    private void validate(Object value) {
        if (value == null || !validator.validate(value).isEmpty()) {
            // 不拼接 violation 的非法值，票据、正文和内部地址可能在其中。
            throw new IllegalArgumentException("VALIDATION");
        }
    }
}