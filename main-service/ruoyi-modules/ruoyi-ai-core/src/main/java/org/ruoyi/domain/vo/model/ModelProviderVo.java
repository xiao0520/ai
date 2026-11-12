package org.ruoyi.domain.vo.model;

import org.ruoyi.domain.entity.model.ModelProvider;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import org.ruoyi.common.excel.annotation.ExcelDictFormat;
import org.ruoyi.common.excel.convert.ExcelDictConvert;
import org.ruoyi.common.translation.annotation.Translation;
import org.ruoyi.common.translation.constant.TransConstant;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;



/**
 * 厂商管理视图对象 model_provider
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = ModelProvider.class)
public class ModelProviderVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @ExcelProperty(value = "主键")
    private Long id;

    /**
     * 提供者名称
     */
    @ExcelProperty(value = "厂商名称")
    private String providerName;

    /**
     * 提供者编码
     */
    @ExcelProperty(value = "厂商编码")
    private String providerCode;

    /**
     * 提供者图标
     */
    @ExcelProperty(value = "厂商图标")
    private String providerIcon;

    /**
     * 提供者图标地址
     */
    @Translation(type = TransConstant.OSS_ID_TO_URL, mapper = "providerIcon")
    private String providerIconUrl;

    /**
     * 提供者描述
     */
    @ExcelProperty(value = "厂商描述")
    private String providerDesc;

    /**
     * API主机地址
     */
    @ExcelProperty(value = "API地址")
    private String apiHost;

    /**
     * 状态
     */
    @ExcelProperty(value = "状态", converter = ExcelDictConvert.class)
    @ExcelDictFormat(readConverterExp = "0=正常,1=停用")
    private String status;

    /**
     * 排序顺序
     */
    @ExcelProperty(value = "排序")
    private Long sortOrder;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

    /**
     * 更新IP
     */
    @ExcelProperty(value = "更新IP")
    private String updateIp;

    /**
     * 创建时间
     */
    @ExcelProperty(value = "创建时间")
    private Date createTime;

}
