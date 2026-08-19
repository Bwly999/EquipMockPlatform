#!/usr/bin/env bash
# =============================================================================
# EquipMock M1 验收脚本（Git Bash / Windows）
#
# 内容：
#   1. mvn -q clean package 全模块构建（含测试）
#   2. 组装发布布局 target/m1-stage/
#   3. demo-host + agent 运行（iterations=2）逐项断言 a–i
#   4. demo-host 不带 agent 基线运行（iterations=1）断言真实值
#   5.（附加）拔掉 bootstrap.jar 的降级场景：不阻断宿主、报错可读（M1 验收补充项）
#
# 任一断言 FAIL 则 exit 1；全部 PASS 则 exit 0。
# =============================================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="/d/Java/jdk1.8.0_281"
JAVA="$JAVA_HOME/bin/java.exe"
JAR_TOOL="$JAVA_HOME/bin/jar.exe"
JJS="$JAVA_HOME/bin/jjs.exe"

# 本机 Maven 需走系统代理下载缺失插件（已缓存则不触发网络）；
# JVM 不读 http_proxy 环境变量，须显式传入
export MAVEN_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.nonProxyHosts=localhost|127.0.0.1"

STAGE="$ROOT/target/m1-stage"
PASS_COUNT=0
FAIL_COUNT=0

pass() { PASS_COUNT=$((PASS_COUNT + 1)); echo "[PASS] $*"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); echo "[FAIL] $*"; }
check() { # check <描述> <实际值> <期望值>
  if [ "$2" = "$3" ]; then
    pass "$1"
  else
    fail "$1 (actual='$2' expected='$3')"
  fi
}

# 取输出文件中某 key 的最后一行值：last_value <file> <前缀>
last_value() {
  grep -F "$2" "$1" | tail -1
}

echo "==================================================================="
echo " M1-verify : $(date '+%Y-%m-%d %H:%M:%S')"
echo " ROOT      : $ROOT"
echo "==================================================================="

# ---------------------------------------------------------------------------
echo "--- [1/5] mvn -q clean package（全模块含测试） ---"
cd "$ROOT"
mkdir -p "$ROOT/target"
if mvn -q clean package > "$ROOT/target/m1-build.log" 2>&1; then
  pass "mvn clean package BUILD SUCCESS"
else
  fail "mvn clean package BUILD FAILURE（详见 target/m1-build.log）"
  tail -30 "$ROOT/target/m1-build.log"
  echo "构建失败，终止验收。"
  exit 1
fi

# ---------------------------------------------------------------------------
echo "--- [2/5] 组装发布布局 target/m1-stage/ ---"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$ROOT/java/equipmock-agent/target/equip-mock-agent.jar" "$STAGE/" \
  && pass "staged equip-mock-agent.jar" || fail "staged equip-mock-agent.jar"
cp "$ROOT/java/equipmock-bootstrap/target/equip-mock-bootstrap.jar" "$STAGE/" \
  && pass "staged equip-mock-bootstrap.jar" || fail "staged equip-mock-bootstrap.jar"
cp "$ROOT/java/demo-host/target/demo-host.jar" "$STAGE/" \
  && pass "staged demo-host.jar" || fail "staged demo-host.jar"

# ---------------------------------------------------------------------------
echo "--- [3/5] 带 agent 运行 demo-host（iterations=2） ---"
AGENT_OUT="$STAGE/agent-run.out"
rm -rf "$STAGE/equip-mock-home"
(
  cd "$STAGE"
  "$JAVA" -Dequipmock.home=equip-mock-home \
    -javaagent:equip-mock-agent.jar \
    -cp demo-host.jar \
    -Dequipmock.demo.iterations=2 \
    com.equip.demo.DemoMain
) > "$AGENT_OUT" 2>&1
AGENT_RC=$?
echo "--- agent run output ---"
cat "$AGENT_OUT"
echo "--- end output (exit=$AGENT_RC) ---"
if [ $AGENT_RC -eq 0 ]; then pass "agent run exit 0"; else fail "agent run exit $AGENT_RC"; fi

check "a. readStatus Mock 值"            "$(last_value "$AGENT_OUT" 'readStatus(1,CH1)=')" "readStatus(1,CH1)=5"
check "b. isOnline Mock 值（静态方法）"  "$(last_value "$AGENT_OUT" 'isOnline()=')"        "isOnline()=true"
check "c. getName Mock 值"               "$(last_value "$AGENT_OUT" 'getName()=')"         "getName()=MOCK-DEVICE"
check "d. getDeviceStatus Mock POJO"     "$(last_value "$AGENT_OUT" 'getDeviceStatus()=')" "getDeviceStatus()=DeviceStatus{powered=true, voltage=220, current=11}"
check "e. send Mock 字节数组"            "$(last_value "$AGENT_OUT" 'send=')"             "send=[1,2,3]"
check "f. powerOn VOID 吞调用"           "$(last_value "$AGENT_OUT" 'realPowerOnCount=')" "realPowerOnCount=0"
check "g. 未拦截类不受影响"              "$(last_value "$AGENT_OUT" 'unrelated=')"        "unrelated=real-hello"

# h. state.json 生成且 JSON 合法
STATE_FILE="$STAGE/equip-mock-home/state.json"
if [ -f "$STATE_FILE" ]; then
  pass "h1. state.json 已生成"
  cat > "$STAGE/jsoncheck.js" <<'JSEOF'
var path = arguments[0];
var text = readFully(path);
try {
  JSON.parse(text);
  print('JSON-VALID');
} catch (e) {
  print('JSON-INVALID: ' + e);
  quit(1);
}
JSEOF
  if "$JJS" -scripting "$STAGE/jsoncheck.js" -- "$STATE_FILE" 2>/dev/null | grep -q 'JSON-VALID'; then
    pass "h2. state.json 为合法 JSON"
  else
    fail "h2. state.json 不是合法 JSON"
  fi
  if grep -q '"agentVersion"' "$STATE_FILE" && grep -q '"lastError"' "$STATE_FILE"; then
    pass "h3. state.json 含 agentVersion/lastError 字段"
  else
    fail "h3. state.json 缺 agentVersion 或 lastError 字段"
  fi
else
  fail "h1. state.json 未生成"; fail "h2. state.json 为合法 JSON"; fail "h3. state.json 字段"
fi

# i. agent jar 无未 relocate 的三方包条目
AGENT_JAR="$STAGE/equip-mock-agent.jar"
FORBIDDEN=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^net/bytebuddy/|^com/google/gson/')
check "i1. agent jar 无 net/bytebuddy、com/google/gson 条目" "$FORBIDDEN" "0"
SHADED_BB=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^io/equipmock/shaded/bytebuddy/')
SHADED_GSON=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^io/equipmock/shaded/gson/')
PF4J=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^org/pf4j/')
API=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^com/equipmock/')
[ "$SHADED_BB" -gt 0 ] && pass "i2. 含 relocate 后 bytebuddy（$SHADED_BB 条）" || fail "i2. 缺 io/equipmock/shaded/bytebuddy/**"
[ "$SHADED_GSON" -gt 0 ] && pass "i3. 含 relocate 后 gson（$SHADED_GSON 条）" || fail "i3. 缺 io/equipmock/shaded/gson/**"
[ "$PF4J" -gt 0 ] && pass "i4. pf4j 合并保留（$PF4J 条，02 §7 不 relocate）" || fail "i4. 缺 org/pf4j/**"
[ "$API" -gt 0 ] && pass "i5. plugin-api 随包分发（$API 条 com/equipmock）" || fail "i5. 缺 com/equipmock/**"

# ---------------------------------------------------------------------------
echo "--- [4/5] 不带 agent 基线运行（iterations=1） ---"
BASE_OUT="$STAGE/baseline-run.out"
(cd "$STAGE" && "$JAVA" -cp demo-host.jar -Dequipmock.demo.iterations=1 com.equip.demo.DemoMain) > "$BASE_OUT" 2>&1
BASE_RC=$?
if [ $BASE_RC -eq 0 ]; then pass "baseline run exit 0"; else fail "baseline run exit $BASE_RC"; fi
check "基线 readStatus 真实值"        "$(last_value "$BASE_OUT" 'readStatus(1,CH1)=')"  "readStatus(1,CH1)=-1"
check "基线 isOnline 真实值"          "$(last_value "$BASE_OUT" 'isOnline()=')"        "isOnline()=false"
check "基线 getName 真实值"           "$(last_value "$BASE_OUT" 'getName()=')"         "getName()=REAL-DEVICE"
check "基线 getDeviceStatus 真实值"   "$(last_value "$BASE_OUT" 'getDeviceStatus()=')" "getDeviceStatus()=DeviceStatus{powered=false, voltage=0, current=0}"
check "基线 send 真实值"              "$(last_value "$BASE_OUT" 'send=')"              "send=null"
check "基线 powerOn 真实打点"         "$(last_value "$BASE_OUT" 'realPowerOnCount=')"  "realPowerOnCount=1"
check "基线 unrelated"                "$(last_value "$BASE_OUT" 'unrelated=')"         "unrelated=real-hello"

# ---------------------------------------------------------------------------
echo "--- [5/5] 附加：拔掉 bootstrap.jar 降级场景（M1 验收补充项） ---"
NOBOOT="$STAGE/no-bootstrap"
mkdir -p "$NOBOOT"
cp "$STAGE/equip-mock-agent.jar" "$NOBOOT/"
cp "$STAGE/demo-host.jar" "$NOBOOT/"
DEGRADED_OUT="$NOBOOT/degraded-run.out"
rm -rf "$NOBOOT/equip-mock-home"
(cd "$NOBOOT" && "$JAVA" -Dequipmock.home=equip-mock-home -javaagent:equip-mock-agent.jar \
  -cp demo-host.jar -Dequipmock.demo.iterations=1 com.equip.demo.DemoMain) > "$DEGRADED_OUT" 2>&1
DEGRADED_RC=$?
if [ $DEGRADED_RC -eq 0 ]; then
  pass "拔 bootstrap 后宿主仍正常退出（exit 0，未阻断）"
else
  fail "拔 bootstrap 后宿主异常退出 exit=$DEGRADED_RC"
fi
check "拔 bootstrap 后降级为真实值" "$(last_value "$DEGRADED_OUT" 'readStatus(1,CH1)=')" "readStatus(1,CH1)=-1"
DEGRADED_STATE="$NOBOOT/equip-mock-home/state.json"
if [ -f "$DEGRADED_STATE" ] && grep -q '"lastError"' "$DEGRADED_STATE" && grep -qi 'bootstrap' "$DEGRADED_STATE"; then
  pass "state.lastError 写明 bootstrap 可读错误"
else
  fail "state.lastError 未包含可读的 bootstrap 错误"
fi
DEGRADED_LOG="$NOBOOT/equip-mock-home/logs/agent.log.0"
# 说明：JUL FileHandler 在 count>1 且 pattern 无 %g 时对全部文件追加代号（agent.log.0..4），
# 故当前日志文件为 agent.log.0（02 §3 "写 logs/agent.log" 的 JUL 标准落地形式）
if [ -f "$DEGRADED_LOG" ] && grep -qi 'bootstrap' "$DEGRADED_LOG"; then
  pass "logs/agent.log.0 记录 bootstrap 缺失错误（信息可读）"
else
  fail "logs/agent.log.0 未记录 bootstrap 缺失错误"
fi

# ---------------------------------------------------------------------------
echo "==================================================================="
echo " 结果汇总: PASS=$PASS_COUNT FAIL=$FAIL_COUNT"
echo "==================================================================="
if [ $FAIL_COUNT -eq 0 ]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "THERE ARE FAILURES"
  exit 1
fi
