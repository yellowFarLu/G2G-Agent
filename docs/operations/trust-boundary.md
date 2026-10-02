# 信任边界与安全网关运维手册（AC-J2/J3/J4）

> 适用范围：`feature/task-framework` 起的 dev/staging/prod 三环境。
> 相关代码：`infrastructure/security/TrustedHeaderFilter.java`、
> `infrastructure/security/PiiMinimizer.java`、
> `infrastructure/security/PiiMinimizingChatModelDecorator.java`、
> `infrastructure/security/StreamingOutputGuardrailSender.java`。

## 1. 威胁模型

应用部署在 API 网关（Nginx/Kong/APISIX 等）之后，登录态由网关校验，
网关注入两个身份头供应用做数据级权限：

| 请求头 | 含义 | 消费方 |
| --- | --- | --- |
| `X-User-Id` | 已认证用户 ID | 会话、审计、按用户隔离 |
| `X-Business-Identity` | 业务身份（admin / business / product / role:admin 等） | `RetrievalIdentityFilter` → `RetrievalSecurityContext` 检索过滤、`CacheAdminController` 管理端点鉴权 |

**核心风险**：如果客户端可以绕过网关直连应用 Pod/VM（内网漫游、SSRF、端口暴露、
端口转发），就能任意伪造上述头——例如普通员工发
`X-Business-Identity: admin` 清空全部缓存、或把自己声明成 admin 检索受限行业方案。

此外两类数据外泄风险：

- **提示注入**：用户输入（含检索回来的文档内容、工具描述）中的注入指令诱导模型
  泄露系统提示词或越权执行；
- **外发 PII**：手机号/身份证/银行卡/邮箱随 Prompt 明文发送给第三方模型服务。

## 2. 共享密钥信任边界（AC-J4）

### 2.1 机制

`TrustedHeaderFilter`（OncePerRequestFilter）仅在
`wikiagent.security.trust-boundary.enabled=true` 时注册：

1. 请求**不带任何受保护身份头** → 视为匿名请求放行（健康检查、公开接口不受影响）；
2. 请求携带 `X-User-Id`/`X-Business-Identity` 中任意一个 → 必须同时携带
   `X-Internal-Secret`，且值与服务端密钥恒定时间比较一致，否则 **401**，
   身份不会进入 `RetrievalIdentityFilter` 等任何下游组件；
3. **fail-fast**：启用过滤器但密钥为空时，应用启动直接失败
   （IllegalStateException，提示配置 `WIKIAGENT_INTERNAL_SECRET`）。
   密钥没有任何硬编码默认值。

dev profile 默认 `enabled=false`（本地直连调试可自由注入身份头）；
staging/prod profile 默认 `true`，只能用 `WIKIAGENT_TRUST_BOUNDARY_ENABLED=false`
显式紧急关闭。

### 2.2 部署步骤

1. 在密钥管理系统（K8s Secret / Vault / SSM）生成高强度随机密钥，例如：

   ```bash
   openssl rand -base64 32
   ```

2. 同时分发给**网关**与**应用**（应用侧环境变量）：

   ```bash
   # 应用工作负载（docker compose 示例，值从 Secret 注入，勿写死在 compose 文件）
   WIKIAGENT_TRUST_BOUNDARY_ENABLED=true
   WIKIAGENT_INTERNAL_SECRET=<与网关一致的随机密钥>
   ```

3. 网关对"认证通过"的请求覆写身份头并附加密钥（以 Nginx 为例）：

   ```nginx
   location / {
       proxy_set_header X-Internal-Secret $wikiagent_internal_secret;  # 来自受保护的变量/密钥文件
       proxy_set_header X-User-Id         $jwt_claim_sub;               # 由认证结果派生
       proxy_set_header X-Business-Identity $jwt_claim_business_identity;
       # 关键：先无条件清除客户端自带的伪造头，再写入网关注入的值
       proxy_set_header X-User-Id "";
       proxy_set_header X-Business-Identity "";
   }
   ```

   > 必须先清除客户端入站同名头，避免被追加/透传。
   > 网络层仍应配合安全组/NetworkPolicy，只允许网关 Pod 访问应用 Service；
   > 共享密钥是纵深防线，不替代网络隔离。

4. 验证：

   ```bash
   # 直连应用、伪造身份 → 期望 401
   curl -i http://<app>/api/chat -H 'X-Business-Identity: admin'
   # 经网关正常访问 → 期望 200
   curl -i https://<gateway>/api/chat -H 'Authorization: Bearer <token>'
   # 无身份头匿名访问健康检查 → 期望 200
   curl -i http://<app>/actuator/health
   ```

### 2.3 密钥轮换

1. 网关侧先灰度支持双密钥（旧/新都接受，头值携带密钥版本或在网关做两次比对）；
2. 应用滚动更新为新密钥；
3. 网关摘除旧密钥。当前版本过滤器只支持单密钥，双密钥轮换需在网关侧兼容。

## 3. 提示注入与输出网关（AC-J2）

- **输入侧** `GuardrailAdvisorChain.checkInput`：`KeywordBlacklistDetector`
  （中英文指令覆盖、jailbreak 等正则）+ `LlmJudgeDetector`
  （配置了 `DASHSCOPE_API_KEY` 时走模型裁判；无 key 自动降级为本地规则，
  命中"忽略/ignore/disregard/system prompt/jailbreak/dan"等直接阻断），
  另有 Azure Prompt Shield、Lakera 检测器可按部署启用。阻断时审计落
  GatewayAudit（logAudit/logViolation）。
- **输出侧** `StreamingOutputGuardrailSender`：装饰 SSE 发送器，累积流式 delta，
  done 时整体过输出链；命中则不发 done，改写 `blocked` 事件
  （reason/violationType/detector=output_guardrail_chain/phase=streaming_output），
  答案不落 assistant 历史、改写 blocked 历史标记。
- **异步 MDC**：`ChatService.chat()` 入口捕获 MDC 副本，经 `@Async("chatExecutor")`
  代理方法在工作线程恢复，traceId 贯穿 SSE 全链路。

### 已知局限（需运营知晓）

- **纯编码走私**（如载荷只有 Base64、无任何明文锚点）正则链无法识别，
  生产应接入 Azure Prompt Shield / Lakera 等云端检测器；
  回归用例覆盖的是"编码块 + 明文指令锚点"的常见组合。
- LLM 裁判无 key 时是规则降级，召回率低于模型裁判；staging/prod 应保障 key 可用。

## 4. PII 最小化外发（AC-J3）

`wikiagent.security.pii-minimization.enabled=true`（prod 默认 true、dev 默认 false）时，
BeanPostProcessor 给所有 `ChatModel` Bean 包一层
`PiiMinimizingChatModelDecorator`，在调用/流式外发前处理 USER/SYSTEM 文本：

| 类别 | 识别 | mask 形态（默认） |
| --- | --- | --- |
| 手机号 | `1[3-9]\d{9}`（数字边界环视） | `138****8000` |
| 身份证 | 18 位（末位 X） | `110101********123X` |
| 银行卡 | 16–19 位 | `6228********4890` |
| 邮箱 | 标准邮箱格式 | `zh***@example.com`（local 保留前 2） |

- `mask`：脱敏后继续外发，保留可对话性；ASSISTANT/工具消息不处理（输出侧由输出网关负责）。
- `block`：不触达下游模型，call/stream 都返回固定中文拒答。
  按类别配置：`wikiagent.security.pii-minimization.policies.{phone,email,id-card,bank-card}=mask|block`。
- 命中只打类别日志（"PII 外发掩码/阻断 categories=PHONE（原始文本不记录）"），
  审计/日志中不出现原始 PII。

## 5. 回归用例与应急

- 攻击回归：`AttackRegressionTest`（surefire，无需 Docker）覆盖注入 5 变体、
  检索越权（business 伪造身份 0 命中/无身份头全量）、缓存端点 403、
  工具 scope 拒绝（SCOPE_MISMATCH + TOOL_DENIED 审计）；
  链级快速断言见 `InputGuardrailRegressionTest`。
- 信任边界：`TrustedHeaderFilterTest` 覆盖 fail-fast、401、身份头透传、匿名放行。
- PII：`PiiMinimizerTest`、`PiiMinimizingChatModelDecoratorTest`。
- **紧急止血**：发现误杀时可临时
  `WIKIAGENT_PII_MINIMIZATION_ENABLED=false` 或调整 policies，
  但**不得**在生产长期关闭 trust-boundary；关闭事件必须进变更记录。
