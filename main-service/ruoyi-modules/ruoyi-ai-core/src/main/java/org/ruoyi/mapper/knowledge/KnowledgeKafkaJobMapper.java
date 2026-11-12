package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;

import java.util.Date;
import java.util.List;

/** Kafka 任务状态竞争全部在 SQL 与短事务内完成。 */
@Mapper
public interface KnowledgeKafkaJobMapper {
    KnowledgeParseJob lockJob(@Param("id") Long id);
    int prepareAttempt(@Param("job") KnowledgeParseJob job, @Param("deadline") Date deadline);
    int markQueued(@Param("id") Long id, @Param("attemptId") String attemptId);
    int markDeliveryFailed(@Param("id") Long id, @Param("attemptId") String attemptId);
    int acceptEvent(@Param("id") Long id, @Param("attemptId") String attemptId,
                    @Param("sequence") long sequence, @Param("hash") String hash,
                    @Param("status") String status, @Param("progress") int progress,
                    @Param("workerId") String workerId, @Param("leaseSeconds") int leaseSeconds,
                    @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);
    List<Long> selectExpired(@Param("limit") int limit);
    int requestCancel(@Param("id") Long id, @Param("leaseSeconds") int leaseSeconds);
    int finishCancelled(@Param("id") Long id, @Param("attemptId") String attemptId,
                        @Param("sequence") long sequence, @Param("hash") String hash,
                        @Param("workerId") String workerId);
    int markBudgetStatus(@Param("id") Long id, @Param("attemptId") String attemptId,
                         @Param("expected") String expected, @Param("status") String status);
    int refreshRebuildParent(@Param("parentJobId") Long parentJobId);
    int updateVersionState(@Param("versionId") Long versionId, @Param("documentId") Long documentId,
                           @Param("status") String status, @Param("errorCode") String errorCode,
                           @Param("errorMessage") String errorMessage);
    int updateUnpublishedDocument(@Param("documentId") Long documentId, @Param("status") String status);
}
