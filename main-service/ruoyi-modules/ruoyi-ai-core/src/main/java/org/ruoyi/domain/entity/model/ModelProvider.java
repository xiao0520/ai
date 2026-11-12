package org.ruoyi.domain.entity.model;

import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 厂商管理对象 model_provider
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_provider")
public class ModelProvider extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 提供者名称
     */
    private String providerName;

    /**
     * 提供者编码
     */
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
     * 版本
     */
    @Version
    private Long version;

    /**
     * 删除标志
     */
    @TableLogic
    private String delFlag;

    /**
     * 更新IP
     */
    private String updateIp;


}
