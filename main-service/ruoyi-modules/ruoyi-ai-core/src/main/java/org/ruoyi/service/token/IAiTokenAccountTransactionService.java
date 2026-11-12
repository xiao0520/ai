package org.ruoyi.service.token;

import org.ruoyi.domain.bo.token.AiTokenAccountCommandBo;

/**
 * 用户Token额度账户事务管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IAiTokenAccountTransactionService {

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
}
