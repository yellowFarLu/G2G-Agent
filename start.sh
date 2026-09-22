#!/bin/bash
# ============================================================
# G2G Agent 后台启动脚本
# 用法: ./start.sh
# 特性: nohup 后台运行，关闭终端仍可正常运行
# ============================================================
set -e
cd "$(dirname "$0")"

PORT=8080
LOG_DIR="logs"
LOG_FILE="$LOG_DIR/app.log"
ENV_FILE=".env"

# 1. 检查 .env 是否存在
if [ ! -f "$ENV_FILE" ]; then
  echo "⚠️  未找到 .env 文件，请先复制 .env.example 为 .env 并填入真实配置"
  echo "   cp .env.example .env && vi .env"
  exit 1
fi

# 2. 检查端口是否已被占用
if lsof -ti:$PORT >/dev/null 2>&1; then
  EXISTING_PID=$(lsof -ti:$PORT | head -1)
  echo "⚠️  端口 $PORT 已被占用 (PID: $EXISTING_PID)，应用可能已在运行"
  echo "   如需重启，请先执行 ./stop.sh"
  exit 0
fi

# 3. 确保日志目录存在
mkdir -p "$LOG_DIR"

# 4. 使用 nohup 后台启动（关闭终端不影响进程）
echo "正在后台启动 G2G Agent..."
nohup bash run.sh > "$LOG_FILE" 2>&1 &
PID=$!
disown $PID 2>/dev/null || true

echo "进程 PID: $PID"
echo "日志文件: $LOG_FILE"
echo "等待应用就绪（最多 90 秒）..."

# 5. 轮询健康检查
for i in $(seq 1 90); do
  HEALTH=$(curl -s http://localhost:$PORT/actuator/health 2>/dev/null || true)
  if echo "$HEALTH" | grep -q '"status":"UP"'; then
    echo ""
    echo "✅ 启动成功！"
    echo "   访问地址: http://localhost:$PORT/"
    echo "   健康检查: $HEALTH"
    echo "   查看日志: tail -f $LOG_FILE"
    exit 0
  fi
  # 进度提示
  if [ $((i % 10)) -eq 0 ]; then
    echo "  ...已等待 ${i}s"
  fi
  sleep 1
done

echo ""
echo "⚠️  启动超时（90s），请检查日志: tail -f $LOG_FILE"
exit 1
