package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.knowledge.KnowledgeAcl;
import org.ruoyi.domain.vo.knowledge.KnowledgeAclVo;

import java.util.Collection;
import java.util.Date;
import java.util.List;

@Mapper
public interface KnowledgeAclMapper extends BaseMapperPlus<KnowledgeAcl, KnowledgeAclVo> {

    /** 按知识库查询全部 ACL 列表。 */
    List<KnowledgeAclVo> selectVoListByKnowledgeId(@Param("knowledgeId") Long knowledgeId);

    /** 按当前用户的角色、部门及时间窗查询可访问的知识库主键。 */
    List<Long> selectEffectiveKnowledgeIds(@Param("status") String status, @Param("effectiveAt") Date effectiveAt,
                                           @Param("roleIds") Collection<String> roleIds,
                                           @Param("deptIds") Collection<Long> deptIds,
                                           @Param("directDeptId") Long directDeptId,
                                           @Param("roleSubjectType") String roleSubjectType,
                                           @Param("deptSubjectType") String deptSubjectType,
                                           @Param("includeChildren") String includeChildren,
                                           @Param("excludeChildren") String excludeChildren);

    /** 查询指定知识库在给定时刻生效的 ACL 授权。 */
    List<KnowledgeAcl> selectEffectiveByKnowledgeId(@Param("knowledgeId") Long knowledgeId,
                                                    @Param("status") String status,
                                                    @Param("effectiveAt") Date effectiveAt);
}

