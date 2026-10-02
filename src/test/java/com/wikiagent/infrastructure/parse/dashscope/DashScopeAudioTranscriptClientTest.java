package com.wikiagent.infrastructure.parse.dashscope;

import com.wikiagent.domain.parse.spi.ParseProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * B-1 Paraformer ASR 客户端契约测试：提交(异步头)→轮询 RUNNING→SUCCEEDED→
 * 拉取 transcription_url 句子时间轴；FAILED 为非重试错误。全程 MockRestServiceServer 桩，不联网。
 */
class DashScopeAudioTranscriptClientTest {

    private DashScopeAudioTranscriptClient newClient(RestClient.Builder builder) {
        return new DashScopeAudioTranscriptClient(
                "test-key", builder.baseUrl("https://dashscope.aliyuncs.com/api/v1").build(),
                "paraformer-v2", 5);
    }

    @Test
    void submitPollAndFetchTranscript() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription"))
                .andExpect(method(POST))
                .andExpect(header("X-DashScope-Async", "enable"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess("""
                        {"request_id":"r1","output":{"task_id":"task-1"}}
                        """, APPLICATION_JSON));
        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/tasks/task-1"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"output":{"task_status":"RUNNING"}}
                        """, APPLICATION_JSON));
        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/tasks/task-1"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"output":{"task_status":"SUCCEEDED",
                          "results":[{"transcription_url":"http://result.example/transcript.json"}]}}
                        """, APPLICATION_JSON));
        server.expect(requestTo("http://result.example/transcript.json"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"transcripts":[{"text":"你好 世界","sentences":[
                          {"begin_time":0,"end_time":500,"text":"你好"},
                          {"begin_time":500,"end_time":1000,"text":"世界"}]}]}
                        """, APPLICATION_JSON));

        DashScopeAudioTranscriptClient client = newClient(builder);
        var result = client.transcribe("http://files.example/a.mp3", 10);

        assertThat(result.fullText()).isEqualTo("你好 世界");
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).beginMs()).isZero();
        assertThat(result.segments().get(1).endMs()).isEqualTo(1000L);
        server.verify();
    }

    @Test
    void missingApiKeyIsNonRetryable() {
        DashScopeAudioTranscriptClient client =
                new DashScopeAudioTranscriptClient("", "https://x", "m");
        Throwable t = catchThrowable(() -> client.transcribe("http://x/a.mp3", 10));
        assertThat(t).isInstanceOf(ParseProviderException.class);
        assertThat(((ParseProviderException) t).retryable()).isFalse();
    }

    @Test
    void missingPublicUrlIsNonRetryable() {
        DashScopeAudioTranscriptClient client =
                new DashScopeAudioTranscriptClient("k", "https://x", "m");
        Throwable t = catchThrowable(() -> client.transcribe("  ", 10));
        assertThat(t).isInstanceOf(ParseProviderException.class)
                .hasMessageContaining("公网");
        assertThat(((ParseProviderException) t).retryable()).isFalse();
    }

    @Test
    void failedTaskIsNonRetryable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription"))
                .andRespond(withSuccess("""
                        {"output":{"task_id":"task-2"}}
                        """, APPLICATION_JSON));
        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/tasks/task-2"))
                .andRespond(withSuccess("""
                        {"output":{"task_status":"FAILED","message":"音频格式不支持"}}
                        """, APPLICATION_JSON));

        DashScopeAudioTranscriptClient client = newClient(builder);
        Throwable t = catchThrowable(() -> client.transcribe("http://x/a.xyz", 10));

        assertThat(t).isInstanceOf(ParseProviderException.class)
                .hasMessageContaining("音频格式不支持");
        assertThat(((ParseProviderException) t).retryable()).isFalse();
        server.verify();
    }
}
