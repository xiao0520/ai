package org.ruoyi.service.contract;

import org.ruoyi.domain.bo.contract.ContractReviewCreateBo;
import org.ruoyi.domain.vo.contract.ContractReviewDetailVo;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 合同审查业务入口。 */
public interface IContractReviewService {

    /**
     * 校验用户权限，保存合同文件并执行一次审查。
     * @param command 文件、智能体和审查条件
     * @param userId 当前登录用户
     * @return 包含任务标识、进度和唯一终态的事件流
     */
    SseEmitter stream(ContractReviewCreateBo command, Long userId);

    /**
     * 读取当前用户创建的任务，完成结果以强类型结构返回。
     * @param taskId 任务主键
     * @param userId 当前登录用户
     * @return 任务详情
     */
    ContractReviewDetailVo queryMyTask(Long taskId, Long userId);
}
