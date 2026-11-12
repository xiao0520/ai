package org.ruoyi.controller.contract;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.contract.ContractReviewCreateBo;
import org.ruoyi.domain.vo.contract.ContractReviewDetailVo;
import org.ruoyi.service.contract.IContractReviewService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 用户侧合同审查入口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/contract/reviews")
public class ContractReviewController {

    private final IContractReviewService contractReviewService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter create(@Validated @ModelAttribute ContractReviewCreateBo command) {
        return contractReviewService.stream(command, LoginHelper.getUserId());
    }

    @GetMapping("/{taskId}")
    public R<ContractReviewDetailVo> detail(@PathVariable Long taskId) {
        return R.ok(contractReviewService.queryMyTask(taskId, LoginHelper.getUserId()));
    }
}
