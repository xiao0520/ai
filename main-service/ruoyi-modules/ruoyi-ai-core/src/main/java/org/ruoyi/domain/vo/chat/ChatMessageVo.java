package org.ruoyi.domain.vo.chat;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.chat.ChatMessage;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 聊天消息视图对象。
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = ChatMessage.class)
public class ChatMessageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键。 */
    @ExcelProperty(value = "主键")
    private Long id;

    /** 会话主键。 */
    @ExcelProperty(value = "会话ID")
    private Long sessionId;

    /** 会话内消息序号。 */
    @ExcelProperty(value = "消息序号")
    private Integer messageSeq;

    /** 内部用户主键，仅用于接口关联。 */
    private Long userId;

    /** 用户账号。 */
    @ExcelProperty(value = "用户账号")
    private String userName;

    /** 消息内容。 */
    @ExcelProperty(value = "消息内容")
    private String content;

    /** 消息角色。 */
    @ExcelProperty(value = "消息角色")
    private String role;

    /** 实际调用模型主键。 */
    @ExcelProperty(value = "模型ID")
    private Long modelId;

    /** 模型选择模式：FIXED 或 AUTO。 */
    @ExcelProperty(value = "模型选择模式")
    private String modelSelectionMode;

    /** 实际调用的模型发布修订号。 */
    @ExcelProperty(value = "模型修订")
    private Integer modelRevision;

    /** 路由原因编码。 */
    @ExcelProperty(value = "路由原因")
    private String routeReasonCode;

    /** 路由策略修订号。 */
    private Integer routePolicyRevision;

    /** AUTO 候选模型快照 SHA-256。 */
    private String candidateSnapshotHash;

    /** 消息总 Token 数。 */
    @ExcelProperty(value = "总Token数")
    private Long totalTokens;

    /** 输入 Token 数。 */
    @ExcelProperty(value = "输入Token数")
    private Long inputTokens;

    /** 输出 Token 数。 */
    @ExcelProperty(value = "输出Token数")
    private Long outputTokens;

    /** 扣减 Token 数。 */
    @ExcelProperty(value = "扣减Token数")
    private Long deductTokens;

    /** 模型名称快照。 */
    @ExcelProperty(value = "模型名称")
    private String modelName;

    /** 调用状态。 */
    @ExcelProperty(value = "调用状态")
    private String callStatus;

    /** 调用请求标识。 */
    @ExcelProperty(value = "请求标识")
    private String requestId;

    /** 调用失败码。 */
    @ExcelProperty(value = "失败码")
    private String errorCode;

    /** 调用失败说明。 */
    @ExcelProperty(value = "失败说明")
    private String errorMessage;

    /** RAG 助手消息实际使用的来源引用。 */
    private List<ChatCitationVo> citations = List.of();
    /** 创建时间。 */
    @ExcelProperty(value = "创建时间")
    private Date createTime;

    /** 更新时间。 */
    @ExcelProperty(value = "更新时间")
    private Date updateTime;
}
