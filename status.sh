#!/bin/bash
# ============================================================
# G2G Agent 状态检查脚本
# 用法: ./status.sh
# ============================================================
cd "$(dirname "$0")"

PORT=8080

echo "======== G2G Agent 状态 ========"

# 1. 进程状态
PIDS=$(lsof -ti:$PORT 2>/dev/null)
if [ -z "$PIDS" ]; then
  echo "进程:   ❌ 未运行"
  echo "================================="
  exit 1
fi
echo "进程:   ✅ 运行中 (PID: $PIDS)"

# 2. 健康检查
HEALTH=$(curl -s http://localhost:$PORT/actuator/health 2>/dev/null || echo "请求失败")
echo "健康:   $HEALTH"

# 3. 最近启动时间
if ps -p $PIDS -o lstart= 2>/dev/null | read -r START_TIME; then
  echo "启动:   $START_TIME"
fi

echo "================================="
echo "访问:   http://localhost:$PORT/"
echo "日志:   tail -f logs/app.log"
