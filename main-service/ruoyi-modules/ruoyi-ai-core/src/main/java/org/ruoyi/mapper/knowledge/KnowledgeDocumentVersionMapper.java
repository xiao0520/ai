package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocumentVersion;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVersionVo;

import java.util.List;

/** 知识库文档版本Mapper。 */
@Mapper
public interface KnowledgeDocumentVersionMapper extends BaseMapperPlus<KnowledgeDocumentVersion, KnowledgeDocumentVersionVo> {

    /** 查询指定文档全部版本关联的 OSS 文件主键。 */
    List<Long> selectOssIdsByDocumentId(@Param("documentId") Long documentId);

    /** 查询指定知识库全部文档版本关联的 OSS 文件主键。 */
    List<Long> selectOssIdsByKnowledgeId(@Param("knowledgeId") Long knowledgeId);

    /** 查询指定文档已持久化的最大版本号。 */
    Integer selectMaxVersionNoByDocumentId(@Param("documentId") Long documentId);

    List<KnowledgeDocumentVersionVo> selectVoListByDocumentId(@Param("documentId") Long documentId);

    /** 冻结知识库当前已发布文档版本，供 REBUILD 父任务扇出子任务。 */
    List<KnowledgeDocumentVersion> selectCurrentPublishedByKnowledgeId(@Param("knowledgeId") Long knowledgeId);

    int updateParseStatusByDocumentId(@Param("documentId") Long documentId, @Param("parseStatus") String parseStatus);
}
