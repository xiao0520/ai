package org.ruoyi.domain.vo.token;

import lombok.Data;
import org.ruoyi.common.translation.annotation.Translation;
import org.ruoyi.common.translation.constant.TransConstant;

import java.io.Serial;
import java.io.Serializable;

/**
 * Token 额度分配员工选项。
 */
@Data
public class AiTokenUserOptionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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
     * 状态
     */
    private String status;
    /**
     * 是否已配置
     */
    private Boolean configured;

    /**
     * 部门名称
     */
    @Translation(type = TransConstant.DEPT_ID_TO_NAME, mapper = "deptId")
    private String deptName;
}
