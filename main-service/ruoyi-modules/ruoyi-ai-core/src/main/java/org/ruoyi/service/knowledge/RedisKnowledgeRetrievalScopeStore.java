package org.ruoyi.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 基于 Redis 的 RAG 授权范围存储。
 *
 * <p>授权状态、分页游标和候选验证票据在多实例间共享；缓存不可用时直接抛错，
 * 调用方必须拒绝请求，禁止降级为无状态放行。</p>
 */
@Component
@RequiredArgsConstructor
public class RedisKnowledgeRetrievalScopeStore implements KnowledgeRetrievalScopeStore {
    private static final String SCOPE_PREFIX = "ai:rag:scope:";
    private static final String CURSOR_PREFIX = "ai:rag:cursor:";
    private static final String VALIDATION_PREFIX = "ai:rag:validation:";
    private final ObjectMapper objectMapper;

    @Override
    public void saveScope(StoredScope state, Duration ttl) {
        write(SCOPE_PREFIX + state.scope().scopeSnapshotId(), state, ttl);
    }

    @Override
    public StoredScope getScope(String scopeSnapshotId) {
        return read(SCOPE_PREFIX + scopeSnapshotId, StoredScope.class);
    }

    @Override
    public String saveCursor(CursorState state, Duration ttl) {
        String token = UUID.randomUUID().toString().replace("-", "");
        write(CURSOR_PREFIX + token, state, ttl);
        return token;
    }

    @Override
    public CursorState getCursor(String cursor) {
        return read(CURSOR_PREFIX + cursor, CursorState.class);
    }

    @Override
    public String saveValidation(ValidationState state, Duration ttl) {
        String token = UUID.randomUUID().toString().replace("-", "");
        write(VALIDATION_PREFIX + token, state, ttl);
        return token;
    }

    @Override
    public ValidationState getValidation(String ticket) {
        return read(VALIDATION_PREFIX + ticket, ValidationState.class);
    }

    private void write(String key, Object value, Duration ttl) {
        try {
            RedisUtils.setCacheObject(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception error) {
            throw new IllegalStateException("授权范围缓存写入失败", error);
        }
    }

    private <T> T read(String key, Class<T> type) {
        try {
            String value = RedisUtils.getCacheObject(key);
            return value == null ? null : objectMapper.readValue(value, type);
        } catch (Exception error) {
            throw new IllegalStateException("授权范围缓存读取失败", error);
        }
    }
}
