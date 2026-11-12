package org.ruoyi.domain.vo.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户聊天页可选择的模型。
 *
 * 仅包含用户展示和请求模型所需的字段，不暴露厂商地址、凭证别名和计费配置。
 */
@Data
public class MyModelOptionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 模型描述
     */
    private String modelDescribe;

    /**
     * 备注
     */
    private String remark;
}
