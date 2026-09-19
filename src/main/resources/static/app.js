/* WikiAgent 前端逻辑：对话（fetch + ReadableStream 手工解析 SSE）与知识库管理 */
"use strict";

const $ = (id) => document.getElementById(id);

/* ================= 标签页切换 ================= */
$("tab-chat").addEventListener("click", () => switchTab("chat"));
$("tab-docs").addEventListener("click", () => switchTab("docs"));
function switchTab(name) {
  $("tab-chat").classList.toggle("active", name === "chat");
  $("tab-docs").classList.toggle("active", name === "docs");
  $("page-chat").classList.toggle("active", name === "chat");
  $("page-docs").classList.toggle("active", name === "docs");
}

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
  for (const file of files) {
    const fd = new FormData();
    fd.append("file", file);
    try {
      const res = await fetch("/api/documents", { method: "POST", body: fd });
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
  const state = { answer: "", sources: [] };
  try {
    const res = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question: q }),
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
    case "stage":
      if (payload.stage === "rewriting") setStage(shell, "查询改写中…");
      else if (payload.stage === "retrieving")
        setStage(shell, "混合检索中（BM25 + 向量 → RRF）…" +
          (payload.rewrittenQuery ? `<div class="muted">改写后：${escapeHtml(payload.rewrittenQuery)}</div>` : ""));
      else if (payload.stage === "generating") setStage(shell, "生成回答中…");
      break;
    case "sources":
      state.sources = payload || [];
      renderSources(shell, state.sources);
      break;
    case "delta":
      state.answer += payload.text || "";
      renderAnswer(shell, state);
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
  div.innerHTML = `<div class="stage"></div><div class="answer"></div><div class="sources"></div><div class="error"></div>`;
  $("messages").appendChild(div);
  scrollBottom();
  return div;
}

function setStage(shell, html) {
  const el = shell.querySelector(".stage");
  el.innerHTML = html;
  scrollBottom();
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

/* 初始化 */
refreshHealth();
refreshDocs();
