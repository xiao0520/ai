package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.knowledge.KnowledgeIndexVersion;
import org.ruoyi.domain.vo.knowledge.KnowledgeIndexVersionVo;

import java.util.Date;
import java.util.List;
import java.util.Set;

@Mapper
public interface KnowledgeIndexVersionMapper extends BaseMapperPlus<KnowledgeIndexVersion, KnowledgeIndexVersionVo> {

    List<KnowledgeIndexVersionVo> selectVoListByKnowledgeId(@Param("knowledgeId") Long knowledgeId,
                                                            @Param("deletedStatus") String deletedStatus);

    /** 软删除已解除当前指针且没有运行任务的索引版本；生效版本还必须没有文档成员。 */
    int softDeleteRemovableById(@Param("id") Long id, @Param("deletedStatus") String deletedStatus,
                                @Param("activeStatus") String activeStatus,
                                @Param("activeStatuses") Set<String> activeStatuses);

    /** 查询需要周期性执行 Qdrant/OSS 对账的可用索引。 */
    List<KnowledgeIndexVersion> selectMaintenanceCandidates(@Param("limit") int limit);

    KnowledgeIndexVersion selectByKnowledgeAndVersion(@Param("knowledgeId") Long knowledgeId,
                                                       @Param("indexVersion") String indexVersion);

    /** 锁定目标索引版本，供任务提交与删除串行校验。 */
    KnowledgeIndexVersion selectByKnowledgeAndVersionForUpdate(@Param("knowledgeId") Long knowledgeId,
                                                                @Param("indexVersion") String indexVersion);

    boolean existsByKnowledgeAndVersion(@Param("knowledgeId") Long knowledgeId,
                                        @Param("indexVersion") String indexVersion, @Param("excludeId") Long excludeId);

    int archiveActiveByKnowledgeId(@Param("knowledgeId") Long knowledgeId, @Param("activeStatus") String activeStatus,
                                   @Param("archivedStatus") String archivedStatus, @Param("excludeId") Long excludeId);

    /** 将可用、已归档或已生效的索引版本置为生效状态。 */
    int activateAvailableById(@Param("id") Long id, @Param("readyStatus") String readyStatus,
                              @Param("archivedStatus") String archivedStatus,
                              @Param("activeStatus") String activeStatus, @Param("activatedAt") Date activatedAt);

    /** 激活后将该索引已验收的文档版本发布为逻辑文档当前版本。 */
    int publishMemberDocuments(@Param("indexVersionId") Long indexVersionId);
}

