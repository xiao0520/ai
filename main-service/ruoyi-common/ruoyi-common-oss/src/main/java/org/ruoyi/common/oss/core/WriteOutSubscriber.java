package org.ruoyi.common.oss.core;

import java.io.IOException;

/**
 * 写出订阅器
 *
 * @author xiaoqh
 */
@FunctionalInterface
public interface WriteOutSubscriber<T> {

    void writeTo(T out) throws IOException;

}

