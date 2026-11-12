package org.ruoyi.mapper.contract;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.domain.entity.contract.ContractReviewTask;

/** 合同审查任务 Mapper。 */
public interface ContractReviewTaskMapper extends BaseMapper<ContractReviewTask> {
    /** 终态与账户结算共享行锁，防止回调或恢复任务重复扣费。 */
    ContractReviewTask selectByIdForUpdate(@Param("id") Long id);
}
