package org.ruoyi.domain.entity.contract;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/** 合同审查任务。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_contract_review_task")
public class ContractReviewTask extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;
    private Long fileOssId;
    private String fileName;
    private Long agentId;
    private Long modelId;
    private String contractType;
    private String partyPosition;
    private String operation;
    private String status;
    private String resultJson;
    private String billingJson;
    private String errorCode;
    private String errorMessage;
}
