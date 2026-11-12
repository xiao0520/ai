package org.ruoyi.domain.dto.knowledge;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;

import java.io.IOException;

/** 区分可选字段的缺省与显式 null：缺省交给 record 默认值，显式 null 拒绝。 */
public final class RagDefaultValueDeserializer extends JsonDeserializer<Object> implements ContextualDeserializer {
    private final JavaType type;

    public RagDefaultValueDeserializer() {
        this(null);
    }

    private RagDefaultValueDeserializer(JavaType type) {
        this.type = type;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        return new RagDefaultValueDeserializer(property.getType());
    }

    @Override
    public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return context.readValue(parser, type);
    }

    @Override
    public Object getNullValue(DeserializationContext context) throws JsonMappingException {
        throw JsonMappingException.from(context.getParser(), "VALIDATION");
    }

    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}