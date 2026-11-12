package org.ruoyi.domain.vo.token;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Token 统计维度数据。
 */
@Data
public class AiTokenStatItemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 键
     */
    private String key;
    /**
     * 名称
     */
    private String name;
    /**
     * 调用次数
     */
    private Long calls;
    /**
     * 实际消耗令牌数
     */
    private Long actualTokens;
    /**
     * 计费令牌数
     */
    private Long chargedTokens;
}
