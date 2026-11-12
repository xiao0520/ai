package org.ruoyi.domain.entity.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * 模型 Profile 的不可变发布快照。
 *
 * Python 运行面后续只应按模型 ID 与修订号读取这一类快照，而不是依赖可变的模型主表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_profile_revision")
public class ModelProfileRevision extends BaseEntity {

    @TableId("id")
    private Long id;

    /** 模型 Profile ID。 */
    private Long modelId;

    /** 同一 Profile 内单调递增的发布修订号。 */
    private Integer revision;

    /** 不含明文密钥的配置快照。 */
    private String profileSnapshot;

    /** 发布状态，当前仅写入 PUBLISHED。 */
    private String status;
}
