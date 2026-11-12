package org.ruoyi.service.token;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.token.AiRoleModelSaveBo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.domain.vo.token.AiRoleModelPermissionVo;
import org.ruoyi.system.domain.bo.SysRoleBo;

import java.util.List;

/**
 * 角色模型权限管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IAiRoleModelPermissionService {

    /**
     * 分页查询角色模型权限列表
     *
     * @param query     查询条件
     * @param pageQuery 分页参数
     * @return 角色模型权限分页列表
     */
    TableDataInfo<AiRoleModelPermissionVo> queryRolePage(SysRoleBo query, PageQuery pageQuery);

    /**
     * 查询模型选项列表
     *
     * @return 模型选项列表
     */
    List<AiModelOptionVo> queryModelOptions();

    /**
     * 根据角色ID查询角色模型权限
     *
     * @param roleId 角色ID
     * @return 角色模型权限
     */
    AiRoleModelPermissionVo queryRoleModels(Long roleId);

    /**
     * 保存角色模型权限配置
     *
     * @param roleId  角色ID
     * @param command 保存参数
     * @return 是否保存成功
     */
    boolean saveRoleModels(Long roleId, AiRoleModelSaveBo command);

    /**
     * 查询用户生效的模型权限列表
     *
     * @param userId 用户ID
     * @return 生效的模型权限列表
     */
    List<AiModelOptionVo> queryEffectiveModels(Long userId);
}
