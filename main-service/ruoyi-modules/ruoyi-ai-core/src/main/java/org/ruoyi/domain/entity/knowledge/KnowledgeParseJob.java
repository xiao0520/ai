package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * Java 控制面持久化的知识库解析任务。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_parse_job")
public class KnowledgeParseJob extends BaseEntity {
    /**
     * 主键ID
     */
    @TableId("id")
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
     * 是否从管理列表隐藏
     */
    private String listHidden;
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
    /** 当前 Kafka 执行批次，重试生成新值，重复投递保持不变。 */
    private String attemptId;
    /** 当前尝试的独立产物批次，旧批次不得覆盖新结果。 */
    private Long generationId;
    /** 已接受序列的结果摘要，用于区分重复与冲突回报。 */
    private String lastResultHash;
    /** 首次执行时固化的完整 IndexSnapshot；后续重试不得漂移。 */
    private String indexSnapshot;
    /** 文档版本冻结的完整处理与查询路由快照。 */
    private String documentProcessingSnapshot;
    /** 历史兼容字段，新任务不再写入。 */
    private String rerankSnapshot;
    /** REBUILD 父任务ID；普通任务为空。 */
    private Long parentJobId;
    /** CLEANUP/RECONCILE 的冻结任务判别体 JSON。 */
    private String taskPayload;
    /** 本 attempt 使用的用户额度账户。 */
    private Long budgetAccountId;
    /** 本 attempt 已预占 Token；无限额度账户为零。 */
    private Long budgetReservedTokens;
    /** 本 attempt 下发给 Python 的模型 Token 上限。 */
    private Integer executionBudgetTokens;
    /** 预算状态：NONE、RESERVED、SETTLED、RELEASED。 */
    private String budgetStatus;
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
}
