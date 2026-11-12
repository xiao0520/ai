package org.ruoyi.domain.bo.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/** 创建合同审查任务的用户输入。 */
@Data
public class ContractReviewCreateBo {

    @NotNull(message = "合同文件不能为空")
    private MultipartFile file;

    @NotNull(message = "智能体不能为空")
    private Long agentId;

    @NotBlank(message = "合同类型不能为空")
    @Pattern(regexp = "PURCHASE|SALES|SERVICE|OTHER", message = "不支持的合同类型")
    private String contractType;

    @NotBlank(message = "我方立场不能为空")
    @Pattern(regexp = "PARTY_A|PARTY_B|NEUTRAL", message = "不支持的我方立场")
    private String partyPosition;

    @NotBlank(message = "处理模式不能为空")
    @Pattern(regexp = "REVIEW|EXTRACT", message = "不支持的合同处理模式")
    private String operation = "REVIEW";
}
