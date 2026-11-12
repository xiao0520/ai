package org.ruoyi.system.service;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.system.domain.bo.SysClientBo;
import org.ruoyi.system.domain.vo.SysClientVo;

import java.util.List;

/**
 * 系统授权 服务层
 *
 * @author xiaoqh
 */
public interface ISysClientService {

    /**
     * 查询系统授权
     */
    SysClientVo queryById(Long id);

    /**
     * 查询系统授权列表
     */
    TableDataInfo<SysClientVo> queryPageList(SysClientBo bo, PageQuery pageQuery);

    /**
     * 查询系统授权列表
     */
    List<SysClientVo> queryList(SysClientBo bo);

    /**
     * 根据客户端id查询
     */
    SysClientVo queryByClientId(String clientId);

    /**
     * 新增系统授权
     */
    Boolean insertByBo(SysClientBo bo);

    /**
     * 修改系统授权
     */
    Boolean updateByBo(SysClientBo bo);

    /**
     * 校验并批量删除系统授权信息
     */
    Boolean deleteWithValidByIds(List<Long> ids, Boolean isValid);

}
