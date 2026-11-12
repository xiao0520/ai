package org.ruoyi.service.contract.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.domain.dto.OssDTO;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.constant.AiSystemConfigKeys;
import org.ruoyi.domain.bo.contract.ContractReviewCreateBo;
import org.ruoyi.domain.dto.chat.PythonChatStreamEvent;
import org.ruoyi.domain.dto.chat.PythonModelProfile;
import org.ruoyi.domain.dto.contract.ContractExecutionPolicy;
import org.ruoyi.domain.dto.contract.ContractReviewExecutionRequest;
import org.ruoyi.domain.dto.contract.ContractReviewResultDto;
import org.ruoyi.domain.dto.contract.ContractReviewUsageDto;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.RagExecutionScope;
import org.ruoyi.domain.dto.knowledge.rag.VisionProfile;
import org.ruoyi.domain.dto.knowledge.rag.ProfileRef;
import org.ruoyi.enums.ModelType;
import org.ruoyi.domain.entity.contract.ContractReviewTask;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.domain.vo.contract.ContractReviewDetailVo;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.integration.python.PythonAiExecutionClient;
import org.ruoyi.mapper.contract.ContractReviewTaskMapper;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.contract.ContractReviewPersistenceService;
import org.ruoyi.service.contract.IContractReviewService;
import org.ruoyi.service.knowledge.IKnowledgeRetrievalScopeService;
import org.ruoyi.service.model.IChatModelService;
import org.ruoyi.support.AiRuntimeConfig;
import org.ruoyi.support.ChatExecutionProperties;
import org.ruoyi.support.RagChatProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 合同审查的 Java 编排：权限、任务状态和持久化留在业务服务。 */
@Service
@Slf4j
public class ContractReviewServiceImpl implements IContractReviewService {

    private static final String CONTRACT_AGENT_CODE = "contract-review";
    private static final Set<String> CONTRACT_TYPES = Set.of("PURCHASE", "SALES", "SERVICE", "OTHER");
    private static final Set<String> PARTY_POSITIONS = Set.of("PARTY_A", "PARTY_B", "NEUTRAL");
    private static final Set<String> FILE_SUFFIXES = Set.of("pdf", "docx");

    private final IAgentService agentService;
    private final IChatModelService modelService;
    private final IKnowledgeRetrievalScopeService retrievalScopeService;
    private final ContractReviewPersistenceService persistenceService;
    private final ContractReviewTaskMapper taskMapper;
    private final PythonAiExecutionClient pythonClient;
    private final RagContractJson ragCodec;
    private final AiRuntimeConfig runtimeConfig;
    private final ChatExecutionProperties executionProperties;
    private final RagChatProperties ragProperties;
    private final AsyncTaskExecutor taskExecutor;

    public ContractReviewServiceImpl(IAgentService agentService, IChatModelService modelService,
                                     IKnowledgeRetrievalScopeService retrievalScopeService,
                                     ContractReviewPersistenceService persistenceService,
                                     ContractReviewTaskMapper taskMapper, PythonAiExecutionClient pythonClient,
                                     RagContractJson ragCodec, AiRuntimeConfig runtimeConfig,
                                     ChatExecutionProperties executionProperties, RagChatProperties ragProperties,
                                     @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.agentService = agentService;
        this.modelService = modelService;
        this.retrievalScopeService = retrievalScopeService;
        this.persistenceService = persistenceService;
        this.taskMapper = taskMapper;
        this.pythonClient = pythonClient;
        this.ragCodec = ragCodec;
        this.runtimeConfig = runtimeConfig;
        this.executionProperties = executionProperties;
        this.ragProperties = ragProperties;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public SseEmitter stream(ContractReviewCreateBo command, Long userId) {
        String operation = normalized(command.getOperation(), Set.of("REVIEW", "EXTRACT"), "不支持的合同处理模式");
        UploadInput upload = requireUpload(command.getFile(), operation);
        String contractType = normalized(command.getContractType(), CONTRACT_TYPES, "不支持的合同类型");
        String partyPosition = normalized(command.getPartyPosition(), PARTY_POSITIONS, "不支持的我方立场");
        AgentSelection selection = requireAgent(command.getAgentId());
        AgentVo agent = selection.agent();
        ModelVo model = requireModel(agent, selection.models());
        if ("REVIEW".equals(operation) && (agent.getKnowledgeIds() == null || agent.getKnowledgeIds().isEmpty())) {
            throw new ServiceException("合同智能体尚未绑定知识库");
        }

        byte[] fileBytes;
        try {
            fileBytes = command.getFile().getBytes();
        } catch (IOException error) {
            throw new ServiceException("读取合同文件失败");
        }
        requireFileSignature(fileBytes, upload.mediaType());
        String businessPath = runtimeConfig.getRequiredString(
            AiSystemConfigKeys.KNOWLEDGE_ATTACHMENT_OSS_CONFIG_KEY);
        String requestId = UUID.randomUUID().toString();
        RagExecutionScope scope = "EXTRACT".equals(operation) ? null
            : retrievalScopeService.create(userId, requestId, agent.getKnowledgeIds(),
                ragProperties.toRetrievalProfile(), ragProperties.getContextBudgetTokens());
        VisionSelection vision = "EXTRACT".equals(operation)
            ? visionProfile(model, selection.models()) : null;
        OssDTO oss = persistenceService.upload(command.getFile(), businessPath);
        ContractReviewTask task = persistenceService.create(oss, agent.getId(), model,
            contractType, partyPosition, requestId, userId, scope, operation,
            vision == null ? null : vision.profile());
        ContractReviewExecutionRequest request = buildRequest(task, userId, requestId, upload,
            contractType, partyPosition, model, scope, agent.getSystemPrompt(), operation, vision);

        SseEmitter emitter = new SseEmitter(executionProperties.getRequestTotalTimeout().toMillis());
        send(emitter, "accepted", new AcceptedEvent(String.valueOf(task.getId()), "RUNNING"));
        try {
            // 浏览器关闭不等同于取消已提交任务；继续收取 Python 终态用量后结算。
            taskExecutor.submit(() -> execute(task.getId(), request, fileBytes, upload, emitter));
            emitter.onTimeout(emitter::complete);
        } catch (RuntimeException error) {
            failQuietly(task.getId(), "INTERNAL", "审查服务繁忙，请稍后重试");
            throw new ServiceException("审查服务繁忙，请稍后重试");
        }
        return emitter;
    }

    @Override
    public ContractReviewDetailVo queryMyTask(Long taskId, Long userId) {
        ContractReviewTask task = taskMapper.selectById(taskId);
        if (task == null || !userId.equals(task.getCreateBy())) {
            throw new ServiceException("合同审查任务不存在或无权访问");
        }
        // 服务重启无法回调旧连接；读取超时任务时按同一终态条件收口。
        if ("RUNNING".equals(task.getStatus()) && task.getCreateTime() != null
            && task.getCreateTime().toInstant().plus(executionProperties.getRequestTotalTimeout())
                .plusSeconds(30)
                .isBefore(java.time.Instant.now())) {
            persistenceService.fail(taskId, "TIMEOUT", failureMessage("TIMEOUT"));
            task = taskMapper.selectById(taskId);
        }
        ContractReviewDetailVo result = new ContractReviewDetailVo();
        result.setId(task.getId());
        result.setFileName(task.getFileName());
        result.setContractType(task.getContractType());
        result.setPartyPosition(task.getPartyPosition());
        result.setOperation(task.getOperation() == null ? "REVIEW" : task.getOperation());
        result.setStatus(task.getStatus());
        result.setErrorCode(task.getErrorCode());
        result.setErrorMessage(task.getErrorMessage());
        result.setCreateTime(task.getCreateTime());
        result.setUpdateTime(task.getUpdateTime());
        if (StringUtils.hasText(task.getResultJson())) {
            try {
                result.setResult(ragCodec.read(
                    task.getResultJson().getBytes(StandardCharsets.UTF_8), ContractReviewResultDto.class));
            } catch (IOException error) {
                throw new ServiceException("合同审查结果读取失败");
            }
        }
        return result;
    }

    private void execute(Long taskId, ContractReviewExecutionRequest request, byte[] fileBytes,
                         UploadInput upload, SseEmitter emitter) {
        AtomicBoolean completed = new AtomicBoolean();
        try {
            pythonClient.streamContractReview(request, fileBytes, upload.mediaType(),
                event -> {
                    if (completed.get()) {
                        throw new ServiceException("AI执行服务在终态之后返回了重复事件");
                    }
                    completed.set(handleEvent(taskId, request.requestId(), event, emitter));
                });
            if (!completed.get()) {
                throw new ServiceException("合同审查连接提前结束，请重新发起审查");
            }
        } catch (Exception error) {
            if (completed.get()) {
                return;
            }
            String code = error instanceof ContractReviewExecutionException failure
                ? failure.errorCode : "INTERNAL";
            String message = userMessage(error);
            failQuietly(taskId, code, message);
            send(emitter, "failed", new ErrorEvent(code, message));
        } finally {
            emitter.complete();
        }
    }

    private boolean handleEvent(Long taskId, String requestId, PythonChatStreamEvent event,
                             SseEmitter emitter) {
        if ("keepalive".equals(event.event())) {
            send(emitter, "keepalive", event.data());
            return false;
        }
        requireRequestId(requestId, event.data());
        if ("usage".equals(event.event())) {
            recordUsage(taskId, event.data());
            return false;
        }
        if ("progress".equals(event.event())) {
            send(emitter, "progress", event.data());
            return false;
        }
        if ("failed".equals(event.event())) {
            recordUsage(taskId, event.data());
            String code = event.data().path("errorCode").asText("INTERNAL");
            throw new ContractReviewExecutionException(code, failureMessage(code));
        }
        if ("completed".equals(event.event())) {
            recordUsage(taskId, event.data());
            ContractReviewResultDto result = readResult(event.data().path("result"));
            if (!String.valueOf(taskId).equals(result.taskId())) {
                throw new ServiceException("AI执行服务响应任务标识不一致");
            }
            persistenceService.complete(taskId, result);
            send(emitter, "completed", new CompletedEvent(String.valueOf(taskId), "COMPLETED"));
            return true;
        }
        throw new ServiceException("AI执行服务返回了未知合同审查事件");
    }

    private ContractReviewExecutionRequest buildRequest(
        ContractReviewTask task, Long userId, String requestId, UploadInput upload,
        String contractType, String partyPosition, ModelVo model, RagExecutionScope scope,
        String systemPrompt, String operation, VisionSelection vision) {
        PythonModelProfile profile = new PythonModelProfile(model.getId(), model.getPublishedRevision(),
            model.getModelName(), model.getProviderCode(), model.getApiHost(), model.getApiMode(),
            model.getCredentialKey(), model.getMaxInputTokens(), model.getMaxOutputTokens());
        ContractExecutionPolicy policy = new ContractExecutionPolicy(model.getMaxInputTokens(),
            model.getMaxOutputTokens(), Math.toIntExact(executionProperties.getRequestTotalTimeout().toSeconds()),
            persistenceService.readBilling(task).reservation().approvedTokens());
        return new ContractReviewExecutionRequest("1.0", requestId, String.valueOf(task.getId()),
            String.valueOf(userId), upload.fileName(), upload.mediaType(), contractType,
            partyPosition, systemPrompt, profile, policy, scope, operation,
            vision == null ? null : vision.profile(),
            vision == null ? Math.min(model.getMaxOutputTokens(), 32768) : vision.maxOutputTokens());
    }

    /** 只使用本智能体与当前用户授权交集里的视觉模型，不跨范围自动选模型。 */
    private VisionSelection visionProfile(ModelVo generation, List<AiModelOptionVo> authorized) {
        List<AiModelOptionVo> candidates = authorized.stream()
            .filter(item -> ModelType.IMAGE.getKey().equals(item.getCategory())).toList();
        ModelVo vision = generation;
        if (candidates.size() == 1) {
            vision = modelService.queryById(candidates.get(0).getModelId());
        } else if (candidates.size() > 1) {
            throw new ServiceException("合同智能体绑定了多个视觉模型，请仅绑定一个OCR模型后重试");
        }
        if (vision == null || !supportsImageInput(vision.getInputModalities())) {
            return null;
        }
        if (vision.getPublishedRevision() == null || vision.getMaxInputTokens() == null
            || vision.getMaxOutputTokens() == null) {
            throw new ServiceException("合同视觉模型尚未完成发布配置");
        }
        VisionProfile profile = new VisionProfile(
            new ProfileRef(vision.getId().toString(), vision.getPublishedRevision()),
            vision.getProviderCode(), vision.getModelName(), vision.getApiHost(), vision.getApiMode(),
            vision.getCredentialKey(), vision.getMaxInputTokens(),
            Math.toIntExact(executionProperties.getRequestTotalTimeout().toSeconds()));
        return new VisionSelection(profile, Math.min(vision.getMaxOutputTokens(), 32768));
    }

    private boolean supportsImageInput(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            return List.of(ragCodec.read(value.getBytes(StandardCharsets.UTF_8), String[].class))
                .contains("IMAGE");
        } catch (IOException | IllegalArgumentException error) {
            throw new ServiceException("合同视觉模型输入模态配置无效");
        }
    }

    private AgentSelection requireAgent(Long agentId) {
        List<AiModelOptionVo> models = agentService.queryAvailableModels(agentId);
        AgentVo agent = agentService.queryById(agentId);
        if (agent == null || !CONTRACT_AGENT_CODE.equals(agent.getAgentCode()) || models.isEmpty()) {
            throw new ServiceException("合同智能体不存在或无权使用");
        }
        String prompt = agent.getSystemPrompt();
        if (prompt != null && prompt.codePointCount(0, prompt.length()) > 20000) {
            throw new ServiceException("合同智能体自定义提示词不能超过20000字，请在后台缩短后重试");
        }
        return new AgentSelection(agent, models);
    }

    private ModelVo requireModel(AgentVo agent, List<AiModelOptionVo> authorized) {
        Long modelId = agent.getModelId() != null ? agent.getModelId() : authorized.get(0).getModelId();
        boolean allowed = authorized.stream().anyMatch(item -> modelId.equals(item.getModelId()));
        if (!allowed) {
            throw new ServiceException("合同智能体默认模型无权使用");
        }
        ModelVo model = modelService.queryById(modelId);
        if (model == null || model.getPublishedRevision() == null || model.getMaxInputTokens() == null
            || model.getMaxOutputTokens() == null) {
            throw new ServiceException("合同智能体模型尚未完成发布配置");
        }
        return model;
    }

    private UploadInput requireUpload(MultipartFile file, String operation) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("合同文件不能为空");
        }
        long maxMb = runtimeConfig.getPositiveLong(
            AiSystemConfigKeys.KNOWLEDGE_UPLOAD_MAX_SIZE_MB, 20L, 1024L);
        if (file.getSize() > maxMb * 1024 * 1024) {
            throw new ServiceException("合同文件不能超过" + maxMb + "MB");
        }
        String originalName = StringUtils.getFilename(file.getOriginalFilename());
        if (!StringUtils.hasText(originalName) || originalName.length() > 255 || !originalName.contains(".")) {
            throw new ServiceException("合同文件名无效");
        }
        String suffix = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        boolean image = "EXTRACT".equals(operation) && Set.of("png", "jpg", "jpeg").contains(suffix);
        if (!FILE_SUFFIXES.contains(suffix) && !image) {
            throw new ServiceException("EXTRACT".equals(operation)
                ? "信息提取支持 PDF、DOCX、PNG 和 JPEG" : "智能审查仅支持 PDF 和 DOCX 合同");
        }
        String mediaType = image ? ("png".equals(suffix) ? "image/png" : "image/jpeg")
            : "pdf".equals(suffix) ? "application/pdf"
            : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return new UploadInput(originalName, mediaType);
    }

    private String normalized(String value, Set<String> allowed, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ServiceException(message);
        }
        return normalized;
    }

    /** 扩展名仅作格式提示，文件头必须符合 PDF 或 Office ZIP 容器。 */
    private void requireFileSignature(byte[] content, String mediaType) {
        boolean valid = "image/png".equals(mediaType)
            ? content.length >= 8 && content[0] == (byte) 0x89 && content[1] == 'P'
                && content[2] == 'N' && content[3] == 'G'
            : "image/jpeg".equals(mediaType)
            ? content.length >= 3 && content[0] == (byte) 0xff && content[1] == (byte) 0xd8 && content[2] == (byte) 0xff
            : "application/pdf".equals(mediaType)
            ? content.length >= 5 && new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")
            : content.length >= 4 && content[0] == 'P' && content[1] == 'K'
                && content[2] == 3 && content[3] == 4;
        if (!valid) {
            throw new ServiceException("合同文件内容与声明格式不符，请检查文件");
        }
    }

    private ContractReviewResultDto readResult(JsonNode value) {
        try {
            return ragCodec.read(value.toString().getBytes(StandardCharsets.UTF_8),
                ContractReviewResultDto.class);
        } catch (Exception error) {
            throw new ServiceException("AI执行服务返回了无效合同审查结果");
        }
    }

    /** 只解析内部计量字段，不将用量明细和授权模型快照转发到浏览器。 */
    private void recordUsage(Long taskId, JsonNode data) {
        var payload = data.deepCopy();
        if (!(payload instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) {
            throw new ServiceException("AI执行服务返回了无效模型用量");
        }
        object.retain("requestId", "executionUsage");
        try {
            ContractReviewUsageDto usage = ragCodec.read(
                object.toString().getBytes(StandardCharsets.UTF_8), ContractReviewUsageDto.class);
            persistenceService.recordUsage(taskId, usage.executionUsage());
        } catch (IOException error) {
            throw new ServiceException("AI执行服务返回了无效模型用量");
        }
    }

    private void requireRequestId(String expected, JsonNode data) {
        if (!expected.equals(data.path("requestId").asText())) {
            throw new ServiceException("AI执行服务响应请求标识不一致");
        }
    }

    private void failQuietly(Long taskId, String code, String message) {
        try {
            persistenceService.fail(taskId, code, message);
        } catch (Exception persistenceError) {
            log.error("合同审查失败状态写入失败：taskId={}, errorCode={}", taskId, code, persistenceError);
        }
    }

    private String userMessage(Exception error) {
        if (error instanceof ContractReviewExecutionException failure) {
            return failure.getMessage();
        }
        if (error instanceof ServiceException && StringUtils.hasText(error.getMessage())) {
            return error.getMessage();
        }
        return "合同审查暂时不可用，请稍后重试";
    }

    private String failureMessage(String code) {
        return switch (code) {
            case "CONTRACT_FILE_UNSUPPORTED" -> "不支持该合同文件格式";
            case "CONTRACT_PARSE_FAILED", "CONTRACT_TEXT_EMPTY" -> "合同内容解析失败，请检查文件";
            case "KNOWLEDGE_ACCESS_DENIED" -> "合同审查知识库权限已失效";
            case "KNOWLEDGE_NOT_READY" -> "合同审查知识库尚未就绪";
            case "MODEL_UNAVAILABLE" -> "合同审查模型当前不可用";
            case "RATE_LIMITED" -> "模型服务繁忙，请稍后重试";
            case "TIMEOUT" -> "合同审查超时，请稍后重试";
            case "BUDGET_EXCEEDED" -> "合同内容超过当前模型处理范围";
            case "CONTRACT_OCR_REQUIRED" -> "扫描件需要视觉模型，请在合同智能体绑定有权限的视觉模型或启用支持图片的默认模型";
            case "CONTRACT_OCR_FAILED" -> "扫描件识别不完整，请上传更清晰的文件或检查视觉模型配置";
            default -> "合同审查暂时不可用，请稍后重试";
        };
    }

    private void send(SseEmitter emitter, String event, Object body) {
        try {
            emitter.send(SseEmitter.event().name(event).data(body));
        } catch (IOException | IllegalStateException ignored) {
            // 浏览器已断开，后台仍收取模型用量并保存终态，用户可重新查询结果。
        }
    }

    private record UploadInput(String fileName, String mediaType) { }
    private record AgentSelection(AgentVo agent, List<AiModelOptionVo> models) { }
    private record VisionSelection(VisionProfile profile, Integer maxOutputTokens) { }
    private record AcceptedEvent(String taskId, String status) { }
    private record CompletedEvent(String taskId, String status) { }
    private record ErrorEvent(String code, String message) { }

    private static final class ContractReviewExecutionException extends RuntimeException {
        private final String errorCode;
        private ContractReviewExecutionException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }
}
