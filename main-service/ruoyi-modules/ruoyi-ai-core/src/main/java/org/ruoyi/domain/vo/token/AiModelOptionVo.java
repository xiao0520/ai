package org.ruoyi.domain.vo.token;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型授权选项。
 */
@Data
public class AiModelOptionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模型ID
     */
    private Long modelId;
    /**
     * 模型名称
     */
    private String modelName;
    /**
     * 提供者编码
     */
    private String providerCode;
    /**
     * 分类
     */
    private String category;
}
