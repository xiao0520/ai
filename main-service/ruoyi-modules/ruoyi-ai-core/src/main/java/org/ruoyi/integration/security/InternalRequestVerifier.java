package org.ruoyi.integration.security;

import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.support.InternalWorkerAuthProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/** 验证 Python 到 Java 的内部请求，并用 Redis 在多实例间原子占用 nonce。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.worker-auth", name = "enabled", havingValue = "true")
public class InternalRequestVerifier {
    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final Pattern PATH = Pattern.compile("^/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+$");
    private static final Pattern SIGNATURE = Pattern.compile("^[a-f0-9]{64}$");
    private final InternalWorkerAuthProperties properties;
    private final InternalRequestNonceStore nonceStore;

    public InternalRequestVerifier(InternalWorkerAuthProperties properties, InternalRequestNonceStore nonceStore) {
        this.properties = properties;
        this.nonceStore = nonceStore;
    }

    /** 使用接收的原始正文验签；调用方不得先反序列化或规范化 JSON。 */
    public void verify(String method, String path, String query, byte[] body, String token,
                       String version, String timestamp, String nonce, String signature) {
        if (!InternalRequestSignature.VERSION.equals(version) || !"POST".equals(method)
            || !PATH.matcher(path).matches() || query == null || !query.isEmpty()
            || timestamp == null || !timestamp.matches("^[0-9]+$")
            || nonce == null || !NONCE.matcher(nonce).matches() || signature == null
            || !SIGNATURE.matcher(signature).matches() || token == null
            || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                properties.getServiceToken().getBytes(StandardCharsets.UTF_8))) {
            deny();
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException error) {
            deny();
            return;
        }
        long now = Instant.now().getEpochSecond();
        if (seconds < now - properties.getMaxAgeSeconds() || seconds > now + properties.getMaxAgeSeconds()) deny();
        String expected = sign(method, path, timestamp, nonce, body);
        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),
            expected.getBytes(StandardCharsets.US_ASCII))) deny();
        long ttl = Math.max(1, seconds + properties.getMaxAgeSeconds() - now);
        String nonceKey = "ai:internal:python-java:"
            + InternalRequestSignature.sha256Hex(nonce.getBytes(StandardCharsets.UTF_8));
        if (!nonceStore.claim(nonceKey, Duration.ofSeconds(ttl))) deny();
    }

    /** 复用 Java 与 Python唯一的规范字节实现创建 HMAC。 */
    public String sign(String method, String path, String timestamp, String nonce, byte[] body) {
        return InternalRequestSignature.sign(properties.getSigningKey(), method, path, timestamp, nonce, body);
    }

    private void deny() {
        throw new ServiceException("内部服务认证失败");
    }
}
