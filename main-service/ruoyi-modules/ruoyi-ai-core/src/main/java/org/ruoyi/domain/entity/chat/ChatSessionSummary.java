package org.ruoyi.domain.entity.chat;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 会话历史摘要对象 chat_session_summary
 *
 * @author xiaoqh
 * @date 2026-08-31
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_session_summary")
public class ChatSessionSummary extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 会话ID
     */
    private Long sessionId;

    /**
     * 覆盖消息序号
     */
    private Integer coveredMessageSeq;

    /**
     * 摘要内容
     */
    private String summaryContent;

    /**
     * 摘要令牌数
     */
    private Integer summaryTokens;

    /**
     * 模型ID
     */
    private Long modelId;

    /**
     * 状态
     */
    private String status;
}
