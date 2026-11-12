package org.ruoyi.domain.vo.contract;

import lombok.Data;
import org.ruoyi.domain.dto.contract.ContractReviewResultDto;

import java.util.Date;

/** 用户侧合同审查任务详情。 */
@Data
public class ContractReviewDetailVo {
    private Long id;
    private String fileName;
    private String contractType;
    private String partyPosition;
    private String operation;
    private String status;
    private ContractReviewResultDto result;
    private String errorCode;
    private String errorMessage;
    private Date createTime;
    private Date updateTime;
}
