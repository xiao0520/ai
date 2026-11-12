package org.ruoyi.service.token.impl;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.constant.AiTokenConstants;
import org.ruoyi.domain.bo.token.AiRoleModelSaveBo;
import org.ruoyi.domain.entity.token.AiRoleModelPermission;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.domain.vo.token.AiRoleModelPermissionVo;
import org.ruoyi.mapper.token.AiRoleModelPermissionMapper;
import org.ruoyi.service.token.IAiTokenUserScopeService;
import org.ruoyi.service.token.IAiRoleModelPermissionService;
import org.ruoyi.service.model.IChatModelService;
import org.ruoyi.system.domain.bo.SysRoleBo;
import org.ruoyi.system.domain.vo.SysRoleVo;
import org.ruoyi.system.service.ISysRoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 角色模型权限管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class AiRoleModelPermissionServiceImpl implements IAiRoleModelPermissionService {

    private final ISysRoleService roleService;
    private final AiRoleModelPermissionMapper permissionMapper;
    private final IChatModelService chatModelService;
    private final IAiTokenUserScopeService userScopeService;

    @Override
    public TableDataInfo<AiRoleModelPermissionVo> queryRolePage(SysRoleBo query, PageQuery pageQuery) {
        // 角色数据范围由系统角色服务处理，避免 Token 模块绕过角色权限。
        TableDataInfo<SysRoleVo> rolePage = roleService.selectPageRoleList(query, pageQuery);
        if (CollUtil.isEmpty(rolePage.getRows())) {
            return new TableDataInfo<>(Collections.emptyList(), rolePage.getTotal());
        }
        List<Long> roleIds = rolePage.getRows().stream().map(SysRoleVo::getRoleId).toList();
        // 一次加载当前页的授权记录和模型信息，避免按角色产生 N+1 查询。
        Map<Long, List<AiRoleModelPermission>> permissions = permissionMapper.selectByRoleIds(roleIds)
            .stream().collect(Collectors.groupingBy(AiRoleModelPermission::getRoleId,
                LinkedHashMap::new, Collectors.toList()));
        Set<Long> modelIds = permissions.values().stream().flatMap(List::stream)
            .map(AiRoleModelPermission::getModelId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, AiModelOptionVo> modelMap = mapModels(modelIds);
        List<AiRoleModelPermissionVo> rows = rolePage.getRows().stream()
            .map(role -> toRoleVo(role, permissions.getOrDefault(role.getRoleId(), Collections.emptyList()), modelMap))
            .toList();
        return new TableDataInfo<>(rows, rolePage.getTotal());
    }

    @Override
    public List<AiModelOptionVo> queryModelOptions() {
        return chatModelService.queryEnabledOptions();
    }

    @Override
    public AiRoleModelPermissionVo queryRoleModels(Long roleId) {
        // 先校验角色数据范围和存在性，再读取其授权模型。
        roleService.checkRoleDataScope(roleId);
        SysRoleVo role = roleService.selectRoleById(roleId);
        if (role == null) {
            throw new ServiceException("角色不存在或已删除");
        }
        List<AiRoleModelPermission> permissions = permissionMapper.selectByRoleIds(List.of(roleId));
        Set<Long> modelIds = permissions.stream().map(AiRoleModelPermission::getModelId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return toRoleVo(role, permissions, mapModels(modelIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveRoleModels(Long roleId, AiRoleModelSaveBo command) {
        // 角色范围、可分配性和存在性均由系统角色服务统一校验。
        roleService.checkRoleDataScope(roleId);
        roleService.checkRoleAllowed(new SysRoleBo(roleId));
        SysRoleVo role = roleService.selectRoleById(roleId);
        if (role == null) {
            throw new ServiceException("角色不存在或已删除");
        }

        // 规范化模型集合并批量校验所有模型均处于可用状态。
        validateTime(command);
        List<Long> modelIds = command.getModelIds() == null ? Collections.emptyList()
            : command.getModelIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        validateModels(modelIds);

        // 当前角色的模型授权采用整体替换，删除和批量写入处于同一事务。
        permissionMapper.deleteByRoleId(roleId);
        if (CollUtil.isEmpty(modelIds)) {
            return true;
        }
        // 复用统一的授权有效期与备注，为每个模型创建独立关系记录。
        List<AiRoleModelPermission> rows = new ArrayList<>(modelIds.size());
        for (Long modelId : modelIds) {
            AiRoleModelPermission permission = new AiRoleModelPermission();
            permission.setRoleId(roleId);
            permission.setModelId(modelId);
            permission.setStatus(AiTokenConstants.STATUS_NORMAL);
            permission.setEffectiveTime(command.getEffectiveTime());
            permission.setExpireTime(command.getExpireTime());
            permission.setRemark(command.getRemark());
            rows.add(permission);
        }
        return permissionMapper.insertBatch(rows);
    }

    @Override
    public List<AiModelOptionVo> queryEffectiveModels(Long userId) {
        // 先限制目标用户的数据范围；管理员不受角色模型授权限制。
        userScopeService.requireUser(userId);
        List<SysRoleVo> roles = roleService.selectRolesByUserId(userId);
        if (roles.stream().anyMatch(SysRoleVo::isAdmin)) {
            return queryModelOptions();
        }
        List<Long> roleIds = roles.stream()
            .filter(role -> AiTokenConstants.STATUS_NORMAL.equals(role.getStatus()))
            .map(SysRoleVo::getRoleId).toList();
        if (CollUtil.isEmpty(roleIds)) {
            return Collections.emptyList();
        }

        // 由 Mapper 在数据库中按有效期筛选授权模型，避免在内存加载全部授权记录。
        Date now = new Date();
        Set<Long> modelIds = new LinkedHashSet<>(permissionMapper.selectEffectiveModelIds(roleIds,
            AiTokenConstants.STATUS_NORMAL, now));

        // 通过模型领域服务过滤掉已停用或已删除的模型，并保持授权顺序。
        Map<Long, AiModelOptionVo> modelMap = mapModels(modelIds);
        return modelIds.stream().map(modelMap::get).filter(java.util.Objects::nonNull).toList();
    }

    /** 校验授权时间窗，失效时间必须晚于生效时间。 */
    private void validateTime(AiRoleModelSaveBo command) {
        if (command.getEffectiveTime() != null && command.getExpireTime() != null
            && !command.getExpireTime().after(command.getEffectiveTime())) {
            throw new ServiceException("失效时间必须晚于生效时间");
        }
    }

    /** 校验所选模型均存在且处于启用状态。 */
    private void validateModels(List<Long> modelIds) {
        if (CollUtil.isEmpty(modelIds)) {
            return;
        }
        chatModelService.requireEnabledModels(modelIds);
    }

    /** 批量加载启用模型选项并按模型标识建立索引。 */
    private Map<Long, AiModelOptionVo> mapModels(Set<Long> modelIds) {
        if (CollUtil.isEmpty(modelIds)) {
            return Collections.emptyMap();
        }
        return chatModelService.queryEnabledOptionsByIds(modelIds)
            .stream()
            .collect(Collectors.toMap(AiModelOptionVo::getModelId, Function.identity(),
                (left, right) -> left, LinkedHashMap::new));
    }

    /** 组装角色授权视图，时间窗与备注取自首条授权记录。 */
    private AiRoleModelPermissionVo toRoleVo(SysRoleVo role, List<AiRoleModelPermission> permissions,
                                               Map<Long, AiModelOptionVo> modelMap) {
        AiRoleModelPermissionVo vo = new AiRoleModelPermissionVo();
        vo.setRoleId(role.getRoleId());
        vo.setRoleName(role.getRoleName());
        vo.setRoleKey(role.getRoleKey());
        vo.setRoleSort(role.getRoleSort());
        vo.setRoleStatus(role.getStatus());
        vo.setModelIds(permissions.stream().map(AiRoleModelPermission::getModelId).distinct().toList());
        vo.setModels(vo.getModelIds().stream().map(modelMap::get).filter(java.util.Objects::nonNull).toList());
        if (CollUtil.isNotEmpty(permissions)) {
            AiRoleModelPermission first = permissions.get(0);
            vo.setEffectiveTime(first.getEffectiveTime());
            vo.setExpireTime(first.getExpireTime());
            vo.setRemark(first.getRemark());
        }
        return vo;
    }

}
