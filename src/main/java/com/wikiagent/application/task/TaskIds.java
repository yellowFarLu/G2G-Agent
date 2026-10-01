package com.wikiagent.application.task;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 任务 ID 生成（规格 2.1）：tsk_{yyyyMMddHHmmss}_{6 位 base36 随机}，
 * 进程内已见集合去重兜底，保证批量生成不重复。
 */
public final class TaskIds {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int RANDOM_LEN = 6;
    private static final int MAX_RETRY = 10;

    /** 进程内去重集合（重启即清；超容量时整体重建防泄漏）。 */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final int SEEN_CAPACITY = 100_000;

    private TaskIds() {
    }

    public static String next() {
        for (int i = 0; i < MAX_RETRY; i++) {
            String id = "tsk_" + LocalDateTime.now().format(FMT) + "_" + randomSuffix();
            if (SEEN.add(id)) {
                if (SEEN.size() > SEEN_CAPACITY) {
                    SEEN.clear();
                }
                return id;
            }
        }
        // 理论不可达：2.18e9 空间连续 10 次碰撞概率可忽略
        throw new IllegalStateException("TaskIds 生成连续碰撞，请检查时钟回拨");
    }

    private static String randomSuffix() {
        StringBuilder sb = new StringBuilder(RANDOM_LEN);
        for (int i = 0; i < RANDOM_LEN; i++) {
            sb.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
