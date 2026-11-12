package org.ruoyi.mapper.token;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.bo.token.AiTokenUsageQueryBo;
import org.ruoyi.domain.entity.token.AiTokenUsage;
import org.ruoyi.domain.vo.token.AiTokenStatItemVo;
import org.ruoyi.domain.vo.token.AiTokenStatisticsVo;

/**
 * Token 使用记录 Mapper。
 */
public interface AiTokenUsageMapper extends BaseMapperPlus<AiTokenUsage, AiTokenUsage> {

    /** 按模型调用幂等ID查询已记账明细。 */
    AiTokenUsage selectByCallId(@Param("callId") String callId);

    /** 按可访问用户范围分页查询模型调用用量明细。 */
    List<AiTokenUsage> selectUsagePage(@Param("page") Page<AiTokenUsage> page,
                                       @Param("query") AiTokenUsageQueryBo query,
                                       @Param("userIds") Collection<Long> userIds);

    /**
     * 查询模型调用用量汇总。
     *
     * @param query 查询条件
     * @param userIds 当前操作人可查看的用户ID集合
     * @return 汇总数据
     */
    AiTokenStatisticsVo selectStatistics(@Param("query") AiTokenUsageQueryBo query,
                                         @Param("userIds") Collection<Long> userIds);

    /**
     * 查询模型调用用量排行。
     *
     * @param query 查询条件
     * @param userIds 当前操作人可查看的用户ID集合
     * @param limit 最大返回数量
     * @return 模型排行数据
     */
    List<AiTokenStatItemVo> selectModelRanking(@Param("query") AiTokenUsageQueryBo query,
                                               @Param("userIds") Collection<Long> userIds,
                                               @Param("limit") int limit);

    /**
     * 查询用户模型调用用量排行。
     *
     * @param query 查询条件
     * @param userIds 当前操作人可查看的用户ID集合
     * @param limit 最大返回数量
     * @return 用户排行数据
     */
    List<AiTokenStatItemVo> selectUserRanking(@Param("query") AiTokenUsageQueryBo query,
                                              @Param("userIds") Collection<Long> userIds,
                                              @Param("limit") int limit);

    /**
     * 查询每日模型调用用量趋势。
     *
     * @param query 查询条件
     * @param userIds 当前操作人可查看的用户ID集合
     * @return 每日趋势数据
     */
    List<AiTokenStatItemVo> selectDailyTrend(@Param("query") AiTokenUsageQueryBo query,
                                             @Param("userIds") Collection<Long> userIds);
}
