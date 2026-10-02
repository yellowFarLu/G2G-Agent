package com.wikiagent.infrastructure.llm;

import com.wikiagent.domain.llm.spi.RerankRequest;
import com.wikiagent.domain.llm.spi.RerankResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

/**
 * E3 DashScope text-rerank REST 契约测试：成功响应按 index 对齐分数、
 * 鉴权头/请求体发送、HTTP 500 包装统一异常、无 key 不可用。全程桩，不联网。
 */
class DashScopeRerankRestTest {

    private static final String URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private DashScopeRerankProvider newProvider(RestClient client, boolean enabled) {
        return new DashScopeRerankProvider("test-key", enabled, client, "gte-rerank");
    }

    @Test
    void 成功响应按index对齐回请求顺序() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess("""
                        {"output":{"results":[
                          {"index":1,"relevance_score":0.9},
                          {"index":0,"relevance_score":0.1}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        DashScopeRerankProvider p = newProvider(builder.baseUrl(URL).build(), true);
        RerankResult r = p.rerank(new RerankRequest("查询", List.of("甲", "乙"), 2));

        // 分数与请求 documents 下标对齐（甲=0.1，乙=0.9），顺序由调用方按分数排
        assertThat(r.scores()).containsExactly(0.1, 0.9);
        assertThat(p.available()).isTrue();
        server.verify();
    }

    @Test
    void http500抛统一异常() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andExpect(method(POST))
                .andRespond(withServerError());

        DashScopeRerankProvider p = newProvider(builder.baseUrl(URL).build(), true);
        Throwable t = catchThrowable(() -> p.rerank(new RerankRequest("q", List.of("a", "b"), 2)));
        assertThat(t).isInstanceOf(RerankProviderException.class);
        server.verify();
    }

    @Test
    void 响应结构缺失抛统一异常() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andExpect(method(POST))
                .andRespond(withSuccess("{\"output\":{}}", MediaType.APPLICATION_JSON));

        DashScopeRerankProvider p = newProvider(builder.baseUrl(URL).build(), true);
        Throwable t = catchThrowable(() -> p.rerank(new RerankRequest("q", List.of("a"), 1)));
        assertThat(t).isInstanceOf(RerankProviderException.class);
        server.verify();
    }

    @Test
    void 无key或未启用时不可用() {
        assertThat(new DashScopeRerankProvider("", true,
                RestClient.builder().baseUrl(URL).build(), "m").available()).isFalse();
        assertThat(new DashScopeRerankProvider("k", false,
                RestClient.builder().baseUrl(URL).build(), "m").available()).isFalse();
    }
}
