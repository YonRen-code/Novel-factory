#!/bin/bash
# 前置：先在 novel_factory-app 目录执行 build.sh 构建镜像，并把密钥放进环境变量：
#   export LLM_API_KEY=sk-xxx
#   export LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode   # 可选，不设则用默认地址
CONTAINER_NAME=novel_factory
IMAGE_NAME=system/novel_factory-app:1.0-SNAPSHOT
PORT=8080
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

if [ -z "${LLM_API_KEY}" ]; then
  echo "警告：未设置 LLM_API_KEY，容器能启动但生成作业会因缺少密钥失败"
fi

echo "容器部署开始 ${CONTAINER_NAME}"

# 停止并删除旧容器（首次部署时不存在，失败可忽略）
docker stop ${CONTAINER_NAME} 2>/dev/null
docker rm ${CONTAINER_NAME} 2>/dev/null

# 启动容器。这里用不带等号的 -e：宿主机没设该变量时容器内也不设，
# 从而回落到 novel-generation.yml 的默认值；写成 -e VAR="" 反而会传空串把默认值盖掉。
# 挂载两个数据目录：不挂的话容器一删，正文/三账本/检查点全部丢失。
docker run --name ${CONTAINER_NAME} \
-p ${PORT}:${PORT} \
-e LLM_API_KEY \
-e LLM_BASE_URL \
-v "${REPO_ROOT}/data:/app/data" \
-v "${REPO_ROOT}/docs/workspace:/app/docs/workspace" \
--restart unless-stopped \
-d ${IMAGE_NAME}

echo "容器部署成功 ${CONTAINER_NAME}"
echo "工作台：http://localhost:${PORT}"

docker logs -f ${CONTAINER_NAME}
