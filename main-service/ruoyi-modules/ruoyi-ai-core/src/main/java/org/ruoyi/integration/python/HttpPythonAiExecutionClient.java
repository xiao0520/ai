package org.ruoyi.integration.python;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.dto.chat.PythonChatRouteRequest;
import org.ruoyi.domain.dto.chat.PythonChatRouteResponse;
import org.ruoyi.domain.dto.chat.PythonChatStreamEvent;
import org.ruoyi.domain.dto.chat.PythonChatStreamRequest;
import org.ruoyi.domain.dto.contract.ContractReviewExecutionRequest;
import org.ruoyi.domain.dto.knowledge.RagContractJson;
import org.ruoyi.domain.dto.knowledge.rag.RagChatStreamRequest;
import org.ruoyi.integration.security.InternalRequestSignature;
import org.ruoyi.support.ChatExecutionProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Python AI 执行服务的 HTTP 客户端。
 *
 * 所有请求使用同一套内部令牌和 HMAC 签名。模型路由使用普通 JSON 响应，普通聊天和 RAG
 * 聊天使用 SSE；真实供应商密钥只通过凭证别名在 Python 侧解析，不进入请求体。
 */
@Service
@RequiredArgsConstructor
public class HttpPythonAiExecutionClient implements PythonAiExecutionClient {

    private static final String CHAT_ROUTE_PATH = "/internal/ai/v1/chat/route";
    private static final String CHAT_STREAM_PATH = "/internal/ai/v1/chat/stream";
    private static final String RAG_CHAT_STREAM_PATH = "/internal/ai/v1/chat/rag/stream";
    private static final String CONTRACT_REVIEW_STREAM_PATH = "/internal/ai/v1/contract-reviews/stream";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String ACCEPT_EVENT_STREAM = "text/event-stream";

    private final ObjectMapper objectMapper;
    private final RagContractJson ragCodec;
    private final ChatExecutionProperties properties;

    @Override
    public PythonChatRouteResponse routeChat(PythonChatRouteRequest request) {
        byte[] requestBody = serialize(request, "AI路由请求序列化失败");
        HttpRequest httpRequest = buildRequest(
            CHAT_ROUTE_PATH, requestBody, CONTENT_TYPE_JSON, CONTENT_TYPE_JSON);
        try {
            HttpResponse<byte[]> response = httpClient().send(
                httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ServiceException("AI模型路由服务暂不可用，请稍后重试");
            }
            return objectMapper.readValue(response.body(), PythonChatRouteResponse.class);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ServiceException("模型路由已取消");
        } catch (IOException error) {
            throw new ServiceException("AI模型路由服务连接失败，请稍后重试");
        }
    }

    @Override
    public void streamChat(PythonChatStreamRequest request,
                           Consumer<PythonChatStreamEvent> eventConsumer) {
        byte[] requestBody = serialize(request, "AI执行请求序列化失败");
        stream(CHAT_STREAM_PATH, requestBody, eventConsumer);
    }

    @Override
    public void streamRagChat(RagChatStreamRequest request,
                              Consumer<PythonChatStreamEvent> eventConsumer) {
        byte[] requestBody;
        try {
            requestBody = ragCodec.write(request);
        } catch (Exception error) {
            throw new ServiceException("RAG执行请求序列化失败");
        }
        stream(RAG_CHAT_STREAM_PATH, requestBody, eventConsumer);
    }

    @Override
    public void streamContractReview(ContractReviewExecutionRequest request, byte[] fileBytes,
                                     String mediaType,
                                     Consumer<PythonChatStreamEvent> eventConsumer) {
        String boundary = "mjn-ai-" + UUID.randomUUID();
        byte[] body = multipartBody(request, fileBytes, mediaType, boundary);
        HttpRequest httpRequest = buildRequest(CONTRACT_REVIEW_STREAM_PATH, body,
            "multipart/form-data; boundary=" + boundary, ACCEPT_EVENT_STREAM);
        stream(httpRequest, eventConsumer, "合同审查已取消");
    }

    /** 外部 HTTP 调用不持有数据库事务，响应事件由上层编排服务决定何时结算。 */
    private void stream(String path, byte[] requestBody,
                        Consumer<PythonChatStreamEvent> eventConsumer) {
        HttpRequest httpRequest = buildRequest(path, requestBody, CONTENT_TYPE_JSON, ACCEPT_EVENT_STREAM);
        stream(httpRequest, eventConsumer, "对话已取消");
    }

    private void stream(HttpRequest httpRequest,
                        Consumer<PythonChatStreamEvent> eventConsumer,
                        String cancelledMessage) {
        try {
            HttpResponse<InputStream> response = httpClient().send(
                httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() != 200) {
                    throw new ServiceException("AI执行服务暂不可用，请稍后重试");
                }
                consumeEvents(responseBody, eventConsumer);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ServiceException(cancelledMessage);
        } catch (IOException error) {
            throw new ServiceException("AI执行服务连接失败，请稍后重试");
        }
    }

    /** Uvicorn 当前链路使用 HTTP/1.1，避免 h2c 升级影响已签名请求体的传输。 */
    private HttpClient httpClient() {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.getConnectTimeout())
            .build();
    }

    /** 对最终发送的原始字节签名，确保 Java 与 Python 校验的请求体完全一致。 */
    private HttpRequest buildRequest(String path, byte[] body, String contentType, String accept) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature;
        try {
            signature = InternalRequestSignature.sign(
                properties.getInternalSigningKey(), "POST", path, timestamp, nonce, body);
        } catch (IllegalStateException error) {
            throw new ServiceException("AI执行服务签名初始化失败");
        }
        return HttpRequest.newBuilder(URI.create(normalizeBaseUrl() + path))
            .timeout(properties.getRequestTotalTimeout())
            .header("Content-Type", contentType)
            .header("Accept", accept)
            .header("X-AI-Service-Token", properties.getInternalServiceToken())
            .header("X-AI-Signature-Version", InternalRequestSignature.VERSION)
            .header("X-AI-Timestamp", timestamp)
            .header("X-AI-Nonce", nonce)
            .header("X-AI-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    /** 先生成完整 multipart 字节再签名，避免请求发送时边界或换行发生变化。 */
    private byte[] multipartBody(ContractReviewExecutionRequest request, byte[] fileBytes,
                                 String mediaType, String boundary) {
        byte[] metadata;
        try {
            metadata = ragCodec.write(request);
        } catch (Exception error) {
            throw new ServiceException("合同审查请求序列化失败");
        }
        String safePartName = mediaType.equals("application/pdf") ? "source.pdf" : "source.docx";
        ByteArrayOutputStream output = new ByteArrayOutputStream(metadata.length + fileBytes.length + 512);
        write(output, "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"request\"\r\n"
            + "Content-Type: application/json; charset=UTF-8\r\n\r\n");
        output.writeBytes(metadata);
        write(output, "\r\n--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safePartName + "\"\r\n"
            + "Content-Type: " + mediaType + "\r\n\r\n");
        output.writeBytes(fileBytes);
        write(output, "\r\n--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private void write(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 按空行切分标准 SSE 事件，同时保留可能出现的多行 data 内容。 */
    private void consumeEvents(InputStream stream,
                               Consumer<PythonChatStreamEvent> eventConsumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("聊天流已取消");
                }
                if (line.isEmpty()) {
                    dispatchEvent(eventName, data, eventConsumer);
                    eventName = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            dispatchEvent(eventName, data, eventConsumer);
        }
    }

    /** 忽略注释和空事件，只向业务层传递包含合法 JSON 数据的命名事件。 */
    private void dispatchEvent(String eventName, StringBuilder data,
                               Consumer<PythonChatStreamEvent> eventConsumer) {
        if (eventName == null || data.isEmpty()) {
            return;
        }
        try {
            JsonNode eventData = objectMapper.readTree(data.toString());
            eventConsumer.accept(new PythonChatStreamEvent(eventName, eventData));
        } catch (IOException error) {
            throw new ServiceException("AI执行服务返回了无效响应");
        }
    }

    private byte[] serialize(Object request, String errorMessage) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException error) {
            throw new ServiceException(errorMessage);
        }
    }

    private String normalizeBaseUrl() {
        String baseUrl = properties.getPythonBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
