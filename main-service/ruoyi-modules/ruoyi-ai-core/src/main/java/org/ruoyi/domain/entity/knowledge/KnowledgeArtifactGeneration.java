package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Python 写入、Java 验收的单次不可变技术产物批次。 */
@Data
@TableName("knowledge_artifact_generation")
public class KnowledgeArtifactGeneration {
    @TableId("generation_id")
    private Long generationId;
    private Long jobId;
    private String attemptId;
    private Long knowledgeId;
    private Long indexVersionId;
    private Long documentId;
    private Long documentVersionId;
    private Long manifestOssId;
    private String manifestHash;
    private Long manifestSize;
    private String configHash;
    private String sourceHash;
    private Integer vectorDimensions;
    private Integer visionVectorDimensions;
    private Integer chunkCount;
    private Integer denseCount;
    private Integer sparseCount;
    private Integer visionCount;
    private String status;
    /** 允许物理删除 Qdrant generation 和 OSS 清单的时间。 */
    private Date cleanupAfter;
    /** Qdrant 与 OSS 均完成清理的时间。 */
    private Date cleanedAt;
    /** 最近一次维护失败摘要。 */
    private String cleanupError;
    private Date createTime;
    private Date updateTime;
}
