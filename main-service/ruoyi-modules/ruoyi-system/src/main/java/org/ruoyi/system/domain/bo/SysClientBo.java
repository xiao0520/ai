package org.ruoyi.system.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import org.ruoyi.system.domain.SysClient;

/**
 * 系统授权业务对象 sys_client
 *
 * @author xiaoqh
 */

@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = SysClient.class, reverseConvertGenerate = false)
public class SysClientBo extends BaseEntity {

    /**
     * id
     */
    private Long id;

    /**
     * 客户端id
     */
    @NotBlank(message = "客户端id不能为空")
    @Size(min = 0, max = 64, message = "客户端id长度不能超过{max}个字符")
    private String clientId;

    /**
     * 客户端key
     */
    @NotBlank(message = "客户端key不能为空")
    @Size(min = 0, max = 32, message = "客户端key长度不能超过{max}个字符")
    private String clientKey;

    /**
     * 客户端秘钥
     */
    @NotBlank(message = "客户端秘钥不能为空")
    @Size(min = 0, max = 255, message = "客户端秘钥长度不能超过{max}个字符")
    private String clientSecret;

    /**
     * 授权类型
     */
    @NotBlank(message = "授权类型不能为空")
    @Size(min = 0, max = 255, message = "授权类型长度不能超过{max}个字符")
    private String grantType;

    /**
     * 设备类型
     */
    @NotBlank(message = "设备类型不能为空")
    @Size(min = 0, max = 32, message = "设备类型长度不能超过{max}个字符")
    private String deviceType;

    /**
     * token活跃超时时间
     */
    private Integer activeTimeout;

    /**
     * token固定超时
     */
    private Integer timeout;

    /**
     * 状态（0正常 1停用）
     */
    private String status;

}
