package com.wikiagent.infrastructure.parse.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.parse.spi.ParseProviderException;
import com.wikiagent.domain.parse.spi.TranscriptResult;
import com.wikiagent.domain.parse.spi.TranscriptSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DashScope Paraformer 录音文件转写客户端（REST，已按官方 2026 文档契约实现）：
 * <ol>
 *   <li>POST /services/audio/asr/transcription（头 X-DashScope-Async: enable）→ task_id</li>
 *   <li>GET /tasks/{task_id} 轮询至 SUCCEEDED/FAILED → transcription_url</li>
 *   <li>GET transcription_url → transcripts[].sentences[]{begin_time,end_time,text}</li>
 * </ol>
 * 官方 API 仅接受公网可访问文件 URL（HTTP/HTTPS/oss://），本地字节不直接上传；
 * 部署侧需经 OSS 等提供 {@code publicUrl}。真实调用冒烟见手工执行清单（需 API Key 与 OSS）。
 */
public class DashScopeAudioTranscriptClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeAudioTranscriptClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_INTERVAL_MS = 2000;

    private final String apiKey;
    private final String model;
    private final RestClient restClient;
    private final long pollIntervalMs;

    public DashScopeAudioTranscriptClient(String apiKey, String baseUrl, String model) {
        this(apiKey, RestClient.builder().baseUrl(baseUrl).build(), model, POLL_INTERVAL_MS);
    }

    /** 测试构造器：注入 MockRestServiceServer 绑定的 RestClient 与轮询间隔。 */
    DashScopeAudioTranscriptClient(String apiKey, RestClient restClient, String model, long pollIntervalMs) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.restClient = restClient;
        this.pollIntervalMs = pollIntervalMs;
    }

    public boolean available() {
        return !apiKey.isBlank();
    }

    public TranscriptResult transcribe(String publicUrl, int timeoutSec) {
        if (!available()) {
            throw new ParseProviderException("ASR 未配置 API Key", false);
        }
        if (publicUrl == null || publicUrl.isBlank()) {
            throw new ParseProviderException(
                    "Paraformer 仅接受公网可访问文件 URL（配置 wikiagent.parse.asr-file-base-url 或提供 OSS 地址）", false);
        }
        String taskId = submit(publicUrl);
        String transcriptionUrl = pollUntilDone(taskId, timeoutSec);
        return fetchTranscript(transcriptionUrl);
    }

    private String submit(String fileUrl) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode input = MAPPER.createObjectNode();
            input.set("file_urls", MAPPER.createArrayNode().add(fileUrl));
            com.fasterxml.jackson.databind.node.ObjectNode payload = MAPPER.createObjectNode();
            payload.put("model", model);
            payload.set("input", input);
            payload.set("parameters", MAPPER.createObjectNode());
            String body = payload.toString();
            String response = restClient.post()
                    .uri("/services/audio/asr/transcription")
                    .headers(this::authHeaders)
                    .header("X-DashScope-Async", "enable")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode json = MAPPER.readTree(response == null ? "{}" : response);
            String taskId = json.path("output").path("task_id").asText(null);
            if (taskId == null) {
                throw new ParseProviderException("ASR 提交未返回 task_id: " + response, true);
            }
            return taskId;
        } catch (ParseProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseProviderException("ASR 提交失败: " + e.getMessage(), true, e);
        }
    }

    private String pollUntilDone(String taskId, int timeoutSec) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(Math.max(1, timeoutSec)));
        while (Instant.now().isBefore(deadline)) {
            try {
                String response = restClient.get()
                        .uri("/tasks/{taskId}", taskId)
                        .headers(this::authHeaders)
                        .retrieve()
                        .body(String.class);
                JsonNode output = MAPPER.readTree(response == null ? "{}" : response).path("output");
                String status = output.path("task_status").asText("");
                switch (status) {
                    case "SUCCEEDED" -> {
                        String url = output.path("results").path(0)
                                .path("transcription_url").asText(null);
                        if (url == null) {
                            throw new ParseProviderException("ASR 完成但无 transcription_url", false);
                        }
                        return url;
                    }
                    case "FAILED" -> throw new ParseProviderException(
                            "ASR 任务失败: " + output.path("message").asText(""), false);
                    default -> {
                        // PENDING / RUNNING 继续轮询
                        Thread.sleep(pollIntervalMs);
                    }
                }
            } catch (ParseProviderException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ParseProviderException("ASR 轮询被中断", false, e);
            } catch (Exception e) {
                // 单次查询失败按瞬时错误处理（外层执行器也有重试，这里短等继续轮询）
                log.debug("ASR 轮询单次失败 taskId={}: {}", taskId, e.getMessage());
                sleepQuietly();
            }
        }
        throw new ParseProviderException("ASR 轮询超时 taskId=" + taskId, true);
    }

    private TranscriptResult fetchTranscript(String transcriptionUrl) {
        try {
            String response = restClient.get()
                    .uri(URI.create(transcriptionUrl))
                    .retrieve()
                    .body(String.class);
            JsonNode root = MAPPER.readTree(response == null ? "{}" : response);
            JsonNode transcripts = root.path("transcripts");
            if (!transcripts.isArray() || transcripts.isEmpty()) {
                throw new ParseProviderException("ASR 结果缺少 transcripts: " + response, false);
            }
            StringBuilder fullText = new StringBuilder();
            List<TranscriptSegment> segments = new ArrayList<>();
            for (JsonNode tr : transcripts) {
                if (!fullText.isEmpty()) {
                    fullText.append('\n');
                }
                fullText.append(tr.path("text").asText(""));
                for (JsonNode s : tr.path("sentences")) {
                    segments.add(new TranscriptSegment(
                            s.path("begin_time").asLong(0),
                            s.path("end_time").asLong(0),
                            s.path("text").asText("")));
                }
            }
            return new TranscriptResult(fullText.toString().trim(), segments, 1.0);
        } catch (ParseProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseProviderException("ASR 结果解析失败: " + e.getMessage(), true, e);
        }
    }

    private void authHeaders(HttpHeaders headers) {
        headers.setBearerAuth(apiKey);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
