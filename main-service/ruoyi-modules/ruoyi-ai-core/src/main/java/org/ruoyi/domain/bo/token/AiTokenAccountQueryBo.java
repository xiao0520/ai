package org.ruoyi.domain.bo.token;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * 用户 Token 额度查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiTokenAccountQueryBo extends BaseEntity {

    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 部门ID
     */
    private Long deptId;
    /**
     * 用户账号
     */
    private String userName;
    /**
     * 用户昵称
     */
    private String nickName;
    /**
     * 周期类型
     */
    private String cycleType;
    /**
     * 状态
     */
    private String status;
    /**
     * 是否无限额度
     */
    private String unlimited;
}
