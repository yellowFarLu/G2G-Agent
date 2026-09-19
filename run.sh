#!/bin/bash
# WikiAgent 本地启动脚本
# 用法: ./run.sh   （可选环境变量见 .env.example，复制为 .env 后 source）
set -e
cd "$(dirname "$0")"

# 1. 定位 JDK 21（本机安装于 ~/.local/share/jdks）
JDK_HOME="$(ls -d "$HOME"/.local/share/jdks/jdk-21*.jdk/Contents/Home 2>/dev/null | head -1)"
if [ -z "$JDK_HOME" ]; then
  echo "错误: 未找到 JDK 21，请先安装到 ~/.local/share/jdks/ 或调整本脚本" >&2
  exit 1
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# 2. 定位 Maven 3.9（优先本地安装，避免使用系统旧版 3.6）
MVN="$(ls "$HOME"/.local/share/maven/apache-maven-*/bin/mvn 2>/dev/null | head -1)"
if [ -z "$MVN" ]; then
  MVN="$(command -v mvn || true)"
fi
if [ -z "$MVN" ]; then
  echo "错误: 未找到 mvn" >&2
  exit 1
fi

# 3. 加载环境变量（DASHSCOPE_API_KEY / MILVUS_URI 等）
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi

echo "JAVA_HOME = $JAVA_HOME"
echo "mvn       = $MVN ($("$MVN" -v | head -1))"
echo "API Key   = ${DASHSCOPE_API_KEY:+已配置}${DASHSCOPE_API_KEY:-未配置 (DASHSCOPE_API_KEY)}"

exec "$MVN" spring-boot:run
