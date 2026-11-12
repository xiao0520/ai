package org.ruoyi.domain.bo.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

/**
 * Agent 单条角色或部门授权。
 */
@Data
public class AgentAclItemBo {
    /**
     * 主体类型
     */
    @NotBlank(message = "授权主体类型不能为空")
    private String subjectType;
    /**
     * 主体ID
     */
    @NotNull(message = "授权主体不能为空")
    private Long subjectId;
    /**
     * 权限
     */
    private String permission;
    /**
     * 生效时间
     */
    private Date effectiveTime;
    /**
     * 过期时间
     */
    private Date expireTime;
    /**
     * 状态
     */
    private String status;
    /**
     * 备注
     */
    private String remark;
}
