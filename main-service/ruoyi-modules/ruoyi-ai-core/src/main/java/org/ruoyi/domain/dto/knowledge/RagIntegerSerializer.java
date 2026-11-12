package org.ruoyi.domain.dto.knowledge;

import com.fasterxml.jackson.databind.ser.std.NumberSerializer;
import java.math.BigInteger;

/** 固定旧聊天整数的 JSON 数值形态，避免全局大整数转字符串策略改变内部协议。 */
public final class RagIntegerSerializer extends NumberSerializer {
    public RagIntegerSerializer() {
        super(BigInteger.class);
    }
}