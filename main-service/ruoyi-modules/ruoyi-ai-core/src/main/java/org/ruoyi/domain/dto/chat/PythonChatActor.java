package org.ruoyi.domain.dto.chat;

import java.util.List;

/**
 * 由 Java 鉴权并通过内部签名声明的调用主体。
 *
 * @param userId 当前用户 ID
 * @param roles  当前请求需要传递给执行层的角色编码
 */
public record PythonChatActor(String userId, List<String> roles) {
}