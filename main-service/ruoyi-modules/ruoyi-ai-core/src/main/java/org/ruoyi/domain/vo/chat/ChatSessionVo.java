package org.ruoyi.domain.vo.chat;

import org.ruoyi.domain.entity.chat.ChatSession;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;


/**
 * 会话管理视图对象 chat_session
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = ChatSession.class)
public class ChatSessionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @ExcelProperty(value = "会话 ID")
    private Long id;

    /** 内部用户主键，仅用于接口关联。 */
    private Long userId;

    /** 用户账号。 */
    @ExcelProperty(value = "用户账号")
    private String userName;

    /**
     * 会话标题
     */
    @ExcelProperty(value = "会话标题")
    private String sessionTitle;

    /**
     * 智能体ID
     */
    @ExcelProperty(value = "Agent ID")
    private Long agentId;

    /**
     * 模型ID
     */
    @ExcelProperty(value = "模型 ID")
    private Long modelId;

    /**
     * 状态
     */
    @ExcelProperty(value = "状态")
    private String status;

    /**
     * 最后消息时间
     */
    @ExcelProperty(value = "最后消息时间")
    private Date lastMessageAt;

    /**
     * 消息数量
     */
    @ExcelProperty(value = "消息数")
    private Integer messageCount;

    /**
     * 输入令牌数
     */
    @ExcelProperty(value = "输入 Token")
    private Long inputTokens;

    /**
     * 输出令牌数
     */
    @ExcelProperty(value = "输出 Token")
    private Long outputTokens;

    /**
     * 扣除令牌数
     */
    @ExcelProperty(value = "扣减 Token")
    private Long deductTokens;

    /**
     * 对话ID
     */
    private String conversationId;

    /**
     * 更新时间
     */
    @ExcelProperty(value = "更新时间")
    private Date updateTime;

}
