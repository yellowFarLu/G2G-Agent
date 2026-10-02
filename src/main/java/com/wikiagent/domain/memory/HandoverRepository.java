package com.wikiagent.domain.memory;

/**
 * v1-v2 §4 交接清单端口（DDD 端口接口）。
 * <p>
 * 默认 MySQL 四表适配器（handover_checklist/node/abandoned_path/data_ref，规格 §2.5）；
 * file 适配器（todo.json）保留供回退，handover-adapter=file 启用。
 * 字段索引由 Java 确定性解析（DataRefValueResolver），Python 脚本已废弃。
 * <p>
 * 交接清单必须包含四段（用户硬性要求）：
 * <ol>
 *   <li>用户原始请求 (originalRequest)</li>
 *   <li>已执行节点描述 (executedNodes)</li>
 *   <li>放弃的路径 (abandonedPaths)</li>
 *   <li>数据引用索引 (dataReferences)</li>
 * </ol>
 */
public interface HandoverRepository {

    /** 初始化交接清单。 */
    void init(String userId, String sessionId, String originalRequest);

    /** 添加已执行节点描述。 */
    void addExecutedNode(String userId, String sessionId, String nodeId, String description);

    /** 添加放弃路径。 */
    void addAbandonedPath(String userId, String sessionId, String nodeId, String reason);

    /** 添加数据引用。 */
    void addDataReference(String userId, String sessionId, String key, String value);

    /** 读取完整交接清单 JSON。 */
    String load(String userId, String sessionId);

    /** 刷新字段索引（已废弃 Python 路径：MySQL 模式即时解析，File 模式空转）。 */
    void refreshFieldIndex(String userId, String sessionId);
}
