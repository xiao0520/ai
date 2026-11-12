package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.integration.kafka.KnowledgeOutboxMessage;

import java.util.List;

/** Outbox 只在业务事务中新增，网络发送在事务外执行。 */
@Mapper
public interface KnowledgeOutboxMapper {
    int insertMessage(@Param("message") KnowledgeOutboxMessage message, @Param("delaySeconds") int delaySeconds);
    List<KnowledgeOutboxMessage> selectDue(@Param("limit") int limit);
    int claim(@Param("id") String id, @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);
    int markSent(@Param("id") String id, @Param("token") String token);
    int markFailure(@Param("id") String id, @Param("token") String token,
                    @Param("delaySeconds") int delaySeconds, @Param("maxAttempts") int maxAttempts);
    int cancelCommands(@Param("jobId") Long jobId, @Param("attemptId") String attemptId, @Param("topic") String topic);
}