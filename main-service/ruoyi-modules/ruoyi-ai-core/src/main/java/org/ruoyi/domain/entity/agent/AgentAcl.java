package org.ruoyi.domain.entity.agent;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * Agent 角色/部门使用权限。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_acl")
public class AgentAcl extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
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
