package org.ruoyi.service.chat;

import org.ruoyi.domain.vo.agent.ChatMyAgentOptionVo;
import org.ruoyi.domain.vo.knowledge.ChatKnowledgeOptionVo;
import org.ruoyi.domain.vo.model.MyModelOptionVo;

import java.util.List;

/**
 * 用户聊天页选项 Service 接口。
 */
public interface IChatUserOptionService {

    /**
     * 查询当前用户可选择的聊天模型。
     *
     * 返回结果仅包含已授权、已启用且允许展示的聊天模型，不包含运行配置和凭证信息。
     *
     * @return 当前用户可选择的聊天模型
     */
    List<MyModelOptionVo> queryMyModels();

    /**
     * 查询当前用户可选择的智能体。
     *
     * 返回结果基于 Agent ACL 和模型权限过滤，且不包含智能体的内部运行配置。
     *
     * @return 当前用户可选择的智能体
     */
    List<ChatMyAgentOptionVo> queryMyAgents();

    /**
     * 查询当前用户有读取权限且索引已经可用的知识库。
     *
     * @return 聊天页知识库选项
     */
    List<ChatKnowledgeOptionVo> queryMyKnowledgeOptions();
}
