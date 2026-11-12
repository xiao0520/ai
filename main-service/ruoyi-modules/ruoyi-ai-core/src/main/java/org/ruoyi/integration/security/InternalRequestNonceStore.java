package org.ruoyi.integration.security;

import java.time.Duration;

/** 为内部请求原子占用 nonce；生产环境必须使用多实例共享存储。 */
public interface InternalRequestNonceStore {
    /**
     * 首次占用返回 true；已被占用时返回 false。
     *
     * @param key 已包含认证方向和版本的 nonce 键
     * @param ttl nonce 的剩余有效期
     */
    boolean claim(String key, Duration ttl);
}
