package com.wikiagent.application.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.eval.data.JudgeVerdict;
import com.wikiagent.domain.eval.golden.AnomalyGoldenCase;
import com.wikiagent.domain.eval.golden.ParseGoldenCase;
import com.wikiagent.domain.eval.golden.RetrieveGoldenCase;
import com.wikiagent.domain.eval.golden.RuleGoldenCase;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线评测固件加载器：从 classpath {@code eval/} 读取四类 golden、种子造数、
 * judge 录制评判与规则 DSL。纯只读，无网络、无 Spring 容器依赖（可直接 new）。
 * <p>
 * JSON 中允许出现 {@code _comment}/{@code _note*} 等说明字段（关闭未知属性失败）。
 */
public class EvalGoldenLoader {

    public static final String DIR = "eval/";
    public static final String PARSE_GOLDEN = DIR + "parse-golden.json";
    public static final String RETRIEVE_GOLDEN = DIR + "retrieve-golden.json";
    public static final String RULE_GOLDEN = DIR + "rule-golden.json";
    public static final String ANOMALY_GOLDEN = DIR + "anomaly-golden.json";
    public static final String RETRIEVE_SEED = DIR + "seed/retrieve-seed.json";

    /** judge 录制固件清单（固定 4 条；如新增固件在此登记）。 */
    private static final List<String> JUDGE_FIXTURES = List.of(
            "judge-fact-error.json",
            "judge-structure-error.json",
            "judge-both-errors.json",
            "judge-clean.json");

    private final ObjectMapper mapper;

    public EvalGoldenLoader() {
        this(new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    public EvalGoldenLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<ParseGoldenCase> loadParseGolden() {
        return readList(PARSE_GOLDEN, new TypeReference<>() {
        });
    }

    public List<RetrieveGoldenCase> loadRetrieveGolden() {
        return readList(RETRIEVE_GOLDEN, new TypeReference<>() {
        });
    }

    public List<RuleGoldenCase> loadRuleGolden() {
        return readList(RULE_GOLDEN, new TypeReference<>() {
        });
    }

    public List<AnomalyGoldenCase> loadAnomalyGolden() {
        return readList(ANOMALY_GOLDEN, new TypeReference<>() {
        });
    }

    /** 种子造数原始 JSON（实体装配/时间戳替换由 EvalSeeder 完成）。 */
    public JsonNode loadSeed() {
        return readJson(RETRIEVE_SEED);
    }

    /** 读取规则 DSL 程序文本（路径相对 eval/ 目录，见 rule-golden.json 的 dslFixture）。 */
    public String readRuleDsl(String dslFixture) {
        return readString(DIR + dslFixture);
    }

    /** 加载 4 条录制 judge 评判为领域投影（评测时刻统一打时间戳）。 */
    public List<JudgeVerdict> loadJudgeVerdicts(Instant at) {
        List<JudgeVerdict> verdicts = new ArrayList<>();
        for (String name : JUDGE_FIXTURES) {
            JsonNode node = readJson(DIR + "stubs/judge/" + name);
            JsonNode verdict = node.path("verdict");
            verdicts.add(new JudgeVerdict(
                    node.path("id").asText(name),
                    verdict.path("factError").asBoolean(false),
                    verdict.path("structureError").asBoolean(false),
                    at));
        }
        return List.copyOf(verdicts);
    }

    /** 通用：把 DSL JSON 文本解析为 JsonNode。 */
    public JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("解析 JSON 失败: " + e.getMessage(), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    private <T> List<T> readList(String path, TypeReference<List<T>> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, type);
        } catch (Exception e) {
            throw new IllegalStateException("加载评测固件失败: " + path + " (" + e.getMessage() + ")", e);
        }
    }

    private JsonNode readJson(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("加载评测固件失败: " + path + " (" + e.getMessage() + ")", e);
        }
    }

    private String readString(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载评测固件失败: " + path + " (" + e.getMessage() + ")", e);
        }
    }
}
