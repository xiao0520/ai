package org.ruoyi.constant;

/**
 * AI Token 控制面常量。
 */
public final class AiTokenConstants {

    /**
     * 额度周期类型：手动管理
     */
    public static final String CYCLE_MANUAL = "MANUAL";

    /**
     * 额度账户状态：正常
     */
    public static final String STATUS_NORMAL = "0";

    /**
     * 额度账户状态：冻结
     */
    public static final String STATUS_FROZEN = "1";

    /**
     * 额度账户状态：已关闭
     */
    public static final String STATUS_CLOSED = "2";

    /**
     * 通用布尔值：是
     */
    public static final String YES = "1";

    /**
     * 通用布尔值：否
     */
    public static final String NO = "0";

    /**
     * 告警阈值最小百分比
     */
    public static final int WARNING_THRESHOLD_MIN = 1;

    /**
     * 告警阈值最大百分比
     */
    public static final int WARNING_THRESHOLD_MAX = 100;

    /**
     * 未配置系统参数时使用的默认告警阈值
     */
    public static final int DEFAULT_WARNING_THRESHOLD = 80;

    /**
     * 额度操作类型：首次分配额度
     */
    public static final String INITIAL_GRANT = "INITIAL_GRANT";

    /**
     * 额度操作类型：追加额度
     */
    public static final String APPEND_GRANT = "APPEND_GRANT";

    /**
     * 额度操作类型：设置剩余额度
     */
    public static final String SET_REMAINING = "SET_REMAINING";

    /**
     * 额度操作类型：更新告警阈值
     */
    public static final String UPDATE_WARNING_THRESHOLD = "UPDATE_WARNING_THRESHOLD";

    /**
     * 额度操作类型：启用无限额度
     */
    public static final String ENABLE_UNLIMITED = "ENABLE_UNLIMITED";

    /**
     * 额度操作类型：禁用无限额度
     */
    public static final String DISABLE_UNLIMITED = "DISABLE_UNLIMITED";

    /**
     * 额度操作类型：冻结账户
     */
    public static final String FREEZE = "FREEZE";

    /**
     * 额度操作类型：解冻账户
     */
    public static final String UNFREEZE = "UNFREEZE";

    /**
     * Token 使用记录状态：成功
     */
    public static final String USAGE_SUCCESS = "SUCCESS";

    /**
     * Token 使用记录状态：失败
     */
    public static final String USAGE_FAILED = "FAILED";

    private AiTokenConstants() {
    }
}
