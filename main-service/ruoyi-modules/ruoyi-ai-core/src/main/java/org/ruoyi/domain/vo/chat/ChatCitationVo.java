package org.ruoyi.domain.vo.chat;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 用户可见的消息引用，不包含内部复核票据和存储对象地址。 */
@Data
public class ChatCitationVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 引用所属的助手消息 ID；流式候选阶段可能为空。 */
    private Long messageId;

    /** 单次回答内稳定且唯一的引用标识。 */
    private String citationId;

    /** 来源文档标题。 */
    private String title;

    /** 来源所在的章节标题路径。 */
    private List<String> titlePath;

    /** 页码、段落或图片位置等结构化定位信息。 */
    private JsonNode locator;

    /** 实际支持回答的来源原文摘要。 */
    private String excerpt;
}