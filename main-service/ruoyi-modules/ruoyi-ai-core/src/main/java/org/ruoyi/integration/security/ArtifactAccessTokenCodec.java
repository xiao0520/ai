package org.ruoyi.integration.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.support.InternalWorkerAuthProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** 为单个任务源对象签发短期 Bearer 能力，不包含 OSS 全局凭证。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.worker-auth", name = "enabled", havingValue = "true")
public class ArtifactAccessTokenCodec {
    private static final String VERSION = "at1";
    private static final String HMAC = "HmacSHA256";
    private static final int MAX_TOKEN_LENGTH = 4096;
    private final InternalWorkerAuthProperties properties;
    private final ObjectMapper mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public ArtifactAccessTokenCodec(InternalWorkerAuthProperties properties) {
        this.properties = properties;
    }

    /** 票据绑定任务批次、执行者、操作和对象；任一字段变化都必须重新签发。 */
    public String issue(ArtifactGrant grant) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(grant));
            return VERSION + "." + payload + "." + signature(payload);
        } catch (Exception error) {
            throw new ServiceException("源文件票据生成失败");
        }
    }

    /** 先校验固定格式和 HMAC，再解析载荷，避免信任调用方可修改的数据。 */
    public ArtifactGrant verify(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) deny();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0]) || parts[1].isEmpty()
            || !parts[2].matches("^[a-f0-9]{64}$")) deny();
        String expected = signature(parts[1]);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            parts[2].getBytes(StandardCharsets.US_ASCII))) deny();
        try {
            ArtifactGrant grant = mapper.readValue(Base64.getUrlDecoder().decode(parts[1]), ArtifactGrant.class);
            if (grant.jobId() == null || grant.requestId() == null || grant.attemptId() == null
                || grant.generationId() == null || grant.workerId() == null || grant.operation() == null
                || grant.objectId() == null || grant.expiresAtEpochSecond() == null
                || grant.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) {
                deny();
            }
            return grant;
        } catch (Exception error) {
            deny();
            return null;
        }
    }

    private String signature(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(properties.getSigningKey().getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal((VERSION + "\n" + payload)
                .getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("源文件票据签名算法不可用", error);
        }
    }

    private void deny() {
        throw new ArtifactAccessDeniedException();
    }

    public record ArtifactGrant(Long jobId, String requestId, String attemptId, Long generationId,
                                String workerId, String operation, Long objectId,
                                Long expiresAtEpochSecond) { }

    /** 不携带外部输入，避免无效票据进入日志和错误响应。 */
    public static class ArtifactAccessDeniedException extends RuntimeException {
        public ArtifactAccessDeniedException() { super("源文件票据无效"); }
    }
}
