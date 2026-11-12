package org.ruoyi.service.token;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.token.AiTokenUsageQueryBo;
import org.ruoyi.domain.vo.token.AiTokenStatisticsVo;
import org.ruoyi.domain.vo.token.AiTokenUsageVo;

/**
 * Token用量查询管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IAiTokenUsageService {

    /**
     * 分页查询Token用量列表
     *
     * @param query     查询条件
     * @param pageQuery 分页参数
     * @return Token用量分页列表
     */
    TableDataInfo<AiTokenUsageVo> queryPage(AiTokenUsageQueryBo query, PageQuery pageQuery);

    /**
     * 统计Token用量
     *
     * @param query 查询条件
     * @return Token用量统计
     */
    AiTokenStatisticsVo statistics(AiTokenUsageQueryBo query);
}
