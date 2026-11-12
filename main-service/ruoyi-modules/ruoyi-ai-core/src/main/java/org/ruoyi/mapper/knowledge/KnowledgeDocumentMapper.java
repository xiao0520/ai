package org.ruoyi.mapper.knowledge;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocument;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentCountVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVo;

import java.util.Collection;
import java.util.List;

/** 知识库逻辑文档Mapper。 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapperPlus<KnowledgeDocument, KnowledgeDocumentVo> {

    /** 按知识库统计未删除的逻辑文档数量。 */
    List<KnowledgeDocumentCountVo> selectDocumentCounts(@Param("knowledgeIds") Collection<Long> knowledgeIds,
                                                        @Param("deletedStatus") String deletedStatus);

    /** 按知识库文档筛选条件查询列表；传入分页对象时由分页插件处理分页。 */
    List<KnowledgeDocumentVo> selectDocumentList(@Param("page") Page<KnowledgeDocumentVo> page,
                                                 @Param("bo") KnowledgeDocumentBo bo,
                                                 @Param("deletedStatus") String deletedStatus);

    /** 锁定指定逻辑文档，用于串行分配其版本号。 */
    KnowledgeDocument selectByIdForUpdate(@Param("id") Long id);

    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
