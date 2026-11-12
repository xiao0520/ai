package org.ruoyi.domain.vo.agent;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Agent 角色、部门权限视图。
 */
@Data
public class AgentAclVo implements Serializable {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 智能体ID
     */
    private Long agentId;
    /**
     * 主体类型
     */
    private String subjectType;
    /**
     * 主体ID
     */
    private Long subjectId;
    /**
     * 主体名称
     */
    private String subjectName;
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
