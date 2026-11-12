package org.ruoyi.mapper.model;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.model.ModelProfileRevision;

/** 模型 Profile 发布快照持久层。 */
public interface ModelProfileRevisionMapper extends BaseMapperPlus<ModelProfileRevision, ModelProfileRevision> {
    /** 读取指定模型的精确已发布修订，禁止回退到最新配置。 */
    @Select("SELECT id, model_id, revision, profile_snapshot, status FROM model_profile_revision "
        + "WHERE model_id = #{modelId} AND revision = #{revision} AND status = 'PUBLISHED' LIMIT 1")
    ModelProfileRevision selectPublished(@Param("modelId") Long modelId, @Param("revision") Integer revision);
}
