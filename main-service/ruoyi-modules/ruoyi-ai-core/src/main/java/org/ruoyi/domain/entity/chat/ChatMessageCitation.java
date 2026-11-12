package org.ruoyi.domain.entity.chat;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/** RAG 助手消息实际使用的不可变来源引用。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message_citation")
public class ChatMessageCitation extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键。 */
    @TableId("id")
    private Long id;

    /** 引用所属的助手消息 ID。 */
    private Long messageId;

    /** 单次回答内稳定且唯一的引用标识。 */
    private String citationId;

    /** 来源知识库 ID。 */
    private Long knowledgeId;

    /** 检索时使用的发布索引版本 ID。 */
    private Long indexVersionId;

    /** 来源文档 ID。 */
    private Long documentId;

    /** 来源文档版本 ID。 */
    private Long documentVersionId;

    /** 来源文档本次入库产物批次 ID。 */
    private Long generationId;

    /** 来源切片稳定标识。 */
    private String chunkId;

    /** 来源内容摘要，用于回访时校验内容未变化。 */
    private String contentHash;

    /** 来源文档标题。 */
    private String title;

    /** 章节标题路径 JSON。 */
    private String titlePathJson;

    /** 页码、段落或图片位置等定位信息 JSON。 */
    private String locatorJson;

    /** 实际支持回答的来源原文摘要。 */
    private String excerpt;
}