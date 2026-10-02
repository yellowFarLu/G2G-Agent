package com.wikiagent.application.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.eval.EvalReport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 评测报告文件存储（target/eval-report）：
 * 写出 {@code EvalReport.json}（latest 固定名）+ 带时间戳的历史快照；
 * 读取接口带白名单 + canonical path 双校验防路径穿越（H4）。
 * <p>
 * 无条件装配（只读查询 API 不依赖 wikiagent.eval.enabled）。
 */
@Component
public class EvalReportStore {

    /** 固定最新报告名。 */
    public static final String LATEST_FILENAME = "EvalReport.json";

    /** 仅允许安全文件名字符（字母数字/点/下划线/横线），必须 .json 结尾。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.json");

    private final Path outputDir;
    private final ObjectMapper mapper;
    private final DateTimeFormatter stampFormatter;

    @org.springframework.beans.factory.annotation.Autowired
    public EvalReportStore(EvalProperties properties, ObjectMapper mapper) {
        this(properties.getOutputDir(), mapper);
    }

    public EvalReportStore(String outputDir, ObjectMapper mapper) {
        this.outputDir = Paths.get(outputDir).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.stampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneId.systemDefault());
    }

    /** 写 latest + 时间戳快照，返回 latest 路径。 */
    public Path write(EvalReport report) {
        try {
            Files.createDirectories(outputDir);
            byte[] json = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(report);
            Path latest = outputDir.resolve(LATEST_FILENAME);
            Files.write(latest, json);
            String stamp = stampFormatter.format(java.time.Instant.now());
            Files.write(outputDir.resolve("EvalReport-" + stamp + ".json"), json);
            return latest;
        } catch (IOException e) {
            throw new IllegalStateException("写评测报告失败: " + e.getMessage(), e);
        }
    }

    /** 最新报告文本（按文件修改时间取最新；无报告空 Optional）。 */
    public Optional<String> latest() throws IOException {
        Path latest = outputDir.resolve(LATEST_FILENAME);
        if (Files.isRegularFile(latest)) {
            return Optional.of(Files.readString(latest, StandardCharsets.UTF_8));
        }
        try (Stream<Path> files = Files.list(outputDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("EvalReport[-.].*\\.json"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .map(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        } catch (java.nio.file.NoSuchFileException e) {
            return Optional.empty();
        }
    }

    /**
     * 按文件名读取报告。
     *
     * @throws IllegalArgumentException 文件名非法/路径穿越（调用方映射 400）
     * @throws java.nio.file.NoSuchFileException 文件不存在（调用方映射 404）
     */
    public String read(String filename) throws IOException {
        if (filename == null || !SAFE_NAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("非法报告文件名: " + filename);
        }
        Path target = outputDir.resolve(filename).normalize();
        // 双保险：canonical 路径必须仍在报告目录内
        if (!target.toAbsolutePath().startsWith(outputDir)) {
            throw new IllegalArgumentException("报告路径越界: " + filename);
        }
        if (!Files.isRegularFile(target)) {
            throw new java.nio.file.NoSuchFileException(filename);
        }
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    /** 列出现有报告文件名（最新在前，管理/排查用）。 */
    public List<String> list() throws IOException {
        if (!Files.isDirectory(outputDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(outputDir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> SAFE_NAME.matcher(n).matches())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }
}
