package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.entity.knowledge.KnowledgeArtifactGeneration;

import java.util.List;
import java.util.Set;

/** 知识库删除生命周期的跨表数据访问。 */
@Mapper
public interface KnowledgeDeletionMapper {

    int countActiveJobsByKnowledge(@Param("knowledgeId") Long knowledgeId,
                                   @Param("activeStatuses") Set<String> activeStatuses);

    int countActiveJobsByDocument(@Param("documentId") Long documentId,
                                  @Param("activeStatuses") Set<String> activeStatuses);

    List<Long> selectIndexVersionIdsByDocument(@Param("documentId") Long documentId);

    List<KnowledgeArtifactGeneration> selectGenerationsByKnowledge(@Param("knowledgeId") Long knowledgeId);

    List<KnowledgeArtifactGeneration> selectGenerationsByDocument(@Param("documentId") Long documentId);

    int markDocumentsDeletedByKnowledge(@Param("knowledgeId") Long knowledgeId,
                                        @Param("deletedStatus") String deletedStatus);

    int markDocumentVersionsDeletedByKnowledge(@Param("knowledgeId") Long knowledgeId,
                                               @Param("deletedStatus") String deletedStatus);

    int markIndexVersionsDeletedByKnowledge(@Param("knowledgeId") Long knowledgeId,
                                            @Param("deletedStatus") String deletedStatus);

    int deleteIndexDocumentsByKnowledge(@Param("knowledgeId") Long knowledgeId);

    int deleteIndexDocumentsByDocument(@Param("documentId") Long documentId);

    int deleteAclByKnowledge(@Param("knowledgeId") Long knowledgeId);

    int deleteAgentBindingsByKnowledge(@Param("knowledgeId") Long knowledgeId);

    List<Long> selectDeletedSourceOssIds(@Param("limit") int limit,
                                         @Param("deletedStatus") String deletedStatus);

    List<Long> selectPurgeableDeletedDocumentIds(@Param("limit") int limit,
                                                  @Param("deletedStatus") String deletedStatus,
                                                  @Param("cleanedStatus") String cleanedStatus,
                                                  @Param("activeStatuses") Set<String> activeStatuses);

    int deleteOutboxByDocument(@Param("documentId") Long documentId);

    int deleteCleanedGenerationsByDocument(@Param("documentId") Long documentId,
                                            @Param("cleanedStatus") String cleanedStatus);

    int deleteJobsByDocument(@Param("documentId") Long documentId);

    int deleteDocumentVersions(@Param("documentId") Long documentId);

    int deleteDocument(@Param("documentId") Long documentId,
                       @Param("deletedStatus") String deletedStatus);

    List<Long> selectPurgeableDeletedIndexVersionIds(@Param("limit") int limit,
                                                      @Param("deletedStatus") String deletedStatus,
                                                      @Param("cleanedStatus") String cleanedStatus,
                                                      @Param("activeStatuses") Set<String> activeStatuses);

    int deleteOutboxByIndex(@Param("indexVersionId") Long indexVersionId);

    int deleteJobsByIndex(@Param("indexVersionId") Long indexVersionId);

    int deleteCleanedGenerationsByIndex(@Param("indexVersionId") Long indexVersionId,
                                        @Param("cleanedStatus") String cleanedStatus);

    int deleteIndexVersion(@Param("indexVersionId") Long indexVersionId,
                           @Param("deletedStatus") String deletedStatus);

    List<Long> selectPurgeableDeletedKnowledgeIds(@Param("limit") int limit,
                                                  @Param("deletedStatus") String deletedStatus,
                                                  @Param("cleanedStatus") String cleanedStatus,
                                                  @Param("activeStatuses") Set<String> activeStatuses);

    int deleteOutboxByKnowledge(@Param("knowledgeId") Long knowledgeId);

    int deleteCleanedGenerationsByKnowledge(@Param("knowledgeId") Long knowledgeId,
                                             @Param("cleanedStatus") String cleanedStatus);

    int deleteJobsByKnowledge(@Param("knowledgeId") Long knowledgeId);

    int deleteIndexVersionsByKnowledge(@Param("knowledgeId") Long knowledgeId,
                                        @Param("deletedStatus") String deletedStatus);

    int deleteKnowledgePhysically(@Param("knowledgeId") Long knowledgeId);
}
