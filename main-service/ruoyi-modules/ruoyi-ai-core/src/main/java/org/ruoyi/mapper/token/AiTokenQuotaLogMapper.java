package org.ruoyi.mapper.token;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.bo.token.AiTokenQuotaLogQueryBo;
import org.ruoyi.domain.entity.token.AiTokenQuotaLog;

import java.util.Collection;
import java.util.List;

/**
 * Token 额度调整流水 Mapper。
 */
public interface AiTokenQuotaLogMapper extends BaseMapperPlus<AiTokenQuotaLog, AiTokenQuotaLog> {

    /** 按可访问用户范围分页查询额度调整流水。 */
    List<AiTokenQuotaLog> selectLogPage(@Param("page") Page<AiTokenQuotaLog> page,
                                        @Param("userIds") Collection<Long> userIds,
                                        @Param("query") AiTokenQuotaLogQueryBo query);
}
