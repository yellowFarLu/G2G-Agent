package com.wikiagent.application.rule;

import com.wikiagent.domain.rule.RuleStatus;
import com.wikiagent.dto.ConflictException;
import com.wikiagent.entity.rule.RuleSetEntity;
import com.wikiagent.repo.rule.RuleSetRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #6 规则发布并发回归：H2 文件库（MODE=MySQL，两线程各自拿连接）+
 * PESSIMISTIC_WRITE（SELECT ... FOR UPDATE）行锁串行化并发发布，
 * 断言任何调度顺序下同 code 最终恰一条 ACTIVE。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class RuleSetPublishConcurrencyIT {

    /** 每类独立 H2 文件（nanoTime 唯一后缀），LOCK_TIMEOUT 放宽到 10s 等行锁。 */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-rule-publish-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired
    private RuleSetService ruleSetService;

    @Autowired
    private RuleSetRepo repo;

    private static final String DSL =
            "{\"steps\":[{\"op\":\"const\",\"params\":{\"value\":1},\"to\":\"x\"}],\"outputs\":[\"x\"]}";

    @Test
    void concurrentPublishDifferentVersionsEndsWithSingleActive() throws Exception {
        String code = "pub-conc-v-" + System.nanoTime();
        ruleSetService.createDraft(code, DSL, "并发v1", "test");
        ruleSetService.createDraft(code, DSL, "并发v2", "test");

        runConcurrent(
                () -> ruleSetService.publish(code, 1),
                () -> ruleSetService.publish(code, 2));

        List<RuleSetEntity> rows = repo.findByCodeOrderByVersionDesc(code);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(r -> RuleStatus.ACTIVE.name().equals(r.getStatus())).count())
                .as("两个版本并发发布后必须恰一条 ACTIVE（后者归档前者）")
                .isEqualTo(1);
        assertThat(rows).anyMatch(r -> RuleStatus.ARCHIVED.name().equals(r.getStatus()));
    }

    @Test
    void concurrentPublishSameVersionEndsWithSingleActive() throws Exception {
        String code = "pub-conc-same-" + System.nanoTime();
        ruleSetService.createDraft(code, DSL, "并发同版", "test");

        runConcurrent(
                () -> ruleSetService.publish(code, 1),
                () -> ruleSetService.publish(code, 1));

        List<RuleSetEntity> rows = repo.findByCodeOrderByVersionDesc(code);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(RuleStatus.ACTIVE.name());
    }

    /**
     * 两线程经同一栅栏同时发起；允许其中一方抛 ConflictException（重复发布语义），
     * 其余异常视为缺陷逸出。
     */
    private static void runConcurrent(Runnable a, Runnable b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> guard(a, start, unexpected));
            Future<?> f2 = pool.submit(() -> guard(b, start, unexpected));
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(unexpected.get()).isNull();
    }

    private static void guard(Runnable action, CountDownLatch start,
                              AtomicReference<Throwable> unexpected) {
        try {
            start.await();
            action.run();
        } catch (ConflictException expected) {
            // 重复发布已 ACTIVE 版本 → 409 是当前契约允许的正确结果
        } catch (Throwable t) {
            unexpected.compareAndSet(null, t);
        }
    }
}
