package org.ruoyi.mapper.token;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.bo.token.AiTokenAccountQueryBo;
import org.ruoyi.domain.entity.token.AiUserTokenAccount;

import java.util.Collection;
import java.util.List;

/**
 * 用户 Token 额度账户 Mapper。
 */
public interface AiUserTokenAccountMapper extends BaseMapperPlus<AiUserTokenAccount, AiUserTokenAccount> {

    /** 按可访问用户范围分页查询额度账户。 */
    List<AiUserTokenAccount> selectAccountPage(@Param("page") Page<AiUserTokenAccount> page,
                                               @Param("userIds") Collection<Long> userIds,
                                               @Param("query") AiTokenAccountQueryBo query);

    /** 查询指定用户的手工额度账户。 */
    AiUserTokenAccount selectManualByUserId(@Param("userId") Long userId,
                                            @Param("cycleNo") String cycleNo);

    /** 查询已配置指定账期额度的用户主键。 */
    List<Long> selectConfiguredUserIds(@Param("userIds") Collection<Long> userIds,
                                       @Param("cycleNo") String cycleNo);

    /** 有限额度账户原子预占 Token。 */
    int reserveTokens(@Param("accountId") Long accountId, @Param("tokens") long tokens);

    /** 原子释放预占并结算实际扣减 Token。 */
    int settleTokens(@Param("accountId") Long accountId, @Param("reservedTokens") long reservedTokens,
                     @Param("chargedTokens") long chargedTokens);

    /** 调用失败时释放预占 Token。 */
    int releaseTokens(@Param("accountId") Long accountId, @Param("reservedTokens") long reservedTokens);
}
