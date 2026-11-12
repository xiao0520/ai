package org.ruoyi.service.contract;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.dto.OssDTO;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.constant.AiTokenConstants;
import org.ruoyi.domain.dto.contract.ContractBillingSnapshot;
import org.ruoyi.domain.dto.contract.ContractReviewResultDto;
import org.ruoyi.domain.dto.contract.ContractTokenReservation;
import org.ruoyi.domain.dto.knowledge.rag.ExecutionUsageItem;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.knowledge.rag.TokenMeasurement;
import org.ruoyi.domain.dto.knowledge.rag.VisionProfile;
import org.ruoyi.domain.entity.contract.ContractReviewTask;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.mapper.contract.ContractReviewTaskMapper;
import org.ruoyi.service.token.ContractReviewTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;
import java.util.List;

/** 合同审查任务的短事务写入，不包裹 Python 长连接。 */
@Service
@RequiredArgsConstructor
public class ContractReviewPersistenceService {

    private static final String STATUS_RUNNING = "RUNNING";
    private final ContractReviewTaskMapper taskMapper;
    private final OssService ossService;
    private final ObjectMapper objectMapper;
    private final ContractReviewTokenService tokenService;

    /** OSS 上传不占用额度事务或账户行锁。 */
    public OssDTO upload(MultipartFile file, String businessPath) {
        return ossService.uploadBusinessFile(file, businessPath);
    }

    /** 任务与预占额度同事务创建，事务失败时不产生账户冻结。 */
    @Transactional(rollbackFor = Exception.class)
    public ContractReviewTask create(OssDTO oss, Long agentId,
                                     ModelVo model, String contractType, String partyPosition,
                                     String requestId, Long userId, RagExecutionScope scope,
                                     String operation, VisionProfile vision) {
        // 预占与任务创建同事务，任何创建失败都会回滚额度。
        ContractTokenReservation reservation = tokenService.reserve(requestId, userId, agentId,
            model, scope, operation, vision);
        ContractReviewTask task = new ContractReviewTask();
        task.setId(IdWorker.getId());
        task.setFileOssId(oss.getOssId());
        task.setFileName(oss.getOriginalName());
        task.setAgentId(agentId);
        task.setModelId(model.getId());
        task.setContractType(contractType);
        task.setPartyPosition(partyPosition);
        task.setOperation(operation);
        task.setStatus(STATUS_RUNNING);
        task.setBillingJson(writeJson(new ContractBillingSnapshot(reservation, List.of())));
        if (taskMapper.insert(task) != 1) {
            throw new ServiceException("合同审查任务创建失败");
        }
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(Long taskId, ContractReviewResultDto result) {
        ContractReviewTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null || !STATUS_RUNNING.equals(task.getStatus())) {
            throw new ServiceException("合同审查任务已经结束");
        }
        ContractBillingSnapshot billing = readBilling(task);
        int expectedCalls = "EXTRACT".equals(task.getOperation()) ? 1 : 2;
        if (billing.executionUsage().stream()
            .filter(item -> item.stage() == ExecutionUsageItem.Stage.V_CHAT).count() != expectedCalls) {
            throw new ServiceException("合同审查缺少完整的模型计量，无法保存为成功结果");
        }
        if ("EXTRACT".equals(task.getOperation()) && !result.findings().isEmpty()) {
            throw new ServiceException("信息提取任务不能包含风险审查结果");
        }
        tokenService.settle(billing.reservation(), billing.executionUsage(), AiTokenConstants.USAGE_SUCCESS, null);
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException error) {
            throw new ServiceException("合同审查结果序列化失败");
        }
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<ContractReviewTask>()
            .eq(ContractReviewTask::getId, taskId)
            .eq(ContractReviewTask::getStatus, STATUS_RUNNING)
            .set(ContractReviewTask::getStatus, "COMPLETED")
            .set(ContractReviewTask::getResultJson, resultJson)
            .set(ContractReviewTask::getErrorCode, null)
            .set(ContractReviewTask::getErrorMessage, null)
            .set(ContractReviewTask::getUpdateTime, new Date()));
        if (updated != 1) {
            throw new ServiceException("合同审查任务状态已变化");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(Long taskId, String errorCode, String errorMessage) {
        ContractReviewTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null || !STATUS_RUNNING.equals(task.getStatus())) {
            return;
        }
        // 已发生调用照常结算，未消耗的预占随失败终态一起释放。
        if (task.getBillingJson() != null) {
            ContractBillingSnapshot billing = readBilling(task);
            tokenService.settle(billing.reservation(), billing.executionUsage(), AiTokenConstants.USAGE_FAILED, errorCode);
        }
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<ContractReviewTask>()
            .eq(ContractReviewTask::getId, taskId)
            .eq(ContractReviewTask::getStatus, STATUS_RUNNING)
            .set(ContractReviewTask::getStatus, "FAILED")
            .set(ContractReviewTask::getErrorCode, limit(errorCode, 64))
            .set(ContractReviewTask::getErrorMessage, limit(errorMessage, 500))
            .set(ContractReviewTask::getUpdateTime, new Date()));
        if (updated != 1) {
            throw new ServiceException("合同审查失败状态保存失败");
        }
    }

    /** 阶段间保存累计快照；服务中断后仍可按已保存用量收口。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordUsage(Long taskId, List<ExecutionUsageItem> items) {
        ContractReviewTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null || !STATUS_RUNNING.equals(task.getStatus())) {
            return;
        }
        ContractBillingSnapshot billing = readBilling(task);
        tokenService.validate(billing.reservation(), items);
        for (ExecutionUsageItem previous : billing.executionUsage()) {
            if (previous.measurement() instanceof TokenMeasurement tokens
                && tokens.quality() == TokenMeasurement.Quality.V_ACTUAL && !items.contains(previous)) {
                throw new ServiceException("合同模型实际用量不能被后续快照覆盖或删除");
            }
        }
        String snapshot = writeJson(new ContractBillingSnapshot(billing.reservation(), List.copyOf(items)));
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<ContractReviewTask>()
            .eq(ContractReviewTask::getId, taskId)
            .eq(ContractReviewTask::getStatus, STATUS_RUNNING)
            .set(ContractReviewTask::getBillingJson, snapshot)
            .set(ContractReviewTask::getUpdateTime, new Date()));
        if (updated != 1) {
            throw new ServiceException("合同模型用量快照保存失败");
        }
    }

    /** 读取服务端保存的授权与计量，不接受客户端提供的预占信息。 */
    public ContractBillingSnapshot readBilling(ContractReviewTask task) {
        try {
            return objectMapper.readValue(task.getBillingJson(), ContractBillingSnapshot.class);
        } catch (Exception error) {
            throw new ServiceException("合同审查计费快照读取失败");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new ServiceException("合同审查计费快照保存失败");
        }
    }

    private String limit(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }
}
