#!/bin/bash
# 本地开发启动脚本（2026-09-22）
#
# 把两次实际踩过的坑固化成前置动作：
#
# 1) **必须清空代理环境变量**。终端/沙箱可能注入 http_proxy 指向一个不可用的代理，
#    而 chat 调用走 Spring AI 默认客户端、**会读环境变量代理** → `Network is unreachable`；
#    embedding 因为用了自定义 requestFactory **不读**，于是表现为"半通半不通"，极易误判成代码问题。
#    本项目用的 dashscope 是国内服务，直连即可。
#
# 2) **Qdrant 必须在跑**。参考资料向量写入是**硬依赖**（不降级 LLM 选择，失败即终止作业），
#    缺了它跑批会在第一步就 FAILED。
set -e

cd "$(dirname "$0")/.."

unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY no_proxy NO_PROXY
echo "✅ 已清空代理环境变量（dashscope 直连）"

JAR="novel_factory-app/target/novel_factory-app.jar"
if [ ! -f "$JAR" ]; then
  echo "❌ 未找到 $JAR"
  echo "   请先打包：mvn -DskipTests package   （注意 yml 改动必须重新打包才会生效）"
  exit 1
fi

if curl -s -m 5 --noproxy '*' http://127.0.0.1:6333/collections 2>/dev/null | grep -q '"status":"ok"'; then
  echo "✅ Qdrant 就绪"
else
  echo "❌ Qdrant 未就绪（127.0.0.1:6333）"
  echo "   参考资料向量写入是硬依赖，缺了它跑批会立即终止。"
  exit 1
fi
# ⚠️ 不要用 `curl -o /dev/null` 判断可用性：Git Bash 下它会返回 exit 23（CURLE_WRITE_ERROR），
#    把"服务正常"误判成"未就绪"——实测踩过（同一条命令加 -w 就返回 HTTP 200）。
#    改用响应体判断最稳（不写文件、不依赖 exit code）。

echo "🚀 启动服务（profile=dev，端口 8080）..."
exec java -Dfile.encoding=UTF-8 -jar "$JAR" --spring.profiles.active=dev
