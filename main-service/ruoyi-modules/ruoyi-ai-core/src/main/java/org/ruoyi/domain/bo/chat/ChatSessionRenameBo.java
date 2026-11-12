package org.ruoyi.domain.bo.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户侧修改会话标题请求。
 */
@Data
public class ChatSessionRenameBo {

    /**
     * 会话标题
     */
    @NotBlank(message = "会话标题不能为空")
    @Size(max = 255, message = "会话标题长度不能超过 255 个字符")
    private String sessionTitle;
}
