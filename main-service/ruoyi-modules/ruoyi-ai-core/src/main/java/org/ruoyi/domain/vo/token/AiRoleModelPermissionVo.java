package org.ruoyi.domain.vo.token;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 角色模型权限视图。
 */
@Data
public class AiRoleModelPermissionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 角色ID
     */
    private Long roleId;
    /**
     * 角色名称
     */
    private String roleName;
    /**
     * 角色权限字符串
     */
    private String roleKey;
    /**
     * 角色排序
     */
    private Integer roleSort;
    /**
     * 角色状态
     */
    private String roleStatus;
    /**
     * 模型ID列表
     */
    private List<Long> modelIds = new ArrayList<>();
    /**
     * 模型列表
     */
    private List<AiModelOptionVo> models = new ArrayList<>();
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
