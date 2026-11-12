package org.ruoyi.domain.bo.model;

import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.domain.entity.model.ModelProvider;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 厂商管理业务对象 model_provider
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = ModelProvider.class, reverseConvertGenerate = false)
public class ModelProviderBo extends BaseEntity {

    /**
     * 主键ID
     */
    @NotNull(message = "主键不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     * 提供者名称
     */
    @NotBlank(message = "厂商名称不能为空", groups = { AddGroup.class, EditGroup.class })
    private String providerName;

    /**
     * 提供者编码
     */
    @NotBlank(message = "厂商编码不能为空", groups = { AddGroup.class, EditGroup.class })
    private String providerCode;

    /**
     * 提供者图标
     */
    private String providerIcon;

    /**
     * 提供者描述
     */
    private String providerDesc;

    /**
     * API主机地址
     */
    @NotBlank(message = "API地址不能为空", groups = { AddGroup.class, EditGroup.class })
    private String apiHost;

    /**
     * 状态
     */
    private String status;

    /**
     * 排序顺序
     */
    private Long sortOrder;

    /**
     * 备注
     */
    private String remark;

    /**
     * 更新IP
     */
    private String updateIp;


}
