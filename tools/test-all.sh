#!/bin/bash
# 全模块测试（2026-09-22）
#
# ⚠️ **为什么需要这个脚本**：日常开发习惯用
#     mvn -pl novel_factory-domain -am test
# 而 `-am` 是"也构建依赖模块"——**不执行依赖模块的测试**。
# 于是 infrastructure / trigger / types 的测试长期没被跑到，盲区里积了问题
# （实测 infra 里有两个失败：一个断言过时、一个 Windows symlink 权限限制，谁都不知道）。
#
# 用 `-pl novel_factory-app -am`：app 依赖所有模块，一次覆盖全部。
#
# ⚠️ **还必须加 `--fail-at-end`**。这是实测才发现的真正杀手：
#    只要 domain 里有**一个**测试失败（例如那个长期红的 StoryPropertiesBindingTest），
#    Maven 就**不会继续构建后续模块**，infrastructure 直接显示 `SKIPPED`——
#    所以"infra 的测试从来没跑到"不只是 `-am` 的问题，更是**被 domain 的失败短路了**。
#    `--fail-at-end` 让所有模块都跑完再汇总失败。
#
# 注意 Maven 必须绕开 mvn 外壳（Git Bash 下 `mvn` 会报 classworlds 主类找不到）。
set -e

cd "$(dirname "$0")/.."

MVN_JAR="D:/apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar"
MVN_CONF="D:/apache-maven-3.9.16/bin/m2.conf"
MVN_HOME="D:/apache-maven-3.9.16"
ROOT="$(pwd)"

echo "▶ 全模块测试（novel_factory-app + 全部依赖模块）"
java -Dfile.encoding=UTF-8 \
  -classpath "$MVN_JAR" \
  -Dclassworlds.conf="$MVN_CONF" \
  -Dmaven.home="$MVN_HOME" \
  -Dmaven.multiModuleProjectDirectory="$ROOT" \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  -o -pl novel_factory-app -am --fail-at-end test 2>&1 \
  | grep -E "Tests run:|<<< (FAILURE|ERROR)|BUILD|Reactor Summary" || true

echo ""
echo "ℹ️ 已知的既有失败（非本次改动引入）："
echo "   - StoryPropertiesBindingTest.bindsSceneModelsFromRealYaml（yml 用带日期后缀的模型名，白名单未覆盖）"
echo "   - StoryRepositoryTest.resolve_rejectsSymlinkEscape（Windows 创建符号链接需特权，环境限制）"
