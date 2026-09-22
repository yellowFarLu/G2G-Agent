/* G2G Agent 前端逻辑：对话（fetch + ReadableStream 手工解析 SSE）与知识库管理 */
"use strict";

const $ = (id) => document.getElementById(id);

/* ================= 枚举常量（与后端 9×6×5 矩阵一致） ================= */
const DOMAINS = [
  ["industry_solution", "行业解决方案"], ["merchant_center", "商家中心"], ["pms", "PMS"],
  ["service_provider", "服务商"], ["trunk_line", "干线"], ["customs", "关务"],
  ["settlement", "结算"], ["first_mile", "首公里"], ["trajectory", "轨迹"],
];
const SUBDOMAINS = [
  ["business", "业务知识"], ["product", "产品知识"], ["tech", "技术知识"],
  ["testing", "测试知识"], ["safety", "安全生产知识"], ["management", "管理层知识"],
];
const IDENTITIES = [
  ["admin", "管理员"], ["business", "业务"], ["product", "产品"],
  ["tech", "技术"], ["testing", "测试"],
];

function fillSelect(sel, options, placeholder) {
  sel.innerHTML = (placeholder ? `<option value="">${placeholder}</option>` : "") +
    options.map(([v, t]) => `<option value="${v}">${t}</option>`).join("");
}

/* ================= 标签页切换 ================= */
$("tab-chat").addEventListener("click", () => switchTab("chat"));
$("tab-docs").addEventListener("click", () => switchTab("docs"));
$("tab-govern").addEventListener("click", () => switchTab("govern"));
$("tab-observe").addEventListener("click", () => switchTab("observe"));
function switchTab(name) {
  for (const n of ["chat", "docs", "govern", "observe"]) {
    $(`tab-${n}`).classList.toggle("active", n === name);
    $(`page-${n}`).classList.toggle("active", n === name);
  }
}

/* ================= 业务会话 ID ================= */
// 页面会话 ID：同一会话内多轮提问复用（后端短期记忆/trace/审计/反馈均按此串联）
let currentSessionId = newSessionId();
function newSessionId() {
  return "s-" + Date.now().toString(16) + Math.random().toString(36).slice(2, 8);
}
function renderCurrentSession() {
  $("current-session").textContent = currentSessionId;
}
$("btn-new-session").addEventListener("click", () => {
  currentSessionId = newSessionId();
  renderCurrentSession();
});
$("btn-copy-session").addEventListener("click", async () => {
  try { await navigator.clipboard.writeText(currentSessionId); } catch { /* 剪贴板不可用时忽略 */ }
});
$("current-session").addEventListener("click", () => {
  try { navigator.clipboard.writeText(currentSessionId); } catch { /* ignore */ }
});
renderCurrentSession();

// === 会话侧栏 ===
$("btn-toggle-sidebar").addEventListener("click", () => {
  $("session-sidebar").classList.toggle("open");
  if ($("session-sidebar").classList.contains("open")) refreshSessions();
});
$("btn-close-sidebar").addEventListener("click", () => {
  $("session-sidebar").classList.remove("open");
});

async function refreshSessions() {
  try {
    const res = await fetch("/api/chat/sessions");
    const sessions = await res.json();
    const list = $("session-list");
    if (!sessions.length) {
      list.innerHTML = '<div class="muted">暂无历史会话</div>';
      return;
    }
    list.innerHTML = sessions.map(s => `
      <div class="session-item${s.sessionId === currentSessionId ? " active" : ""}" data-sid="${s.sessionId}">
        <div class="si-preview">${escHtml(s.preview)}</div>
        <div class="si-meta"><span>${s.messageCount} 条</span><span>${fmtTime(s.lastTime)}</span></div>
      </div>`).join("");
    list.querySelectorAll(".session-item").forEach(el => {
      el.addEventListener("click", () => loadSession(el.dataset.sid));
    });
  } catch (e) { console.error("加载会话列表失败", e); }
}

async function loadSession(sid) {
  currentSessionId = sid;
  renderCurrentSession();
  document.querySelectorAll(".session-item").forEach(el => {
    el.classList.toggle("active", el.dataset.sid === sid);
  });
  $("messages").innerHTML = "";
  try {
    const res = await fetch(`/api/chat/sessions/${sid}/messages`);
    const msgs = await res.json();
    for (const m of msgs) {
      appendMessage(m.role, m.content);
    }
    if (!msgs.length) {
      $("messages").innerHTML = '<div class="empty-hint">该会话暂无消息记录</div>';
    }
  } catch (e) { console.error("加载会话消息失败", e); }
}

function escHtml(s) { const d = document.createElement("div"); d.textContent = s || ""; return d.innerHTML; }
function fmtTime(t) { if (!t) return ""; const d = new Date(t); return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours()}:${String(d.getMinutes()).padStart(2, "0")}`; }

/* 知识治理子 tab */
$("subtab-metrics").addEventListener("click", () => switchSub("govern", "metrics", "conflicts"));
$("subtab-conflicts").addEventListener("click", () => { switchSub("govern", "conflicts", "metrics"); refreshConflicts(); });
function switchSub(kind, active, other) {
  $(`subtab-${active}`)?.classList.add("active");
  $(`subtab-${other}`)?.classList.remove("active");
  $(`${kind}-${active}`).classList.add("active");
  $(`${kind}-${other}`).classList.remove("active");
}

/* 可观测 4 tab */
const OBS_TABS = ["trace", "metrics", "knowledge", "audit"];
for (const t of OBS_TABS) {
  $(`obs-tab-${t}`).addEventListener("click", () => {
    for (const o of OBS_TABS) {
      $(`obs-tab-${o}`).classList.toggle("active", o === t);
      $(`obs-${o}`).classList.toggle("active", o === t);
    }
  });
}

/* ================= 初始化下拉框 ================= */
fillSelect($("chat-domain"), DOMAINS, "（不指定，走默认链路）");
fillSelect($("upload-domain"), DOMAINS, "（不打标）");
fillSelect($("upload-subdomain"), SUBDOMAINS, "（不打标）");
fillSelect($("upload-identity"), IDENTITIES, "（不指定）");
fillSelect($("chat-identity"), IDENTITIES, "（匿名）");
$("chat-domain").addEventListener("change", () => {
  const sel = $("chat-subdomain");
  if ($("chat-domain").value) {
    fillSelect(sel, SUBDOMAINS, "（不指定）");
    sel.disabled = false;
  } else {
    sel.innerHTML = `<option value="">（先选领域）</option>`;
    sel.disabled = true;
  }
});


/* ================= 健康检查 ================= */
async function refreshHealth() {
  const box = $("health");
  try {
    const res = await fetch("/api/health");
    const h = await res.json();
    const ds = h.dashscope || {};
    const mv = h.milvus || {};
    box.innerHTML =
      `<span class="chip ${ds.apiKeyConfigured ? "ok" : "bad"}">DashScope Key ${ds.apiKeyConfigured ? "已配置" : "未配置"}</span>` +
      `<span class="chip ${mv.status === "UP" ? "ok" : "bad"}" title="${mv.error ? escapeHtml(mv.error) : mv.version || ""}">` +
      `Milvus ${mv.status === "UP" ? "已连接" + (mv.version ? " " + escapeHtml(mv.version) : "") : "不可用"}</span>`;
  } catch (e) {
    box.innerHTML = `<span class="chip bad">后端不可达</span>`;
  }
}
function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/* ================= 知识库管理 ================= */
const PROCESSING = new Set(["PARSING", "CLEANING", "CHUNKING", "EMBEDDING", "INDEXING"]);
let pollTimer = null;

async function refreshDocs() {
  try {
    const res = await fetch("/api/documents");
    const docs = await res.json();
    renderDocs(docs);
    if (docs.some((d) => PROCESSING.has(d.status))) {
      schedulePoll();
    }
  } catch (e) {
    $("docs-body").innerHTML = `<tr><td colspan="8" class="muted">加载失败：${escapeHtml(e.message)}</td></tr>`;
  }
}

function schedulePoll() {
  if (pollTimer) return;
  pollTimer = setTimeout(async () => {
    pollTimer = null;
    await refreshDocs();
  }, 2000);
}

function renderDocs(docs) {
  if (!docs.length) {
    $("docs-body").innerHTML = `<tr><td colspan="8" class="muted">暂无文档，请先上传</td></tr>`;
    return;
  }
  $("docs-body").innerHTML = docs.map((d) => {
    const statusHtml = PROCESSING.has(d.status)
      ? `<span class="badge processing">${d.status}</span>`
      : d.status === "READY"
        ? `<span class="badge ready">READY</span>`
        : `<span class="badge failed">FAILED</span>${d.error ? `<div class="muted" title="${escapeHtml(d.error)}">${escapeHtml(d.error.slice(0, 80))}${d.error.length > 80 ? "…" : ""}</div>` : ""}`;
    return `<tr>
      <td>${escapeHtml(d.filename)}</td>
      <td>${escapeHtml(d.docType)}</td>
      <td>${formatSize(d.sizeBytes)}</td>
      <td>${statusHtml}</td>
      <td>${d.parentCount}</td>
      <td>${d.childCount}</td>
      <td>${new Date(d.createdAt).toLocaleString()}</td>
      <td><button class="btn-del" data-id="${d.id}">删除</button></td>
    </tr>`;
  }).join("");
  document.querySelectorAll(".btn-del").forEach((btn) =>
    btn.addEventListener("click", () => deleteDoc(btn.dataset.id)));
}

function formatSize(n) {
  if (n == null) return "";
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
  return (n / 1024 / 1024).toFixed(1) + " MB";
}

$("btn-upload").addEventListener("click", async () => {
  const files = $("file-input").files;
  if (!files.length) { alert("请先选择文件"); return; }
  $("btn-upload").disabled = true;
  let failed = 0;
  const domain = $("upload-domain").value;
  const subDomain = $("upload-subdomain").value;
  const userId = $("upload-user").value.trim();
  const identity = $("upload-identity").value;
  for (const file of files) {
    const fd = new FormData();
    fd.append("file", file);
    if (domain) fd.append("domain", domain);
    if (subDomain) fd.append("subDomain", subDomain);
    const headers = {};
    if (userId) headers["X-User-Id"] = userId;
    if (identity) headers["X-Business-Identity"] = identity;
    try {
      const res = await fetch("/api/documents", { method: "POST", body: fd, headers });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        failed++;
        alert(`${file.name} 上传失败：${err.message || res.status}`);
      }
    } catch (e) {
      failed++;
      alert(`${file.name} 上传失败：${e.message}`);
    }
  }
  $("btn-upload").disabled = false;
  $("file-input").value = "";
  if (!failed) switchTab("docs");
  refreshDocs();
});

async function deleteDoc(id) {
  if (!confirm("确定删除该文档？（将同时删除向量索引与切片）")) return;
  const res = await fetch(`/api/documents/${id}`, { method: "DELETE" });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    alert(`删除失败：${err.message || res.status}`);
  }
  refreshDocs();
}

/* ================= 对话 ================= */
let abortCtrl = null;

$("btn-send").addEventListener("click", ask);
$("question").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); ask(); }
});
$("btn-stop").addEventListener("click", () => { if (abortCtrl) abortCtrl.abort(); });

async function ask() {
  const input = $("question");
  const q = input.value.trim();
  if (!q || abortCtrl) return;
  input.value = "";
  $("empty-hint")?.remove?.();

  appendMessage("user", q);
  const shell = addAssistantShell();
  setBusy(true);

  abortCtrl = new AbortController();
  const state = { answer: "", sources: [], sessionId: currentSessionId };
  try {
    const res = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        question: q,
        domain: $("chat-domain").value || null,
        subDomain: $("chat-subdomain").value || null,
        identity: $("chat-identity").value || null,
        sessionId: currentSessionId,
      }),
      signal: abortCtrl.signal,
    });
    if (!res.ok || !res.body) {
      let msg = `请求失败 (${res.status})`;
      try { const j = await res.json(); if (j.message) msg = j.message; } catch { /* ignore */ }
      throw new Error(msg);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf("\n\n")) >= 0) {
        const frame = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        handleFrame(frame, shell, state);
      }
    }
    finishShell(shell, state);
  } catch (e) {
    if (e.name === "AbortError") {
      state.answer += "\n\n（已停止）";
      renderAnswer(shell, state);
    } else {
      showError(shell, e.message);
    }
  } finally {
    setBusy(false);
    abortCtrl = null;
    // 侧栏打开时刷新会话列表
    if ($("session-sidebar").classList.contains("open")) refreshSessions();
  }
}

function handleFrame(frame, shell, state) {
  let event = "message";
  let data = "";
  for (const line of frame.split("\n")) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) data += line.slice(5).trim();
  }
  if (!data) return;
  let payload;
  try { payload = JSON.parse(data); } catch { return; }

  switch (event) {
    case "session":
      // 后端权威会话 ID（与 trace/审计/反馈/短期记忆串联），同步页面会话条与本气泡
      state.sessionId = payload.sessionId || state.sessionId;
      currentSessionId = state.sessionId;
      renderCurrentSession();
      renderSessionChip(shell, state.sessionId);
      break;
    case "stage":
      if (payload.stage === "routing") setStage(shell, "分析问题类型（判断是否需要检索）…");
      else if (payload.stage === "rewriting") setStage(shell, "查询改写中…");
      else if (payload.stage === "retrieving") {
        if (payload.queries && payload.queries.length) {
          setStage(shell, `第 ${payload.round || 1} 轮混合检索（BM25 + 向量 → RRF）…` +
            `<div class="muted">检索查询：${payload.queries.map(escapeHtml).join(" / ")}</div>`);
        } else {
          setStage(shell, "混合检索中（BM25 + 向量 → RRF）…" +
            (payload.rewrittenQuery ? `<div class="muted">改写后：${escapeHtml(payload.rewrittenQuery)}</div>` : ""));
        }
      } else if (payload.stage === "grading")
        setStage(shell, `第 ${payload.round || 1} 轮证据评估中（判断是否需要重检）…`);
      else if (payload.stage === "generating")
        setStage(shell, payload.mode === "direct" ? "直接回答中（无需检索）…" : "生成回答中…");
      else if (payload.stage === "fallback")
        renderFallback(shell, state, payload.mode);
      break;
    case "sources":
      state.sources = payload || [];
      renderSources(shell, state.sources);
      break;
    case "delta":
      state.answer += payload.text || "";
      renderAnswer(shell, state);
      break;
    case "blocked":
      shell.querySelector(".error").innerHTML =
        `<span class="badge blocked">已被安全网关拦截</span> ${escapeHtml(payload.reason || "内容不合规")}` +
        (payload.violationType ? ` <span class="muted">（${escapeHtml(payload.violationType)}）</span>` : "");
      shell.querySelector(".stage").innerHTML = "";
      state.blocked = true;
      break;
    case "error":
      showError(shell, payload.message || "未知错误");
      break;
    case "done":
      state.finished = true;
      break;
    default:
      break;
  }
}

function appendMessage(role, text) {
  const div = document.createElement("div");
  div.className = `msg ${role}`;
  div.textContent = text;
  $("messages").appendChild(div);
  scrollBottom();
}

function addAssistantShell() {
  const div = document.createElement("div");
  div.className = "msg assistant";
  div.innerHTML = `<div class="msg-session hidden"></div><div class="stage"></div><div class="fallback-badge hidden"></div><div class="answer"></div><div class="sources"></div>` +
    `<div class="feedback hidden"><button type="button" data-fb="USEFUL" class="fb-btn">👍 有用</button>` +
    `<button type="button" data-fb="USELESS" class="fb-btn">👎 无用</button><span class="fb-result muted"></span></div>` +
    `<div class="error"></div>`;
  $("messages").appendChild(div);
  scrollBottom();
  return div;
}

function setStage(shell, html) {
  const el = shell.querySelector(".stage");
  el.innerHTML = html;
  scrollBottom();
}

/** 在回答气泡顶部展示会话 ID，提供复制与"查链路"跳转。 */
function renderSessionChip(shell, sid) {
  const el = shell.querySelector(".msg-session");
  if (!el || !sid) return;
  el.innerHTML = `<span class="chip gray">会话 ${escapeHtml(sid)}</span>` +
    `<button type="button" class="link-btn" data-act="copy-sid">复制 ID</button>` +
    `<button type="button" class="link-btn" data-act="trace-sid">查链路</button>`;
  el.classList.remove("hidden");
  el.querySelector('[data-act="copy-sid"]').addEventListener("click", () => {
    try { navigator.clipboard.writeText(sid); } catch { /* ignore */ }
  });
  el.querySelector('[data-act="trace-sid"]').addEventListener("click", () => {
    $("trace-session").value = sid;
    switchTab("observe");
    queryTrace();
  });
}

/** 知识库未命中兜底：显示持久来源徽标 + 当前阶段提示。 */
function renderFallback(shell, state, mode) {
  state.fallbackMode = mode || "model_knowledge";
  const badge = shell.querySelector(".fallback-badge");
  if (mode === "web_search") {
    badge.innerHTML = `<span class="badge fb-web">🌐 企业知识库未命中 · 联网搜索回答</span>`;
    setStage(shell, "知识库未命中，正在联网检索公开信息并生成回答…");
  } else {
    badge.innerHTML = `<span class="badge fb-model">🧠 企业知识库未命中 · 模型通用知识回答（非企业文档，请注意甄别）</span>`;
    setStage(shell, "知识库未命中，正在使用模型通用知识回答…");
  }
  badge.classList.remove("hidden");
}

function renderSources(shell, sources) {
  const el = shell.querySelector(".sources");
  el.innerHTML = (sources || [])
    .map((s) => `<a class="src" href="javascript:void 0" title="docId: ${escapeHtml(s.docId)}">[${s.index}] ${escapeHtml(s.filename)} · score ${Number(s.score).toFixed(4)}</a>`)
    .join("");
}

function renderAnswer(shell, state) {
  shell.querySelector(".answer").textContent = state.answer;
  scrollBottom();
}

function finishShell(shell, state) {
  if (state.answer) renderAnswer(shell, state);
  shell.querySelector(".stage").innerHTML = "";
  // 有实际答案且未被安全网关拦截时，展示"有用/无用"反馈按钮
  const fb = shell.querySelector(".feedback");
  if (state.answer && !state.blocked && fb) {
    fb.classList.remove("hidden");
    fb.querySelectorAll(".fb-btn").forEach((btn) =>
      btn.addEventListener("click", () => submitFeedback(btn, shell, state)));
  }
}

async function submitFeedback(btn, shell, state) {
  const type = btn.dataset.fb;
  const result = shell.querySelector(".fb-result");
  shell.querySelectorAll(".fb-btn").forEach((b) => (b.disabled = true));
  try {
    const res = await fetch("/api/feedback", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        userId: $("chat-identity").value || "anonymous",
        // 复用后端下发的真实会话 ID，使反馈可与 trace/审计按会话关联
        sessionId: state.sessionId || currentSessionId,
        conversationId: state.sessionId || currentSessionId,
        // 当前 SSE sources 仅含 docId（父文档粒度），无 chunkId，会话级反馈不强挂 chunk
        chunkId: null,
        feedbackType: type,
      }),
    });
    if (!res.ok) throw new Error("HTTP " + res.status);
    result.textContent = type === "USEFUL" ? "已记录：有用，谢谢反馈" : "已记录：无用，我们将改进";
  } catch (e) {
    result.textContent = "反馈失败：" + e.message;
    shell.querySelectorAll(".fb-btn").forEach((b) => (b.disabled = false));
  }
}

function showError(shell, msg) {
  shell.querySelector(".error").textContent = "出错了：" + msg;
  shell.querySelector(".stage").innerHTML = "";
  scrollBottom();
}

function setBusy(busy) {
  $("btn-send").disabled = busy;
  $("btn-stop").classList.toggle("hidden", !busy);
}

function scrollBottom() {
  const box = $("messages");
  box.scrollTop = box.scrollHeight;
}

/* ================= v4 知识治理：知识看板 ================= */
function pct(v) { return (Number(v || 0) * 100).toFixed(1) + "%"; }

function kpiCard(label, value, hint) {
  return `<div class="kpi-card"><div class="kpi-value">${escapeHtml(String(value))}</div>` +
    `<div class="kpi-label">${escapeHtml(label)}</div>${hint ? `<div class="muted">${escapeHtml(hint)}</div>` : ""}</div>`;
}

$("btn-aggregate").addEventListener("click", () => loadAggregation(true));

async function loadAggregation(refresh) {
  $("stale-body").innerHTML = `<tr><td colspan="8" class="muted">聚合中…</td></tr>`;
  try {
    const res = await fetch(`/api/metrics/aggregation${refresh ? "?refresh=true" : ""}`);
    const data = await res.json();
    if (!data || data.aggregatedAt == null) {
      $("kpi-row").innerHTML = "";
      $("stale-body").innerHTML = `<tr><td colspan="8" class="muted">${escapeHtml(data.message || "暂无聚合数据")}</td></tr>`;
      return;
    }
    $("kpi-row").innerHTML =
      kpiCard("知识总量", data.totalKnowledge) +
      kpiCard("近30天检索", data.totalRetrievals, "被引用 " + data.totalCitations + " 次") +
      kpiCard("有用率", pct(data.usefulnessRate), `${data.usefulCount} 有用 / ${data.uselessCount} 无用`) +
      kpiCard("召回率", pct(data.recallRate), "引用 / 检索") +
      kpiCard("疑似过期", data.staleKnowledgeCount, `阈值 stale<0.3，τ=${180}d`);
    const stale = data.staleKnowledge || [];
    $("stale-body").innerHTML = stale.length
      ? stale.map((k) => `<tr>
          <td title="${escapeHtml(k.chunkId)}">${escapeHtml(String(k.chunkId).slice(0, 12))}…</td>
          <td>${escapeHtml(k.docId || "")}</td>
          <td>${escapeHtml(k.domainTag || "")}</td>
          <td>${escapeHtml(k.subDomainTag || "")}</td>
          <td>${escapeHtml(k.createdBy || "")}</td>
          <td>${k.createdAt ? new Date(k.createdAt).toLocaleDateString() : ""}</td>
          <td>${k.recentRetrievals}</td>
          <td><span class="badge ${k.staleScore < 0.15 ? "failed" : "processing"}">${k.staleScore}</span></td>
        </tr>`).join("")
      : `<tr><td colspan="8" class="muted">没有疑似过期知识</td></tr>`;
  } catch (e) {
    $("stale-body").innerHTML = `<tr><td colspan="8" class="muted">聚合失败：${escapeHtml(e.message)}</td></tr>`;
  }
}

/* ================= v4 知识治理：冲突审核（代码冲突合并式） ================= */
$("btn-conflict-refresh").addEventListener("click", refreshConflicts);
$("conflict-status").addEventListener("change", refreshConflicts);
$("btn-conflict-scan").addEventListener("click", async () => {
  const btn = $("btn-conflict-scan");
  btn.disabled = true;
  btn.textContent = "扫描中…";
  try {
    const res = await fetch("/api/conflicts/scan", { method: "POST" });
    const data = await res.json().catch(() => ({}));
    alert(res.ok ? `扫描完成，新检出冲突 ${data.newConflicts ?? "?"} 条` : "扫描失败：" + res.status);
    refreshConflicts();
  } catch (e) {
    alert("扫描失败：" + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "立即扫描";
  }
});

async function refreshConflicts() {
  const status = $("conflict-status").value;
  try {
    const res = await fetch(`/api/conflicts?status=${encodeURIComponent(status)}`);
    const list = await res.json();
    $("conflict-body").innerHTML = list.length
      ? list.map((c) => `<tr data-id="${c.id}" class="conflict-row">
          <td>${c.id}</td><td>${escapeHtml((c.domainTag || "") + "/" + (c.subDomainTag || ""))}</td>
          <td>${Number(c.similarity).toFixed(4)}</td><td>${escapeHtml(c.status)}</td>
          <td>${c.detectedAt ? new Date(c.detectedAt).toLocaleString() : ""}</td></tr>`).join("")
      : `<tr><td colspan="5" class="muted">暂无${status}状态的冲突</td></tr>`;
    document.querySelectorAll(".conflict-row").forEach((tr) =>
      tr.addEventListener("click", () => showConflictDiff(tr.dataset.id)));
  } catch (e) {
    $("conflict-body").innerHTML = `<tr><td colspan="5" class="muted">加载失败：${escapeHtml(e.message)}</td></tr>`;
  }
}

async function showConflictDiff(id) {
  const box = $("conflict-detail");
  box.innerHTML = `<div class="muted placeholder">加载中…</div>`;
  try {
    const res = await fetch(`/api/conflicts/${id}/diff`);
    if (!res.ok) throw new Error("HTTP " + res.status);
    const d = await res.json();
    const c = d.conflict;
    const pane = (title, ch, color) => `<div class="diff-pane ${color}">
      <div class="diff-head">${title}
        <span class="muted">${escapeHtml(String(ch.chunkId).slice(0, 16))}…</span></div>
      <div class="diff-meta muted">${escapeHtml([ch.domainTag, ch.subDomainTag, ch.createdIdentity, ch.sourceFilename].filter(Boolean).join(" · "))}</div>
      <pre class="diff-content">${escapeHtml(ch.content || "（chunk 原文缺失，可能已被删除）")}</pre>
    </div>`;
    const resolved = c.status !== "DETECTED";
    box.innerHTML = `
      <div class="diff-summary">冲突 #${c.id} · 相似度 ${Number(c.similarity).toFixed(4)} ·
        状态 <span class="badge ${resolved ? "ready" : "processing"}">${escapeHtml(c.status)}</span>
        ${c.resolution ? ` · 决议 <b>${escapeHtml(c.resolution)}</b>` : ""}
        ${c.resolvedBy ? ` · 处理人 ${escapeHtml(c.resolvedBy)}` : ""}
      </div>
      <div class="diff-panes">${pane("A 方", d.chunkA, "side-a")}${pane("B 方", d.chunkB, "side-b")}</div>
      <div class="diff-actions" ${resolved ? "hidden" : ""}>
        <button data-res="KEEP_A" type="button">保留 A</button>
        <button data-res="KEEP_B" type="button">保留 B</button>
        <button data-res="KEEP_BOTH" type="button">双方并存</button>
        <button data-res="MERGE" type="button">已人工合并</button>
        <button data-res="DELETE_A" class="danger" type="button">淘汰 A</button>
        <button data-res="DELETE_B" class="danger" type="button">淘汰 B</button>
        <button data-res="IGNORE" type="button">误报忽略</button>
      </div>
      <input id="resolve-comment" class="resolve-comment" type="text" placeholder="处理说明（可选）">`;
    box.querySelectorAll(".diff-actions button").forEach((btn) =>
      btn.addEventListener("click", () => resolveConflict(id, btn.dataset.res)));
  } catch (e) {
    box.innerHTML = `<div class="muted placeholder">加载失败：${escapeHtml(e.message)}</div>`;
  }
}

async function resolveConflict(id, resolution) {
  const comment = $("resolve-comment")?.value?.trim() || "";
  if (resolution.startsWith("DELETE") &&
      !confirm(`确认执行 ${resolution}？对应知识的 is_active 将被置为 false（检索不再命中）。`)) return;
  const url = resolution === "IGNORE"
    ? `/api/conflicts/${id}/ignore`
    : `/api/conflicts/${id}/resolve`;
  const body = resolution === "IGNORE"
    ? { resolvedBy: "web-admin", comment }
    : { resolution, resolvedBy: "web-admin", comment };
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      alert("处理失败：" + (err.message || res.status));
      return;
    }
    await showConflictDiff(id);
    refreshConflicts();
  } catch (e) {
    alert("处理失败：" + e.message);
  }
}

/* ================= v5 统一可观测 ================= */
$("btn-trace-query").addEventListener("click", queryTrace);
$("trace-session").addEventListener("keydown", (e) => { if (e.key === "Enter") queryTrace(); });

async function queryTrace() {
  const sid = $("trace-session").value.trim();
  if (!sid) return;
  $("trace-result").innerHTML = `<div class="muted">查询中…</div>`;
  try {
    const res = await fetch(`/api/trace/session/${encodeURIComponent(sid)}`);
    const spans = await res.json();
    $("trace-result").innerHTML = spans.length
      ? spans.map((s, i) => `<div class="trace-span">
          <div class="trace-line"><b>#${i + 1}</b> ${escapeHtml(s.nodeId || "")}
            <span class="muted">[${escapeHtml(s.spanType || "")}]</span>
            <span class="badge ${s.status === "ERROR" ? "failed" : "ready"}">${escapeHtml(s.status || "")}</span>
            <span class="muted">${s.durationMs ?? 0}ms</span>
            ${s.modelUsed ? `<span class="muted">· ${escapeHtml(s.modelUsed)}</span>` : ""}
          </div>
          ${s.errorMsg ? `<div class="trace-err">${escapeHtml(s.errorMsg)}</div>` : ""}
        </div>`).join("")
      : `<div class="muted">该 session 没有 trace 记录</div>`;
  } catch (e) {
    $("trace-result").innerHTML = `<div class="muted">查询失败：${escapeHtml(e.message)}</div>`;
  }
}

$("btn-obs-refresh").addEventListener("click", async () => {
  try {
    const res = await fetch("/api/observability/dashboard");
    const d = await res.json();
    const bm = d.businessMetrics || {};
    const kd = d.knowledgeDetails || {};
    const fa = d.feedbackAudit || {};
    $("obs-metrics-body").innerHTML =
      kpiCard("检索次数（7d）", bm.retrievals ?? "-") +
      kpiCard("引用次数（7d）", bm.citations ?? "-") +
      kpiCard("有用反馈（7d）", bm.useful ?? "-") +
      kpiCard("无用反馈（7d）", bm.useless ?? "-") +
      kpiCard("知识总量", kd.totalKnowledge ?? "-") +
      kpiCard("过期知识", kd.staleKnowledge ?? "-", "超 180 天未更新") +
      kpiCard("待处理冲突", kd.pendingConflicts ?? "-") +
      kpiCard("网关拦截", fa.gatewayBlocked ?? "-");
  } catch (e) {
    $("obs-metrics-body").innerHTML = `<div class="muted">加载失败：${escapeHtml(e.message)}</div>`;
  }
});

$("btn-obs-knowledge").addEventListener("click", async () => {
  $("obs-knowledge-body").innerHTML = `<tr><td colspan="9" class="muted">加载中…</td></tr>`;
  try {
    const list = await (await fetch("/api/observability/knowledge")).json();
    $("obs-knowledge-body").innerHTML = list.length
      ? list.map((k) => `<tr>
          <td title="${escapeHtml(k.chunkId)}">${escapeHtml(String(k.chunkId).slice(0, 12))}…</td>
          <td>${escapeHtml(k.docId || "")}</td><td>${escapeHtml(k.domainTag || "")}</td>
          <td>${escapeHtml(k.subDomainTag || "")}</td><td>${escapeHtml(k.requiredIdentity || "")}</td>
          <td>${escapeHtml(k.createdBy || "")}</td><td>${escapeHtml(k.sourceFilename || "")}</td>
          <td>${k.version ?? ""}</td>
          <td>${k.createdAt ? new Date(k.createdAt).toLocaleDateString() : ""}</td></tr>`).join("")
      : `<tr><td colspan="9" class="muted">暂无知识元数据（上传文档并打标后出现）</td></tr>`;
  } catch (e) {
    $("obs-knowledge-body").innerHTML = `<tr><td colspan="9" class="muted">加载失败：${escapeHtml(e.message)}</td></tr>`;
  }
});

$("btn-obs-audit").addEventListener("click", async () => {
  try {
    const [feedbacks, gateway] = await Promise.all([
      fetch("/api/observability/feedback").then((r) => r.json()),
      fetch("/api/observability/gateway").then((r) => r.json()),
    ]);
    $("audit-feedback-body").innerHTML = feedbacks.length
      ? feedbacks.slice(0, 200).map((f) => `<tr>
          <td>${f.id}</td><td>${escapeHtml(f.sessionId || "")}</td>
          <td>${escapeHtml(f.chunkId || "")}</td>
          <td><span class="badge ${f.feedbackType === "USEFUL" ? "ready" : "failed"}">${escapeHtml(f.feedbackType || "")}</span></td>
          <td>${escapeHtml(f.feedbackComment || "")}</td>
          <td>${f.createdAt ? new Date(f.createdAt).toLocaleString() : ""}</td></tr>`).join("")
      : `<tr><td colspan="6" class="muted">暂无反馈</td></tr>`;
    $("audit-gateway-body").innerHTML = gateway.length
      ? gateway.slice(0, 200).map((g) => `<tr>
          <td>${g.id}</td><td>${escapeHtml(g.direction || "")}</td>
          <td>${escapeHtml(g.detectorName || "")}</td>
          <td><span class="badge ${g.detectionResult === "BLOCK" ? "failed" : "ready"}">${escapeHtml(g.detectionResult || "")}</span></td>
          <td>${g.riskScore ?? ""}</td><td>${escapeHtml(g.userId || "")}</td>
          <td>${g.createdAt ? new Date(g.createdAt).toLocaleString() : ""}</td></tr>`).join("")
      : `<tr><td colspan="7" class="muted">暂无网关审计记录</td></tr>`;
  } catch (e) {
    alert("审计数据加载失败：" + e.message);
  }
});

/* 初始化 */
refreshHealth();
refreshDocs();
