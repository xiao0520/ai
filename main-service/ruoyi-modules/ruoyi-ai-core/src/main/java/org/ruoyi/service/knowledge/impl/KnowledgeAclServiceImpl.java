package org.ruoyi.service.knowledge.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.PermissionService;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.constant.AiDictTypes;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.knowledge.KnowledgeAclBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeAcl;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.domain.vo.knowledge.KnowledgeAclVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.mapper.knowledge.KnowledgeAclMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.ruoyi.service.knowledge.IKnowledgeAclService;
import org.ruoyi.support.AiBusinessDict;
import org.ruoyi.system.domain.vo.SysDeptVo;
import org.ruoyi.system.domain.vo.SysUserVo;
import org.ruoyi.system.service.ISysDeptService;
import org.ruoyi.system.service.ISysUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 知识库ACL权限管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class KnowledgeAclServiceImpl implements IKnowledgeAclService {
    private final KnowledgeAclMapper baseMapper;
    private final KnowledgeInfoMapper knowledgeMapper;
    private final ISysDeptService deptService;
    private final ISysUserService userService;
    private final PermissionService permissionService;
    private final AiBusinessDict businessDict;

    @Override
    public List<KnowledgeAclVo> list(Long knowledgeId) {
        require(knowledgeId, AiDomainConstants.PERMISSION_MANAGE);
        return baseMapper.selectVoListByKnowledgeId(knowledgeId);
    }

    @Override
    public List<Long> accessibleKnowledgeIds() {
        if (!LoginHelper.isLogin()) {
            return List.of();
        }
        Long userId = LoginHelper.getUserId();

        // 平台管理员拥有全部知识库访问范围，无需再计算 ACL。
        if (isPlatformAdministrator(userId)) {
            return knowledgeMapper.selectAllIds();
        }

        // 创建人仅用于审计；基础读取范围来自全员可读配置，其他访问必须命中 ACL。
        List<Long> allInternal = knowledgeMapper.selectAllInternalIds(
            AiDomainConstants.KNOWLEDGE_SCOPE_ALL_INTERNAL);

        // 收集当前用户的角色和部门祖先链，用于匹配 ACL 授权主体。
        Set<String> roles = LoginHelper.getLoginUser() == null || LoginHelper.getLoginUser().getRolePermission() == null
            ? Set.of() : LoginHelper.getLoginUser().getRolePermission();
        Set<Long> deptIds = currentUserDeptAndAncestors();
        Date now = new Date();

        // 主体与时间窗条件由数据库过滤，避免加载无关 ACL 记录后再在内存中筛选。
        List<Long> granted = roles.isEmpty() && deptIds.isEmpty() ? List.of()
            : baseMapper.selectEffectiveKnowledgeIds(
                SystemConstants.NORMAL, now, roles, deptIds, LoginHelper.getDeptId(),
                AiDomainConstants.SUBJECT_ROLE, AiDomainConstants.SUBJECT_DEPT,
                AiDomainConstants.FLAG_ENABLED, AiDomainConstants.FLAG_DISABLED);

        // 合并基础访问范围与 ACL 授权范围，并去重后返回。
        return Stream.concat(allInternal.stream(), granted.stream()).distinct().toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean save(KnowledgeAclBo bo) {
        // 新增与编辑都要求当前用户具备目标知识库的管理权限。
        require(bo.getKnowledgeId(), AiDomainConstants.PERMISSION_MANAGE);
        KnowledgeAcl current = bo.getId() == null ? null : baseMapper.selectById(bo.getId());
        if (bo.getId() != null && current == null) {
            throw new ServiceException("ACL 记录不存在");
        }
        if (current != null) require(current.getKnowledgeId(), AiDomainConstants.PERMISSION_MANAGE);

        // 主体类型、权限与包含子部门均按字典标准化，非法值直接拒绝。
        String subjectType = businessDict.requireSpecifiedValue(AiDictTypes.KNOWLEDGE_ACL_SUBJECT_TYPE,
            bo.getSubjectType().toUpperCase(Locale.ROOT));
        String permission = businessDict.requireSpecifiedValue(AiDictTypes.KNOWLEDGE_ACL_PERMISSION,
            bo.getPermission().toUpperCase(Locale.ROOT));
        if (bo.getEffectiveTime() != null && bo.getExpireTime() != null
            && !bo.getExpireTime().after(bo.getEffectiveTime())) throw new ServiceException("失效时间必须晚于生效时间");
        String includeChildren = businessDict.requireValue(AiDictTypes.FLAG_YES_NO, bo.getIncludeChildren());

        // 部门主体必须指向真实存在的部门，避免授权成为无法匹配的无效记录。
        if (AiDomainConstants.SUBJECT_DEPT.equals(subjectType)) {
            Long deptId;
            try {
                deptId = Long.valueOf(bo.getSubjectId());
            } catch (NumberFormatException e) {
                throw new ServiceException("部门授权主体必须是部门ID");
            }
            if (deptService.selectDeptById(deptId) == null) {
                throw new ServiceException("授权部门不存在");
            }
        }

        // 写入或更新授权记录，变更后递增权限修订号通知运行端刷新缓存。
        KnowledgeAcl entity = toEntity(bo);
        entity.setSubjectType(subjectType);
        entity.setPermission(permission);
        entity.setIncludeChildren(includeChildren);
        entity.setStatus(normalizeAclStatus(entity.getStatus()));
        boolean changed = bo.getId() == null ? baseMapper.insert(entity) > 0 : baseMapper.updateById(entity) > 0;
        if (changed) increasePermissionRevision(bo.getKnowledgeId());
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean delete(Long id) {
        // 删除前校验管理权限，成功后递增权限修订号使运行端授权缓存失效。
        KnowledgeAcl acl = baseMapper.selectById(id);
        if (acl == null) {
            return false;
        }
        require(acl.getKnowledgeId(), AiDomainConstants.PERMISSION_MANAGE);
        boolean deleted = baseMapper.deleteById(id) > 0;
        if (deleted) increasePermissionRevision(acl.getKnowledgeId());
        return deleted;
    }

    @Override
    public void require(Long knowledgeId, String permission) {
        if (!canAccess(knowledgeId, permission)) {
            throw new ServiceException("知识库资源不可用或权限不足");
        }
    }

    @Override
    public boolean canAccess(Long knowledgeId, String required) {
        if (!LoginHelper.isLogin()) {
            return false;
        }
        Long userId = LoginHelper.getUserId();
        Set<String> roles = LoginHelper.getLoginUser() == null
            || LoginHelper.getLoginUser().getRolePermission() == null
            ? Set.of() : LoginHelper.getLoginUser().getRolePermission();
        return canAccess(knowledgeId, required, userId, roles, LoginHelper.getDeptId());
    }

    @Override
    public boolean canAccess(Long userId, Long knowledgeId, String required) {
        if (userId == null) {
            return false;
        }
        SysUserVo user = userService.selectUserById(userId);
        if (user == null || !SystemConstants.NORMAL.equals(user.getStatus())) {
            return false;
        }
        Set<String> roles = permissionService.getRolePermission(userId);
        return canAccess(knowledgeId, required, userId, roles, user.getDeptId());
    }

    private boolean canAccess(Long knowledgeId, String required, Long userId,
                              Set<String> roles, Long directDeptId) {
        // 根账号只绕过 ACL；RAG 服务仍会另外复核知识库、索引和文档内容状态。
        KnowledgeInfoVo knowledge = knowledgeMapper.selectVoById(knowledgeId);
        if (knowledge == null) {
            return false;
        }
        if (isPlatformAdministrator(userId)) {
            return true;
        }
        if (!SystemConstants.NORMAL.equals(knowledge.getStatus())) {
            return false;
        }
        if (AiDomainConstants.PERMISSION_READ.equals(required)
            && AiDomainConstants.KNOWLEDGE_SCOPE_ALL_INTERNAL.equals(knowledge.getAccessScope())) {
            return true;
        }

        // 使用当前角色与部门祖先链匹配生效 ACL，避免复用创建快照时的旧身份。
        List<KnowledgeAcl> grants = baseMapper.selectEffectiveByKnowledgeId(
            knowledgeId, SystemConstants.NORMAL, new Date());
        int requiredRank = rank(required);
        Set<Long> deptIds = deptAndAncestors(directDeptId);
        return grants.stream().anyMatch(grant -> matchesSubject(
            grant, roles, deptIds, directDeptId) && rank(grant.getPermission()) >= requiredRank);
    }

    /**
     * 获取权限等级
     *
     * @param permission 权限
     * @return 权限等级
     */
    private int rank(String permission) {
        return switch (permission) {
            case AiDomainConstants.PERMISSION_MANAGE -> 3;
            case AiDomainConstants.PERMISSION_WRITE -> 2;
            default -> 1;
        };
    }

    /**
     * 仅平台根账号可绕过知识库 ACL。
     * 菜单操作权限与知识内容访问权限必须分离，避免普通管理角色获得全部知识库数据。
     */
    private boolean isPlatformAdministrator(Long userId) {
        return LoginHelper.isAdmin(userId);
    }

    /** 将请求对象复制为持久化实体，字典标准化字段由调用方另行设置。 */
    private KnowledgeAcl toEntity(KnowledgeAclBo source) {
        KnowledgeAcl target = new KnowledgeAcl();
        target.setId(source.getId());
        target.setKnowledgeId(source.getKnowledgeId());
        target.setSubjectType(source.getSubjectType());
        target.setSubjectId(source.getSubjectId());
        target.setPermission(source.getPermission());
        target.setIncludeChildren(source.getIncludeChildren());
        target.setStatus(source.getStatus());
        target.setEffectiveTime(source.getEffectiveTime());
        target.setExpireTime(source.getExpireTime());
        return target;
    }

    /** ACL 启停复用框架状态，避免把任意值写入权限判断条件。 */
    private String normalizeAclStatus(String status) {
        String normalizedStatus = StringUtils.isBlank(status) ? SystemConstants.NORMAL : status;
        if (!SystemConstants.NORMAL.equals(normalizedStatus) && !SystemConstants.DISABLE.equals(normalizedStatus)) {
            throw new ServiceException("ACL 状态不正确");
        }
        return normalizedStatus;
    }

    /** 判断授权记录在当前时刻是否处于生效时间窗内。 */
    private boolean isEffective(KnowledgeAcl acl, Date now) {
        return (acl.getEffectiveTime() == null || !acl.getEffectiveTime().after(now))
            && (acl.getExpireTime() == null || acl.getExpireTime().after(now));
    }

    /** 判断授权主体是否命中当前用户的角色或部门，部门可按配置向下包含子级。 */
    private boolean matchesSubject(KnowledgeAcl acl, Set<String> roles, Set<Long> deptIds,
                                   Long directDeptId) {
        if (AiDomainConstants.SUBJECT_ROLE.equals(acl.getSubjectType())) {
            return roles.contains(acl.getSubjectId());
        }
        if (!AiDomainConstants.SUBJECT_DEPT.equals(acl.getSubjectType())) {
            return false;
        }
        try {
            Long grantedDeptId = Long.valueOf(acl.getSubjectId());
            // 包含子部门时匹配部门祖先链，否则仅匹配用户直属部门。
            if (AiDomainConstants.FLAG_ENABLED.equals(acl.getIncludeChildren())) {
                return deptIds.contains(grantedDeptId);
            }
            return directDeptId != null && directDeptId.equals(grantedDeptId);
        } catch (NumberFormatException ignore) {
            return false;
        }
    }

    /** 收集当前用户直属部门及其全部祖先部门，用于部门授权的层级匹配。 */
    private Set<Long> currentUserDeptAndAncestors() {
        return deptAndAncestors(LoginHelper.getDeptId());
    }

    /** 按指定用户直属部门构建祖先链，供内部候选复核读取当前身份。 */
    private Set<Long> deptAndAncestors(Long deptId) {
        Set<Long> deptIds = new HashSet<>();
        if (deptId == null) {
            return deptIds;
        }
        deptIds.add(deptId);
        SysDeptVo dept = deptService.selectDeptById(deptId);
        if (dept == null || dept.getAncestors() == null) {
            return deptIds;
        }
        for (String value : dept.getAncestors().split(",")) {
            try {
                if (StringUtils.isNotBlank(value)) deptIds.add(Long.valueOf(value));
            } catch (NumberFormatException ignore) {
                // 忽略历史脏数据，避免影响当前用户已有的角色权限。
            }
        }
        return deptIds;
    }

    /**
     * 增加权限版本号
     *
     * @param knowledgeId 知识库ID
     */
    private void increasePermissionRevision(Long knowledgeId) {
        KnowledgeInfo current = knowledgeMapper.selectById(knowledgeId);
        if (current == null) {
            return;
        }
        KnowledgeInfo update = new KnowledgeInfo();
        update.setId(knowledgeId);
        int currentRevision = current.getPermissionRevision() == null ? 0 : current.getPermissionRevision();
        update.setPermissionRevision(currentRevision + 1);
        knowledgeMapper.updateById(update);
    }
}
