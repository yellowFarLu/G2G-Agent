#!/bin/bash
# ============================================================
# G2G Agent 停止脚本
# 用法: ./stop.sh
# 策略: 先优雅 kill(SIGTERM)，超时后强制 kill -9(SIGKILL)
# ============================================================
cd "$(dirname "$0")"

PORT=8090

# 1. 查找占用端口的进程
PIDS=$(lsof -ti:$PORT 2>/dev/null)
if [ -z "$PIDS" ]; then
  echo "ℹ️  端口 $PORT 无进程占用，应用未在运行"
  exit 0
fi

echo "正在停止应用 (PID: $PIDS)..."

# 2. 优雅停止（SIGTERM）
kill $PIDS 2>/dev/null || true

# 3. 等待优雅关闭（最多 20 秒）
for i in $(seq 1 20); do
  if ! lsof -ti:$PORT >/dev/null 2>&1; then
    echo "✅ 应用已优雅停止"
    exit 0
  fi
  sleep 1
done

# 4. 超时则强制停止
echo "⏳ 优雅停止超时，强制 kill -9 ..."
kill -9 $PIDS 2>/dev/null || true
sleep 1

# 5. 最终确认
if lsof -ti:$PORT >/dev/null 2>&1; then
  echo "❌ 停止失败，请手动检查: lsof -i:$PORT"
  exit 1
else
  echo "✅ 应用已强制停止"
fi
