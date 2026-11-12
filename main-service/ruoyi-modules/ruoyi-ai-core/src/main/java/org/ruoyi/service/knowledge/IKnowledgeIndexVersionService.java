package org.ruoyi.service.knowledge;

import org.ruoyi.domain.bo.knowledge.KnowledgeIndexVersionBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeIndexVersionVo;

import java.util.List;

/**
 * 知识库索引版本管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IKnowledgeIndexVersionService {

    /**
     * 查询知识库索引版本列表
     *
     * @param knowledgeId 知识库ID
     * @return 索引版本列表
     */
    List<KnowledgeIndexVersionVo> list(Long knowledgeId);

    /**
     * 根据ID查询知识库索引版本
     *
     * @param id 主键
     * @return 索引版本
     */
    KnowledgeIndexVersionVo query(Long id);

    /**
     * 保存知识库索引版本
     *
     * @param bo 索引版本
     * @return 是否保存成功
     */
    Boolean save(KnowledgeIndexVersionBo bo);

    /**
     * 删除没有文档成员和运行任务的索引版本；当前生效版本会同时解除知识库指针。
     *
     * @param id 主键
     * @return 是否删除成功
     */
    Boolean remove(Long id);

    /**
     * 激活知识库索引版本
     *
     * @param id 主键
     * @return 是否激活成功
     */
    Boolean activate(Long id);
}
