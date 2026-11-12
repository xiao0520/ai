package org.ruoyi.domain.bo.token;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 角色模型权限保存参数。
 */
@Data
public class AiRoleModelSaveBo {

    /**
     * 模型ID列表
     */
    @NotNull(message = "模型ID列表不能为空")
    private List<Long> modelIds;

    /**
     * 生效时间
     */
    private Date effectiveTime;
    /**
     * 过期时间
     */
    private Date expireTime;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注不能超过500个字符")
    private String remark;
}
