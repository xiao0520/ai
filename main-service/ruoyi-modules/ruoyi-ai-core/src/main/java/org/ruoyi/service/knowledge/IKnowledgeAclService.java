package org.ruoyi.service.knowledge;

import org.ruoyi.domain.bo.knowledge.KnowledgeAclBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeAclVo;

import java.util.List;

/**
 * 知识库ACL权限管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IKnowledgeAclService {

    /**
     * 查询知识库ACL权限列表
     *
     * @param knowledgeId 知识库ID
     * @return ACL权限列表
     */
    List<KnowledgeAclVo> list(Long knowledgeId);

    /**
     * 返回当前用户可读的知识库 ID，用于列表强制过滤
     *
     * @return 知识库ID列表
     */
    List<Long> accessibleKnowledgeIds();

    /**
     * 保存知识库ACL权限
     *
     * @param bo ACL权限
     * @return 是否保存成功
     */
    Boolean save(KnowledgeAclBo bo);

    /**
     * 删除知识库ACL权限
     *
     * @param id 主键
     * @return 是否删除成功
     */
    Boolean delete(Long id);

    /**
     * 校验知识库访问权限
     *
     * @param knowledgeId 知识库ID
     * @param permission  权限
     */
    void require(Long knowledgeId, String permission);

    /**
     * 判断是否可以访问知识库
     *
     * @param knowledgeId 知识库ID
     * @param required    所需权限
     * @return 是否可以访问
     */
    boolean canAccess(Long knowledgeId, String required);

    /**
     * 使用用户当前角色和部门复核知识库权限，不依赖旧登录会话快照。
     *
     * @param userId 用户ID
     * @param knowledgeId 知识库ID
     * @param required 所需权限
     * @return 是否可以访问
     */
    boolean canAccess(Long userId, Long knowledgeId, String required);
}
