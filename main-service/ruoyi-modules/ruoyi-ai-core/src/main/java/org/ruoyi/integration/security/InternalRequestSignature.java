package org.ruoyi.integration.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Java 与 Python 内部 HTTP 请求共用的签名字节规范。 */
public final class InternalRequestSignature {
    public static final String VERSION = "2";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private InternalRequestSignature() {
    }

    /** 对版本、方法、路径、时戳、nonce 和原始正文摘要创建 HMAC。 */
    public static String sign(String signingKey, String method, String path, String timestamp,
                              String nonce, byte[] body) {
        String canonical = String.join("\n", VERSION, method, path, timestamp, nonce, sha256Hex(body));
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("内部签名算法不可用", error);
        }
    }

    /** 返回原始字节的 SHA-256 小写十六进制摘要。 */
    public static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("内部摘要算法不可用", error);
        }
    }
}
