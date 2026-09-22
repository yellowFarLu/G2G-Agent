package com.wikiagent.infrastructure.memory.file;

import com.wikiagent.infrastructure.lock.HandoffLock;
import com.wikiagent.infrastructure.lock.LockBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * v6 §22.5 交接清单 todo.json 的并发控制（§22.3 #3 POSIX flock + §22.3 #1 Redis 分布式锁双层）。
 * <p>
 * <b>双层锁设计</b>（§22.4 推荐）：
 * <ol>
 *   <li>外层：Redis 分布式锁（{@link HandoffLock}）—— 跨节点互斥</li>
 *   <li>内层：本地 {@link FileChannel#tryLock()}（POSIX advisory lock）—— 单机内多进程/线程互斥</li>
 * </ol>
 * <p>
 * <b>生产建议</b>（§22.6 / §22.7 诚实声明）：把 todo.json 迁到 MySQL {@code todo_node} 表
 * （{@code task_id} 主键 + {@code status} 列 + JPA {@code @Version} CAS），免跨节点文件锁。
 * 当前实现是开发期兼容性方案。
 * <p>
 * 与 §22.5 骨架一致；TodoNode 类型留作 v3-v5 实施时定义（当前用泛化 {@code List<T>}）。
 */
@Component
public class TodoStore {

    private static final Logger log = LoggerFactory.getLogger(TodoStore.class);

    private final HandoffLock distributedLock;
    private final Duration lockTtl;

    public TodoStore(HandoffLock distributedLock,
                     @Value("${wikiagent.concurrency.lock.default-ttl-seconds:5}") long lockTtlSeconds) {
        this.distributedLock = distributedLock;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
    }

    /**
     * 在双层锁保护下原子更新 todo.json。
     * <p>
     * 行为契约：
     * <ol>
     *   <li>获取 Redis 分布式锁（{@code key=todo:{path}}）失败 → 抛 {@link LockBusyException}</li>
     *   <li>打开文件 FileChannel + tryLock（POSIX advisory lock）失败 → 抛 {@link LockBusyException}</li>
     *   <li>读取现有 List + 调用 mut 修改 + 写回</li>
     *   <li>finally 释放两层锁</li>
     * </ol>
     * <p>
     * <b>注意</b>：本方法是 §22 并发控制框架的骨架，{@code readAll} / {@code writeAll}
     * 的具体序列化逻辑留作 v3-v5 实施时按 §4 交接清单格式实现。
     * 当前实现仅保证编译通过 + 锁机制可用，不实际读写 todo.json 内容
     * （mut 接收 List 但本方法返回 null 表示未实际持久化）。
     *
     * @param path todo.json 路径
     * @param mut  修改函数（接收当前 List，原地修改）
     * @throws LockBusyException 锁繁忙
     * @throws IOException       文件 IO 错误
     */
    public <T> void update(Path path, Consumer<List<T>> mut) throws IOException {
        distributedLock.withLock("todo:" + path, lockTtl, () -> {
            try (FileChannel ch = FileChannel.open(path,
                    StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE)) {
                FileLock ignored = ch.tryLock();
                if (ignored == null) {
                    throw new LockBusyException(path.toString());
                }
                try {
                    // §22.5 骨架占位：实际 readAll/writeAll 留作 v3-v5 实施时按 §4 格式实现
                    // 当前仅演示双层锁框架，不实际持久化内容
                    List<T> nodes = List.of();
                    mut.accept(nodes);
                    log.debug("todo.json 已在锁保护下更新（占位实现，未实际持久化）: {}", path);
                } finally {
                    ignored.release();
                }
            } catch (LockBusyException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("todo.json 更新失败: " + path, e);
            }
            return null;
        });
    }
}
