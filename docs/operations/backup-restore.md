# 备份与恢复手册（AC-J4b）

> 适用组件：MySQL（业务权威库 + Flyway 迁移历史）、Milvus（向量索引）、
> Redis（会话/限流/协调，无权威状态）、应用版本本身。
> 原则：**MySQL 是唯一权威数据源**；Milvus/Redis 均可从 MySQL 与对象存储重建。

## 1. MySQL 备份与恢复

### 1.1 逻辑备份（每日全量 + 保留 14 天）

```bash
# 全库逻辑备份（在跳板机/运维 Pod 内执行；账号只授予 SELECT, LOCK TABLES, SHOW VIEW, TRIGGER）
BACKUP_DIR=/data/backup/mysql
mkdir -p "$BACKUP_DIR"
TS=$(date +%F_%H%M)
mysqldump \
  --host="$MYSQL_HOST" --port=3306 \
  --user="$MYSQL_BACKUP_USER" --password="$MYSQL_BACKUP_PASSWORD" \
  --single-transaction --quick --routines --triggers --events \
  --set-gtid-purged=OFF \
  wiki_agent | gzip > "$BACKUP_DIR/wiki_agent_$TS.sql.gz"

# 校验备份可读（至少解析前若干行 + 尾部完整性标记）
gzip -t "$BACKUP_DIR/wiki_agent_$TS.sql.gz"
zcat "$BACKUP_DIR/wiki_agent_$TS.sql.gz" | tail -1 | grep -q 'Dump completed' \
  || { echo "备份不完整"; exit 1; }
```

定时任务（专用备份账号 crontab，每日 02:30）：

```cron
30 2 * * * /opt/ops/wiki-agent-mysql-backup.sh >> /var/log/wiki-agent-backup.log 2>&1
```

清理过期备份：

```bash
find /data/backup/mysql -name 'wiki_agent_*.sql.gz' -mtime +14 -delete
```

> 生产建议额外开启云厂商自动快照（PITR/binlog），逻辑备份用于跨版本/跨账号恢复。

### 1.2 恢复

```bash
# 1) 新建空库（不要直接覆盖在用库）
mysql -h "$MYSQL_HOST" -u root -p \
  -e "CREATE DATABASE wiki_agent_restore DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2) 导入
zcat /data/backup/mysql/wiki_agent_2026-10-08_0230.sql.gz \
  | mysql -h "$MYSQL_HOST" -u root -p wiki_agent_restore

# 3) 行数抽样核对
mysql -h "$MYSQL_HOST" -u root -p wiki_agent_restore \
  -e "SELECT COUNT(*) FROM knowledge_metadata; SELECT COUNT(*) FROM audit_log;"

# 4) 验证应用可连：把应用的 MYSQL_URL 临时指向恢复库（staging 演练），
#    ddl-auto=validate 会在启动时校验 schema，不一致直接拒绝启动
```

### 1.3 Flyway 迁移失败处理（validate-on-migrate=true）

失败迁移后 `flyway_schema_history` 中会留下 success=0 的记录，应用无法启动。
**先在测试环境演练**，顺序如下：

1. 看应用启动日志确认失败的版本与原因（语法错误/超时/锁等待）。
2. **首选：forward fix**——编写新版本迁移 `V<下一个版本>__repair.sql` 修正数据/对象，
   不要修改已经发布过的迁移文件内容（checksum 会变）。
3. 必须清理失败记录时（确认该版本从未在任何环境成功过）：

   ```bash
   # 方式 A：Flyway repair（删除失败行并更新 checksum；需要 flyway 命令行，版本与 pom 中一致）
   flyway -url="jdbc:mysql://$MYSQL_HOST/wiki_agent" \
          -user="$MYSQL_USER" -password="$MYSQL_PASSWORD" repair

   # 方式 B：手工删除失败行（repair 不可用时）
   mysql -h "$MYSQL_HOST" -u root -p wiki_agent -e \
     "DELETE FROM flyway_schema_history WHERE success = 0;"
   ```

   修复后重新部署应用，Flyway 会重新执行待应用版本。
4. 若是误发布的破坏性迁移：从最近备份恢复到新库（1.2），核对后切换数据源，
   **禁止**在生产库做无备份的 DROP/TRUNCATE。

## 2. Milvus 备份与恢复

### 2.1 备份工具与限制

- Milvus 自身不保证向量数据是权威来源；推荐使用官方 **milvus-backup**
  对 collection 做快照（gRPC `CreateBackup`），备份产物落对象存储（S3/MinIO）。

```bash
# 示例（参数按实际 milvus-proxy/MinIO 地址填写）
milvus-backup create -n wiki_agent_$(date +%F)
milvus-backup list
milvus-backup get -n wiki_agent_2026-10-08
```

- 限制：备份一致性依赖 Milvus 版本；2.3 前的历史版本仅保证元数据一致，
  正在写入的增量可能缺失——备份窗口请避开大批量灌库任务；
  standalone 模式下的本地磁盘备份不能直接用于分布式恢复。

### 2.2 恢复 / 重建 collection

优先用备份恢复：

```bash
milvus-backup restore -n wiki_agent_2026-10-08 -c wiki_chunks
```

备份不可用时按权威数据全量重建（常规演练路径，无需停机窗口外的额外工具）：

1. 在管理端/运维任务中触发"全量重建索引"（或对 `knowledge_metadata` 全量文档
   重新跑 chunk → embedding → upsert）：
   - 数据源：MySQL 文档元数据/版本状态 + 对象存储中的原始文件；
   - 重建期间检索走本地关键词降级（`RetrievalService` 已有 Milvus 异常降级路径）。
2. 重建后抽样比对每个 docId 的 chunk 数与 MySQL `kb_child_chunk` 一致。
3. 切换前执行一次检索冒烟（admin/business 身份各一组）。

## 3. Redis：无备份，按可丢失数据处理

Redis 中只有会话（spring session）、限流桶、分布式协调/缓存类数据，
**不持有权威状态，不做备份**：

- 故障恢复：重建实例即可，应用自动重连；
- 影响面：用户需要重新登录；限流桶冷启动从零计数（短窗口内可能放过略超阈值的请求）；
  答案/embedding 缓存冷启动后命中率逐步恢复；
- 若启用了 AOF/RDB（容器默认不承诺），只用于减少重建抖动，**不得**作为恢复依据；
- 演练要求：每季度在 staging 做一次 Redis 实例删除重建演练。

## 4. 应用版本回滚

### 4.1 镜像 tag 回退

```bash
# compose 部署：固定镜像 tag（禁止用 latest），回退即改 tag 后重新拉起
# docker-compose.yml 中 image: registry.example.com/wiki-agent:<git-sha-or-vx.y.z>
docker compose pull
docker compose up -d app
```

K8s：`kubectl set image deployment/wiki-agent app=registry.example.com/wiki-agent:<上一个tag>`
或直接回滚 Deployment 版本历史。

### 4.2 与数据库迁移的兼容约定（重要）

Flyway 迁移**只增不破坏**，保证"新库 + 旧镜像"可启动窗口：

- 允许：新增表、新增列（带默认值/可空）、新增索引（CONCURRENTLY 思路，避免长锁）；
- 禁止：删列/改列语义/重命名（需要跨两个版本：先双写 → 切读 → 再清理）；
- 回滚应用镜像时**不回滚 Flyway**；旧镜像必须能容忍新版本留下的额外列
  （ddl-auto=validate 只校验实体涉及的表结构，多余列不阻断）；
- 若某次迁移确实破坏了向后兼容，必须走 §1.2 的整库恢复 + 镜像回退，不允许热修数据字典。

### 4.3 回滚检查清单

1. 确认上一个稳定镜像 tag 与对应配置（环境变量变更是否兼容旧镜像）；
2. 检查自上个版本以来的 Flyway 版本，确认无非兼容变更；
3. 回滚后验证：`/actuator/health`（含 dashScopeKey）、一次带身份头的对话冒烟、
   一次检索权限冒烟（admin/business）；
4. 回滚操作与原因记录变更单，保留故障现场日志与审计（audit_log 不随回滚删除）。
