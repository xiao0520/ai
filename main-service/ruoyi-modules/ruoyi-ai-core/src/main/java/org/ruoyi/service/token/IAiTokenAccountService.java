package org.ruoyi.service.token;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.token.AiTokenAccountCommandBo;
import org.ruoyi.domain.bo.token.AiTokenAccountQueryBo;
import org.ruoyi.domain.bo.token.AiTokenQuotaLogQueryBo;
import org.ruoyi.domain.vo.token.AiTokenQuotaLogVo;
import org.ruoyi.domain.vo.token.AiTokenUserOptionVo;
import org.ruoyi.domain.vo.token.AiUserTokenAccountVo;

/**
 * 用户Token额度账户管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IAiTokenAccountService {

    /**
     * 分页查询用户Token额度账户列表
     *
     * @param query     查询条件
     * @param pageQuery 分页参数
     * @return 用户Token额度账户分页列表
     */
    TableDataInfo<AiUserTokenAccountVo> queryPage(AiTokenAccountQueryBo query, PageQuery pageQuery);

    /**
     * 分页查询用户选项列表
     *
     * @param query     查询条件
     * @param pageQuery 分页参数
     * @return 用户选项分页列表
     */
    TableDataInfo<AiTokenUserOptionVo> queryUserOptions(AiTokenAccountQueryBo query, PageQuery pageQuery);

    /**
     * 根据用户ID查询Token额度账户
     *
     * @param userId 用户ID
     * @return 用户Token额度账户
     */
    AiUserTokenAccountVo queryByUserId(Long userId);

    /**
     * 初始授予Token额度
     *
     * @param userId  用户ID
     * @param command 授予参数
     * @return 是否授予成功
     */
    boolean initialGrant(Long userId, AiTokenAccountCommandBo command);

    /**
     * 追加授予Token额度
     *
     * @param userId  用户ID
     * @param command 授予参数
     * @return 是否追加成功
     */
    boolean appendGrant(Long userId, AiTokenAccountCommandBo command);

    /**
     * 设置Token剩余额度
     *
     * @param userId  用户ID
     * @param command 设置参数
     * @return 是否设置成功
     */
    boolean setRemaining(Long userId, AiTokenAccountCommandBo command);

    /**
     * 设置Token预警阈值
     *
     * @param userId  用户ID
     * @param command 设置参数
     * @return 是否设置成功
     */
    boolean setWarningThreshold(Long userId, AiTokenAccountCommandBo command);

    /**
     * 设置Token额度无限
     *
     * @param userId  用户ID
     * @param command 设置参数
     * @return 是否设置成功
     */
    boolean setUnlimited(Long userId, AiTokenAccountCommandBo command);

    /**
     * 修改Token账户状态
     *
     * @param userId  用户ID
     * @param command 设置参数
     * @return 是否修改成功
     */
    boolean changeStatus(Long userId, AiTokenAccountCommandBo command);

    /**
     * 分页查询Token额度日志列表
     *
     * @param query     查询条件
     * @param pageQuery 分页参数
     * @return Token额度日志分页列表
     */
    TableDataInfo<AiTokenQuotaLogVo> queryLogPage(AiTokenQuotaLogQueryBo query, PageQuery pageQuery);
}
