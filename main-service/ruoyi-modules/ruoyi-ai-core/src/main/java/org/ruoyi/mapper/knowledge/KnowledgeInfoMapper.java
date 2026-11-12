package org.ruoyi.mapper.knowledge;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Collection;
import java.util.List;

/**
 * 知识库Mapper接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface KnowledgeInfoMapper extends BaseMapperPlus<KnowledgeInfo, KnowledgeInfoVo> {

    /** 按知识库筛选与授权范围查询列表；传入分页对象时由分页插件处理分页。 */
    List<KnowledgeInfoVo> selectKnowledgeList(@Param("page") Page<KnowledgeInfoVo> page,
                                              @Param("bo") KnowledgeInfoBo bo,
                                              @Param("authorizedIds") Collection<Long> authorizedIds);

    /** 查询全部知识库主键，供平台管理员计算可访问范围。 */
    List<Long> selectAllIds();

    /** 查询未逻辑删除的全员可读知识库主键，创建人不参与授权范围计算。 */
    List<Long> selectAllInternalIds(@Param("allInternalScope") String allInternalScope);

    List<KnowledgeInfoVo> selectKnowledgeVoByIds(@Param("ids") Collection<Long> ids);

    /** 锁定知识库行，返回空表示记录不存在。 */
    Long selectIdForUpdate(@Param("id") Long id);

    int updateCurrentIndexVersion(@Param("id") Long id, @Param("indexVersion") String indexVersion);

    /** 仅当目标版本仍是当前指针时清空，避免并发切换后误清新版本。 */
    int clearCurrentIndexVersion(@Param("id") Long id, @Param("indexVersion") String indexVersion);
}
