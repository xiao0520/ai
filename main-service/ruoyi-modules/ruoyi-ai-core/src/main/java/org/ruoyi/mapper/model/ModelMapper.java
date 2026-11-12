package org.ruoyi.mapper.model;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.bo.model.ModelBo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.model.Model;
import org.ruoyi.domain.vo.model.MyModelOptionVo;
import org.ruoyi.domain.vo.model.KnowledgeModelOptionVo;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;

import java.util.Collection;
import java.util.List;

/**
 * 模型管理Mapper接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface ModelMapper extends BaseMapperPlus<Model, ModelVo> {

    /** 根据模型名称查询排序靠前的模型主键。 */
    Long selectFirstIdByModelName(@Param("modelName") String modelName);

    /** 按管理端筛选条件查询模型列表；传入分页对象时由分页插件处理分页。 */
    List<ModelVo> selectModelList(@Param("page") Page<ModelVo> page, @Param("bo") ModelBo bo);

    List<ModelVo> selectModelVoByIds(@Param("ids") Collection<Long> ids);

    List<MyModelOptionVo> selectVisibleChatOptions(@Param("ids") Collection<Long> ids,
                                                    @Param("category") String category,
                                                    @Param("status") String status,
                                                    @Param("modelShow") String modelShow);

    List<AiModelOptionVo> selectEnabledOptions(@Param("status") String status);

    List<AiModelOptionVo> selectEnabledOptionsByIds(@Param("ids") Collection<Long> ids,
                                                     @Param("status") String status);

    /** 查询知识库页面可使用的指定分类模型。 */
    List<KnowledgeModelOptionVo> selectKnowledgeOptions(@Param("category") String category,
                                                         @Param("status") String status);

    boolean existsByProviderCodes(@Param("providerCodes") Collection<String> providerCodes);

    boolean existsByProfile(@Param("providerCode") String providerCode, @Param("modelName") String modelName,
                            @Param("category") String category, @Param("excludedId") Long excludedId);

    /** 锁定指定厂商的模型 Profile，供批量凭证别名变更时生成一致的修订快照。 */
    List<Model> selectByProviderCodeForUpdate(@Param("providerCode") String providerCode);

    /** 锁定指定模型 Profile，避免并发更新产生相同修订号。 */
    Model selectByIdForUpdate(@Param("id") Long id);

}
