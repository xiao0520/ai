package org.ruoyi.domain.entity.token;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 角色模型权限。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("role_model_permission")
public class AiRoleModelPermission extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId("id")
    private Long id;

    /**
     * 角色ID
     */
    private Long roleId;
    /**
     * 模型ID
     */
    private Long modelId;
    /**
     * 状态
     */
    private String status;
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
    private String remark;
}
