package org.ruoyi.domain.vo.knowledge;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;

import java.io.Serializable;
import java.util.Date;

/**
 * 知识库解析任务视图。
 */
@Data
@AutoMapper(target = KnowledgeParseJob.class)
public class KnowledgeParseJobVo implements Serializable {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 请求ID
     */
    private String requestId;
    /**
     * 作业类型
     */
    private String jobType;
    /**
     * 知识库ID
     */
    private Long knowledgeId;
    /**
     * 文档ID
     */
    private Long documentId;
    /**
     * 文档版本ID
     */
    private Long documentVersionId;
    /** REBUILD 父任务ID。 */
    private Long parentJobId;
    /**
     * 目标索引版本
     */
    private String targetIndexVersion;
    /**
     * 幂等键
     */
    private String idempotencyKey;
    /**
     * 状态
     */
    private String status;
    /**
     * 进度
     */
    private Integer progress;
    /**
     * 尝试次数
     */
    private Integer attemptCount;
    /**
     * 最大尝试次数
     */
    private Integer maxAttempts;
    /**
     * 最后序列号
     */
    private Long lastSequence;
    /**
     * 租约持有者
     */
    private String leaseOwner;
    /**
     * 租约过期时间
     */
    private Date leaseExpireTime;
    /**
     * 错误编码
     */
    private String errorCode;
    /**
     * 错误信息
     */
    private String errorMessage;
    /**
     * 开始时间
     */
    private Date startedAt;
    /**
     * 完成时间
     */
    private Date finishedAt;
    /**
     * 创建时间
     */
    private Date createTime;
    /**
     * 更新时间
     */
    private Date updateTime;
}
