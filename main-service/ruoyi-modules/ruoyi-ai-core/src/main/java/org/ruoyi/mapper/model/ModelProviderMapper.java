package org.ruoyi.mapper.model;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.bo.model.ModelProviderBo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.model.ModelProvider;
import org.ruoyi.domain.vo.model.ModelProviderVo;

import java.util.List;

/**
 * 厂商管理Mapper接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface ModelProviderMapper extends BaseMapperPlus<ModelProvider, ModelProviderVo> {

    /** 按管理端筛选条件查询厂商列表；传入分页对象时由分页插件处理分页。 */
    List<ModelProviderVo> selectProviderList(@Param("page") Page<ModelProviderVo> page,
                                             @Param("bo") ModelProviderBo bo);

    /** 判断厂商编码是否已被同一逻辑删除状态的数据占用。 */
    boolean existsByProviderCode(@Param("providerCode") String providerCode, @Param("excludeId") Long excludeId,
                                 @Param("normalDelFlag") String normalDelFlag);

    /** 按编码读取当前可用厂商，作为模型 API 地址的权威配置源。 */
    ModelProvider selectEnabledByProviderCode(@Param("providerCode") String providerCode,
                                              @Param("normalStatus") String normalStatus,
                                              @Param("normalDelFlag") String normalDelFlag);
}
