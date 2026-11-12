package org.ruoyi.integration.security;

import org.ruoyi.common.redis.utils.RedisUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 使用 Redis 的 SET NX 语义，在 Java 多实例之间共享 nonce 防重放状态。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.worker-auth", name = "enabled", havingValue = "true")
public class RedisInternalRequestNonceStore implements InternalRequestNonceStore {
    @Override
    public boolean claim(String key, Duration ttl) {
        return RedisUtils.setObjectIfAbsent(key, "1", ttl);
    }
}
