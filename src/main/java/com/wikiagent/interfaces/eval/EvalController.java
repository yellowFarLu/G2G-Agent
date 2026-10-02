package com.wikiagent.interfaces.eval;

import com.wikiagent.application.eval.EvalReportStore;
import com.wikiagent.application.eval.EvalRunner;
import com.wikiagent.domain.eval.EvalOptions;
import com.wikiagent.domain.eval.EvalReport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.Optional;

/**
 * 离线评测查询/触发 API（子项目 H，AC-H4）：
 * <ul>
 *   <li>GET  /api/eval/report/latest       读取最新 EvalReport.json（无文件 404）</li>
 *   <li>GET  /api/eval/report/{filename}   白名单 + canonical 双校验读取（穿越 400）</li>
 *   <li>POST /api/eval/run                 同步触发离线评测（未启用 wikiagent.eval.enabled → 503）</li>
 * </ul>
 * 查询接口无条件可用；运行器默认不装配，对既有部署安全。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalReportStore store;
    private final ObjectProvider<EvalRunner> runnerProvider;

    public EvalController(EvalReportStore store, ObjectProvider<EvalRunner> runnerProvider) {
        this.store = store;
        this.runnerProvider = runnerProvider;
    }

    @GetMapping("/report/latest")
    public ResponseEntity<String> latest() {
        try {
            Optional<String> json = store.latest();
            return json.map(EvalController::jsonOk)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            return internalError(e);
        }
    }

    @GetMapping("/report/{filename}")
    public ResponseEntity<String> byName(@PathVariable String filename) {
        try {
            return jsonOk(store.read(filename));
        } catch (IllegalArgumentException e) {
            // 文件名白名单/路径穿越 → 400
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (NoSuchFileException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return internalError(e);
        }
    }

    /**
     * 同步触发一次离线评测并返回报告。
     *
     * @param outputDir 可选报告目录覆盖（-Dwikiagent.eval.output-dir 同源）
     */
    @PostMapping("/run")
    public ResponseEntity<Object> run(@RequestParam(required = false) String outputDir) {
        EvalRunner runner = runnerProvider.getIfAvailable();
        if (runner == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "离线评测未启用（wikiagent.eval.enabled=true 才允许触发）"));
        }
        EvalOptions options = new EvalOptions(outputDir, null, null, false);
        EvalReport report = runner.runAll(options);
        return ResponseEntity.ok(report);
    }

    private static ResponseEntity<String> jsonOk(String json) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    private static ResponseEntity<String> internalError(Exception e) {
        return ResponseEntity.internalServerError()
                .body("{\"error\":\"" + escape(e.getMessage()) + "\"}");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
