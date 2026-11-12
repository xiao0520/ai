package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.entity.knowledge.KnowledgeArtifactGeneration;
import org.ruoyi.domain.entity.knowledge.KnowledgeIndexDocument;
import org.ruoyi.domain.vo.knowledge.KnowledgeManifestMemberRow;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalScopeRow;

import java.util.Date;
import java.util.List;

/** 产物上传幂等、验收、发布和生命周期维护 SQL 边界。 */
@Mapper
public interface KnowledgeArtifactMapper {
    KnowledgeArtifactGeneration selectGeneration(@Param("generationId") Long generationId);
    KnowledgeIndexDocument selectIndexDocument(@Param("indexVersionId") Long indexVersionId,
                                                @Param("documentId") Long documentId);
    int insertGenerationIgnore(@Param("generation") KnowledgeArtifactGeneration generation);
    int markGenerationReady(@Param("generationId") Long generationId,
                            @Param("denseCount") int denseCount,
                            @Param("sparseCount") int sparseCount,
                            @Param("visionCount") int visionCount);
    int markGenerationRetired(@Param("generationId") Long generationId,
                              @Param("cleanupAfter") Date cleanupAfter);
    int markGenerationOrphaned(@Param("generationId") Long generationId,
                               @Param("cleanupAfter") Date cleanupAfter,
                               @Param("cleanupError") String cleanupError);
    List<KnowledgeArtifactGeneration> selectCleanupCandidates(@Param("limit") int limit);
    int countIndexReferences(@Param("generationId") Long generationId);
    int markGenerationVectorCleaned(@Param("generationId") Long generationId);
    List<KnowledgeArtifactGeneration> selectManifestCleanupCandidates(@Param("limit") int limit);
    int markGenerationCleaned(@Param("generationId") Long generationId);
    int markCleanupFailed(@Param("generationId") Long generationId,
                          @Param("cleanupError") String cleanupError);
    List<Long> selectRetainedGenerationIds(@Param("indexVersionId") Long indexVersionId);
    List<Long> selectIndexMemberGenerationIds(@Param("indexVersionId") Long indexVersionId);
    int countIndexDocuments(@Param("indexVersionId") Long indexVersionId);
    List<KnowledgeArtifactGeneration> selectGenerationsByIndex(@Param("indexVersionId") Long indexVersionId);
    int deleteIndexDocuments(@Param("indexVersionId") Long indexVersionId);
    int upsertIndexDocument(@Param("member") KnowledgeIndexDocument member);
    int refreshIndexStats(@Param("indexVersionId") Long indexVersionId);
    int markIndexReadyIfComplete(@Param("indexVersionId") Long indexVersionId,
                                 @Param("knowledgeId") Long knowledgeId);
    int publishDocumentForActiveIndex(@Param("indexVersionId") Long indexVersionId,
                                      @Param("documentId") Long documentId,
                                      @Param("documentVersionId") Long documentVersionId);
    KnowledgeRetrievalScopeRow selectRetrievalScope(
        @Param("knowledgeId") Long knowledgeId,
        @Param("knowledgeStatus") String knowledgeStatus,
        @Param("delFlag") String delFlag,
        @Param("indexStatus") String indexStatus,
        @Param("jobStatus") String jobStatus);
    List<KnowledgeManifestMemberRow> selectManifestMembers(
        @Param("indexVersionId") Long indexVersionId,
        @Param("knowledgeStatus") String knowledgeStatus,
        @Param("delFlag") String delFlag,
        @Param("indexStatus") String indexStatus,
        @Param("documentStatus") String documentStatus,
        @Param("versionStatus") String versionStatus,
        @Param("generationStatus") String generationStatus,
        @Param("offset") int offset,
        @Param("limit") int limit);
    List<KnowledgeManifestMemberRow> selectDocumentRoutes(
        @Param("indexVersionId") Long indexVersionId,
        @Param("knowledgeStatus") String knowledgeStatus,
        @Param("delFlag") String delFlag,
        @Param("indexStatus") String indexStatus,
        @Param("documentStatus") String documentStatus,
        @Param("versionStatus") String versionStatus,
        @Param("generationStatus") String generationStatus);
    List<KnowledgeManifestMemberRow> selectValidMembers(
        @Param("indexVersionId") Long indexVersionId,
        @Param("generationIds") List<Long> generationIds,
        @Param("knowledgeStatus") String knowledgeStatus,
        @Param("delFlag") String delFlag,
        @Param("indexStatus") String indexStatus,
        @Param("documentStatus") String documentStatus,
        @Param("versionStatus") String versionStatus,
        @Param("generationStatus") String generationStatus);

}
