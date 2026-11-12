package org.ruoyi.domain.bo.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * 按厂商批量更新凭证别名业务对象
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ModelBatchKeyBo extends BaseEntity {

    /**
     * 提供者编码
     */
    @NotBlank(message = "厂商编码不能为空")
    private String providerCode;

    /**
     * Credential键
     */
    @NotBlank(message = "凭证别名不能为空")
    private String credentialKey;

}

