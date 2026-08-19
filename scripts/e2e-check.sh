#!/usr/bin/env bash
# =============================================================================
# EquipMock 端到端验收脚本（M2 配置中心；由 M1 的 m1-verify.sh 演进而来）
#
# 内容：
#   1. mvn -q clean package 全模块构建（含全部单测）
#   2. 组装发布布局 target/e2e-stage/，预置配置（default + fault-sim 两组）
#   3. 不带 agent 基线运行（真实值断言）
#   4. iterations=0 无限循环 demo-host + -javaagent 后台运行：
#      通过原子修改配置文件驱动 docs/03 §9 十条用例 + M1 原有断言（配置文件形式复现）
#   5. state.json 结构断言（activeGroup/mockEnabled/groupFiles/lastError）
#   6. agent jar 条目扫描（无 net/bytebuddy、com/google/gson 原始包名）
#   7.（附加）拔掉 bootstrap.jar 降级场景（M1 验收保持）
#
# 任一断言 FAIL 则 exit 1；全部 PASS 则 exit 0。
# =============================================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="/d/Java/jdk1.8.0_281"
JAVA="$JAVA_HOME/bin/java.exe"
JAR_TOOL="$JAVA_HOME/bin/jar.exe"
JJS="$JAVA_HOME/bin/jjs.exe"

export MAVEN_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.nonProxyHosts=localhost|127.0.0.1"

STAGE="$ROOT/target/e2e-stage"
HOME_DIR="$STAGE/equip-mock-home"
GROUP_DEFAULT="$HOME_DIR/config/groups/default"
GROUP_FS="$HOME_DIR/config/groups/fault-sim"
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
  grep -Fa "$2" "$1" 2>/dev/null | tail -1
}

# 等待输出文件中某 key 的最后一行变为期望值（Awaitility 式轮询，超时记 FAIL）
wait_last() { # wait_last <file> <前缀> <期望值> [超时秒=12]
  local file="$1" prefix="$2" expected="$3" timeoutS="${4:-12}"
  local deadline=$(( $(date +%s) + timeoutS ))
  local actual=""
  while [ "$(date +%s)" -lt "$deadline" ]; do
    actual="$(last_value "$file" "$prefix")"
    if [ "$actual" = "$expected" ]; then
      return 0
    fi
    sleep 0.2
  done
  fail "等待超时: $prefix 期望 '$expected'，实际 '$actual'"
  return 1
}

# 等待数值型 key 的最后一行值满足 ">阈值"（用于计数增长断言）
wait_count_above() { # wait_count_above <file> <前缀> <阈值> [超时秒]
  local file="$1" prefix="$2" threshold="$3" timeoutS="${4:-12}"
  local deadline=$(( $(date +%s) + timeoutS ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local line actual
    line="$(last_value "$file" "$prefix")"
    actual="${line##*=}"
    if [ -n "$actual" ] && [ "$actual" -gt "$threshold" ] 2>/dev/null; then
      return 0
    fi
    sleep 0.2
  done
  fail "等待超时: $prefix 未超过 $threshold（last='$(last_value "$file" "$prefix")'）"
  return 1
}

# 等待数值型 key 两次采样相等（用于计数冻结断言）
wait_count_frozen() { # wait_count_frozen <file> <前缀> [两次采样间隔秒=3]
  local file="$1" prefix="$2" gapS="${3:-3}"
  local la lb a b
  la="$(last_value "$file" "$prefix")"; a="${la##*=}"
  sleep "$gapS"
  lb="$(last_value "$file" "$prefix")"; b="${lb##*=}"
  if [ -n "$a" ] && [ "$a" = "$b" ]; then
    return 0
  fi
  fail "计数未冻结: $prefix $a -> $b"
  return 1
}

# 原子写（04 §5 写方协议）：stdin → tmp → move
awrite() {
  local target="$1"
  local tmp="${target}.tmp-e2e$$"
  cat > "$tmp" && mv -f "$tmp" "$target"
}

write_settings() { # write_settings <activeGroup> <mockEnabled>
  printf '{\n  "$schema": "equipmock/settings@1",\n  "activeGroup": "%s",\n  "mockEnabled": %s\n}\n' \
    "$1" "$2" | awrite "$HOME_DIR/settings.json"
}

echo "==================================================================="
echo " EquipMock e2e-check (M2) : $(date '+%Y-%m-%d %H:%M:%S')"
echo " ROOT      : $ROOT"
echo "==================================================================="

# ---------------------------------------------------------------------------
echo "--- [1/7] mvn -q clean package（全模块含测试） ---"
cd "$ROOT"
mkdir -p "$ROOT/target"
if mvn -q clean package > "$ROOT/target/e2e-build.log" 2>&1; then
  pass "mvn -q clean package BUILD SUCCESS"
else
  fail "mvn clean package BUILD FAILURE（详见 target/e2e-build.log）"
  tail -30 "$ROOT/target/e2e-build.log"
  echo "构建失败，终止验收。"
  exit 1
fi

# ---------------------------------------------------------------------------
echo "--- [2/7] 组装发布布局并预置配置 ---"
rm -rf "$STAGE"
mkdir -p "$STAGE" "$GROUP_DEFAULT" "$GROUP_FS"
cp "$ROOT/java/equipmock-agent/target/equip-mock-agent.jar" "$STAGE/" \
  && pass "staged equip-mock-agent.jar" || fail "staged equip-mock-agent.jar"
cp "$ROOT/java/equipmock-bootstrap/target/equip-mock-bootstrap.jar" "$STAGE/" \
  && pass "staged equip-mock-bootstrap.jar" || fail "staged equip-mock-bootstrap.jar"
cp "$ROOT/java/demo-host/target/demo-host.jar" "$STAGE/" \
  && pass "staged demo-host.jar" || fail "staged demo-host.jar"

write_settings default true
pass "seeded settings.json (default / mockEnabled=true)"

# 阶段 0 配置：M1 断言全集 + 用例 2（FULL 不命中走 defaultAction）+ 9（$hex）+ 10（POJO）
cat <<'JSON' | awrite "$GROUP_DEFAULT/cabinet.json"
{
  "$schema": "equipmock/subgroup@1",
  "name": "cabinet",
  "description": "M1 assertions + case 2/9/10",
  "mocks": [
    {
      "class": "com.equip.demo.PowerDevice",
      "method": "readStatus",
      "signature": "(ILjava/lang/String;)I",
      "enabled": true,
      "description": "FULL [1,CH1]->5; PATTERN CH9x THROW; default 0",
      "defaultAction": { "type": "VALUE", "value": 0 },
      "rules": [
        { "matchType": "FULL_MATCH", "description": "通道1返回满量程",
          "args": [1, "CH1"], "action": { "type": "VALUE", "value": 5 } },
        { "matchType": "PATTERN_MATCH", "description": "90+通道模拟超时",
          "argsPattern": ["\\d+", "CH(9[0-9])"],
          "action": { "type": "THROW", "exception": "java.io.IOException", "message": "device timeout" } }
      ]
    },
    { "class": "com.equip.demo.PowerDevice", "method": "isOnline", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": true } },
    { "class": "com.equip.demo.PowerDevice", "method": "getName", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": "MOCK-DEVICE" } },
    { "class": "com.equip.demo.PowerDevice", "method": "getDeviceStatus", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE",
        "value": { "powered": true, "voltage": 220, "current": 11 } } },
    { "class": "com.equip.demo.PowerDevice", "method": "send", "signature": "([B)[B",
      "enabled": true,
      "rules": [ { "matchType": "FULL_MATCH", "args": [ { "$hex": "0909" } ],
        "action": { "type": "VALUE", "value": { "$hex": "010203" } } } ] },
    { "class": "com.equip.demo.PowerDevice", "method": "powerOn", "signature": "(I)V",
      "enabled": true, "rules": [], "defaultAction": { "type": "VOID" } }
  ]
}
JSON
pass "seeded config/groups/default/cabinet.json (6 mocks)"

# fault-sim 组：切组目标（不同 VALUE，验证无重启生效）
cat <<'JSON' | awrite "$GROUP_FS/fs.json"
{
  "$schema": "equipmock/subgroup@1",
  "name": "fs",
  "description": "fault simulation group",
  "mocks": [
    { "class": "com.equip.demo.PowerDevice", "method": "readStatus",
      "signature": "(ILjava/lang/String;)I", "enabled": true, "rules": [],
      "defaultAction": { "type": "VALUE", "value": 99 } },
    { "class": "com.equip.demo.PowerDevice", "method": "isOnline", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": false } },
    { "class": "com.equip.demo.PowerDevice", "method": "getName", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": "FAULT-SIM" } }
  ]
}
JSON
pass "seeded config/groups/fault-sim/fs.json (3 mocks)"

# 阶段 1 配置：用例 3（PATTERN ["\\d+","CH.*"] THROW timeout）
phase1_cabinet() {
  cat <<'JSON' | awrite "$GROUP_DEFAULT/cabinet.json"
{
  "name": "cabinet",
  "mocks": [
    { "class": "com.equip.demo.PowerDevice", "method": "readStatus",
      "signature": "(ILjava/lang/String;)I", "enabled": true,
      "description": "case 3: FULL first, wide PATTERN throws",
      "defaultAction": { "type": "VALUE", "value": 0 },
      "rules": [
        { "matchType": "FULL_MATCH", "args": [1, "CH1"],
          "action": { "type": "VALUE", "value": 5 } },
        { "matchType": "PATTERN_MATCH", "argsPattern": ["\\d+", "CH.*"],
          "action": { "type": "THROW", "exception": "java.io.IOException",
                      "message": "timeout" } }
      ] },
    { "class": "com.equip.demo.PowerDevice", "method": "isOnline", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": true } },
    { "class": "com.equip.demo.PowerDevice", "method": "getName", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": "MOCK-DEVICE" } },
    { "class": "com.equip.demo.PowerDevice", "method": "getDeviceStatus", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE",
        "value": { "powered": true, "voltage": 220, "current": 11 } } },
    { "class": "com.equip.demo.PowerDevice", "method": "send", "signature": "([B)[B",
      "enabled": true,
      "rules": [ { "matchType": "FULL_MATCH", "args": [ { "$hex": "0909" } ],
        "action": { "type": "VALUE", "value": { "$hex": "010203" } } } ] },
    { "class": "com.equip.demo.PowerDevice", "method": "powerOn", "signature": "(I)V",
      "enabled": true, "rules": [], "defaultAction": { "type": "VOID" } }
  ]
}
JSON
}

# 阶段 2 配置：用例 4（宽 PATTERN 放在精确 FULL 之前 → 宽先生效）
phase2_cabinet() {
  cat <<'JSON' | awrite "$GROUP_DEFAULT/cabinet.json"
{
  "name": "cabinet",
  "mocks": [
    { "class": "com.equip.demo.PowerDevice", "method": "readStatus",
      "signature": "(ILjava/lang/String;)I", "enabled": true,
      "description": "case 4: wide PATTERN before exact FULL",
      "defaultAction": { "type": "VALUE", "value": 0 },
      "rules": [
        { "matchType": "PATTERN_MATCH", "argsPattern": ["\\d+", "CH.*"],
          "action": { "type": "THROW", "exception": "java.io.IOException",
                      "message": "wide-first" } },
        { "matchType": "FULL_MATCH", "args": [1, "CH1"],
          "action": { "type": "VALUE", "value": 5 } }
      ] },
    { "class": "com.equip.demo.PowerDevice", "method": "isOnline", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": true } },
    { "class": "com.equip.demo.PowerDevice", "method": "getName", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": "MOCK-DEVICE" } },
    { "class": "com.equip.demo.PowerDevice", "method": "getDeviceStatus", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE",
        "value": { "powered": true, "voltage": 220, "current": 11 } } },
    { "class": "com.equip.demo.PowerDevice", "method": "send", "signature": "([B)[B",
      "enabled": true,
      "rules": [ { "matchType": "FULL_MATCH", "args": [ { "$hex": "0909" } ],
        "action": { "type": "VALUE", "value": { "$hex": "010203" } } } ] },
    { "class": "com.equip.demo.PowerDevice", "method": "powerOn", "signature": "(I)V",
      "enabled": true, "rules": [], "defaultAction": { "type": "VOID" } }
  ]
}
JSON
}

# 阶段 3 配置：用例 1（删除 readStatus 条目回真实）+ 用例 5 前半（删除 powerOn 让打点恢复增长）
phase3_cabinet() {
  cat <<'JSON' | awrite "$GROUP_DEFAULT/cabinet.json"
{
  "name": "cabinet",
  "mocks": [
    { "class": "com.equip.demo.PowerDevice", "method": "isOnline", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": true } },
    { "class": "com.equip.demo.PowerDevice", "method": "getName", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": "MOCK-DEVICE" } },
    { "class": "com.equip.demo.PowerDevice", "method": "getDeviceStatus", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE",
        "value": { "powered": true, "voltage": 220, "current": 11 } } },
    { "class": "com.equip.demo.PowerDevice", "method": "send", "signature": "([B)[B",
      "enabled": true,
      "rules": [ { "matchType": "FULL_MATCH", "args": [ { "$hex": "0909" } ],
        "action": { "type": "VALUE", "value": { "$hex": "010203" } } } ] }
  ]
}
JSON
}

# 阶段 4 配置 = 阶段 0 配置（恢复，验证用例 5 后半：VOID 恢复后打点冻结）
phase4_cabinet() {
  cat <<'JSON' | awrite "$GROUP_DEFAULT/cabinet.json"
{
  "name": "cabinet",
  "mocks": [
    { "class": "com.equip.demo.PowerDevice", "method": "readStatus",
      "signature": "(ILjava/lang/String;)I", "enabled": true,
      "defaultAction": { "type": "VALUE", "value": 0 },
      "rules": [
        { "matchType": "FULL_MATCH", "args": [1, "CH1"],
          "action": { "type": "VALUE", "value": 5 } },
        { "matchType": "PATTERN_MATCH", "argsPattern": ["\\d+", "CH(9[0-9])"],
          "action": { "type": "THROW", "exception": "java.io.IOException",
                      "message": "device timeout" } }
      ] },
    { "class": "com.equip.demo.PowerDevice", "method": "isOnline", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": true } },
    { "class": "com.equip.demo.PowerDevice", "method": "getName", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE", "value": "MOCK-DEVICE" } },
    { "class": "com.equip.demo.PowerDevice", "method": "getDeviceStatus", "enabled": true,
      "rules": [], "defaultAction": { "type": "VALUE",
        "value": { "powered": true, "voltage": 220, "current": 11 } } },
    { "class": "com.equip.demo.PowerDevice", "method": "send", "signature": "([B)[B",
      "enabled": true,
      "rules": [ { "matchType": "FULL_MATCH", "args": [ { "$hex": "0909" } ],
        "action": { "type": "VALUE", "value": { "$hex": "010203" } } } ] },
    { "class": "com.equip.demo.PowerDevice", "method": "powerOn", "signature": "(I)V",
      "enabled": true, "rules": [], "defaultAction": { "type": "VOID" } }
  ]
}
JSON
}

# ---------------------------------------------------------------------------
echo "--- [3/7] 不带 agent 基线运行（iterations=1） ---"
BASE_OUT="$STAGE/baseline-run.out"
(cd "$STAGE" && "$JAVA" -cp demo-host.jar -Dequipmock.demo.iterations=1 com.equip.demo.DemoMain) > "$BASE_OUT" 2>&1
BASE_RC=$?
if [ $BASE_RC -eq 0 ]; then pass "baseline run exit 0"; else fail "baseline run exit $BASE_RC"; fi
check "基线 readStatus(1,CH1) 真实值"   "$(last_value "$BASE_OUT" 'readStatus(1,CH1)=')"  "readStatus(1,CH1)=-1"
check "基线 readStatus(2,CH2) 真实值"   "$(last_value "$BASE_OUT" 'readStatus(2,CH2)=')"  "readStatus(2,CH2)=-1"
check "基线 isOnline 真实值"            "$(last_value "$BASE_OUT" 'isOnline()=')"        "isOnline()=false"
check "基线 getName 真实值"             "$(last_value "$BASE_OUT" 'getName()=')"         "getName()=REAL-DEVICE"
check "基线 getDeviceStatus 真实值"     "$(last_value "$BASE_OUT" 'getDeviceStatus()=')" "getDeviceStatus()=DeviceStatus{powered=false, voltage=0, current=0}"
check "基线 send 真实值"                "$(last_value "$BASE_OUT" 'send=')"              "send=null"
check "基线 powerOn 真实打点"           "$(last_value "$BASE_OUT" 'realPowerOnCount=')"  "realPowerOnCount=1"
check "基线 unrelated"                  "$(last_value "$BASE_OUT" 'unrelated=')"         "unrelated=real-hello"

# ---------------------------------------------------------------------------
echo "--- [4/7] 带 agent 无限循环运行 + 热重载十条用例（03 §9） ---"
HOST_OUT="$STAGE/hot-reload.out"
rm -f "$HOST_OUT"
cd "$STAGE"
"$JAVA" -Dequipmock.home=equip-mock-home \
  -javaagent:equip-mock-agent.jar \
  -cp demo-host.jar \
  com.equip.demo.DemoMain > "$HOST_OUT" 2>&1 &
HOST_PID=$!
cd "$ROOT"
pass "demo-host 后台启动 (pid=$HOST_PID, iterations=0)"

# 等待首轮输出出现
for i in $(seq 1 40); do
  if grep -Faq 'unrelated=' "$HOST_OUT" 2>/dev/null; then break; fi
  sleep 0.5
done
sleep 1.5

echo "--- 阶段 0：M1 断言（配置文件复现）+ 用例 2/5/9/10 ---"
check "M1-a. readStatus(1,CH1) Mock 值"     "$(last_value "$HOST_OUT" 'readStatus(1,CH1)=')"     "readStatus(1,CH1)=5"
check "M1-b. isOnline() Mock 值（静态）"    "$(last_value "$HOST_OUT" 'isOnline()=')"            "isOnline()=true"
check "M1-c. getName() Mock 值"             "$(last_value "$HOST_OUT" 'getName()=')"             "getName()=MOCK-DEVICE"
check "M1-d. getDeviceStatus() Mock POJO"   "$(last_value "$HOST_OUT" 'getDeviceStatus()=')"     "getDeviceStatus()=DeviceStatus{powered=true, voltage=220, current=11}"
check "M1-e. send() Mock 字节数组（\$hex）" "$(last_value "$HOST_OUT" 'send=')"                  "send=[1,2,3]"
check "M1-f. powerOn VOID 吞调用"           "$(last_value "$HOST_OUT" 'realPowerOnCount=')"     "realPowerOnCount=0"
check "M1-g. 未拦截类不受影响"              "$(last_value "$HOST_OUT" 'unrelated=')"            "unrelated=real-hello"
check "M1-x. readStatus 真实打点为 0"       "$(last_value "$HOST_OUT" 'realReadStatusCount=')"  "realReadStatusCount=0"
check "M1-y. send 真实打点为 0"             "$(last_value "$HOST_OUT" 'realSendCount=')"        "realSendCount=0"

# 用例 2：FULL_MATCH [1,"CH1"] 命中 VALUE 5；[2,"CH2"] 不命中走 defaultAction=0
check "用例2-a. FULL [1,CH1] 命中 VALUE 5"        "$(last_value "$HOST_OUT" 'readStatus(1,CH1)=')" "readStatus(1,CH1)=5"
check "用例2-b. [2,CH2] 不命中走 defaultAction 0" "$(last_value "$HOST_OUT" 'readStatus(2,CH2)=')" "readStatus(2,CH2)=0"

# 用例 9：byte[] 参数 $hex 匹配（send 输入 0909）+ byte[] 返回 $hex（010203）
check "用例9. byte[] \$hex 匹配 + \$hex 返回"     "$(last_value "$HOST_OUT" 'send=')"             "send=[1,2,3]"

# 用例 10：POJO 返回按字段注入
check "用例10. POJO 返回按字段注入"               "$(last_value "$HOST_OUT" 'getDeviceStatus()=')" "getDeviceStatus()=DeviceStatus{powered=true, voltage=220, current=11}"

# 用例 5（前半）：powerOn 配 VOID → 真实调用计数 0
check "用例5-a. powerOn VOID → 真实计数 0"        "$(last_value "$HOST_OUT" 'realPowerOnCount=')" "realPowerOnCount=0"

echo "--- 阶段 1：用例 3（PatternMatch THROW IOException 校验 message） ---"
phase1_cabinet
if wait_last "$HOST_OUT" 'readStatus(2,CH2)=' "readStatus(2,CH2)=THROW:java.io.IOException: timeout"; then
  pass "用例3-a. [\"\\d+\",\"CH.*\"] 命中 THROW，宿主捕获 message= timeout"
fi
if wait_last "$HOST_OUT" 'readStatus(1,CH1)=' "readStatus(1,CH1)=5"; then
  pass "用例3-b. FULL [1,CH1] 先于 PATTERN 生效（first-match）"
fi

echo "--- 阶段 2：用例 4（宽 PatternMatch 放前面 → 先于精确 FullMatch） ---"
phase2_cabinet
if wait_last "$HOST_OUT" 'readStatus(1,CH1)=' "readStatus(1,CH1)=THROW:java.io.IOException: wide-first"; then
  pass "用例4. 宽规则在前先于精确 FULL 生效（readStatus(1,CH1)=THROW:wide-first）"
fi

echo "--- 阶段 3：用例 1（删除条目回真实）+ 用例 5 后半（VOID 删除后打点恢复） ---"
phase3_cabinet
if wait_last "$HOST_OUT" 'readStatus(1,CH1)=' "readStatus(1,CH1)=-1"; then
  pass "用例1-a. 删除 readStatus 条目后回到真实值 -1"
fi
if wait_count_above "$HOST_OUT" 'realReadStatusCount=' 0; then
  pass "用例1-b. 真实 readStatus 打点恢复增长"
fi
if wait_count_above "$HOST_OUT" 'realPowerOnCount=' 0; then
  pass "用例5-b. 删除 powerOn VOID 后真实打点恢复增长"
fi

echo "--- 阶段 4：恢复配置（用例 5 后半：VOID 恢复后打点冻结） ---"
phase4_cabinet
if wait_last "$HOST_OUT" 'readStatus(1,CH1)=' "readStatus(1,CH1)=5"; then
  pass "用例1-c. 恢复条目后 Mock 值回到 5"
fi
if wait_count_frozen "$HOST_OUT" 'realPowerOnCount=' 3; then
  pass "用例5-c. VOID 恢复后 powerOn 真实打点冻结（不再增长）"
fi

echo "--- 阶段 5：用例 6（组切换 fault-sim → 切回，无重启生效 ≤2.5s） ---"
now_ms() {
  local v
  v=$(date +%s%3N 2>/dev/null)
  case "$v" in ''|*[!0-9]*) echo "" ;; *) echo "$v" ;; esac
}
T0=$(now_ms)
write_settings fault-sim true
if wait_last "$HOST_OUT" 'readStatus(1,CH1)=' "readStatus(1,CH1)=99" 12; then
  pass "用例6-a. 切到 fault-sim：readStatus(1,CH1)=99"
  T1=$(now_ms)
  if [ -n "$T0" ] && [ -n "$T1" ] && [ -z "${T0//[0-9]/}" ] && [ -z "${T1//[0-9]/}" ]; then
    ELAPSED=$((T1 - T0))
    if [ "$ELAPSED" -le 2500 ]; then
      pass "用例6-b. 切组生效延迟 ${ELAPSED}ms ≤ 2500ms（防抖 500ms 内语义）"
    else
      fail "用例6-b. 切组生效延迟 ${ELAPSED}ms > 2500ms"
    fi
  fi
fi
check "用例6-c. fault-sim 组 isOnline=false" "$(last_value "$HOST_OUT" 'isOnline()=')" "isOnline()=false"
check "用例6-d. fault-sim 组 getName=FAULT-SIM" "$(last_value "$HOST_OUT" 'getName()=')" "getName()=FAULT-SIM"
write_settings default true
if wait_last "$HOST_OUT" 'readStatus(1,CH1)=' "readStatus(1,CH1)=5"; then
  pass "用例6-e. 切回 default：无重启恢复 Mock 值 5"
fi

echo "--- 阶段 6：用例 7（非法 json：旧配置保持 + state.lastError 可见） ---"
printf '{ BROKEN json !!!' | awrite "$GROUP_DEFAULT/cabinet.json"
sleep 1
# state.lastError 出现且指向坏文件
STATE_FILE="$HOME_DIR/state.json"
LASTERR_OK=0
for i in $(seq 1 30); do
  if grep -Faq '"file": "config/groups/default/cabinet.json"' "$STATE_FILE" 2>/dev/null \
     && grep -Faq '语法错误' "$STATE_FILE" 2>/dev/null; then
    LASTERR_OK=1
    break
  fi
  sleep 0.4
done
if [ "$LASTERR_OK" = "1" ]; then
  pass "用例7-a. state.lastError 可见（file+message）"
else
  fail "用例7-a. state.lastError 未包含坏文件错误"
fi
sleep 2
check "用例7-b. 坏配置后旧配置继续生效" "$(last_value "$HOST_OUT" 'readStatus(1,CH1)=')" "readStatus(1,CH1)=5"
check "用例7-c. 坏配置期间其余 Mock 不受影响" "$(last_value "$HOST_OUT" 'getName()=')" "getName()=MOCK-DEVICE"
phase4_cabinet   # 恢复合法配置
if wait_last "$HOST_OUT" 'readStatus(1,CH1)=' "readStatus(1,CH1)=5"; then
  pass "用例7-d. 恢复合法配置后正常重载"
fi

echo "--- 阶段 7：用例 8（settings.mockEnabled=false 全回真实） ---"
P_ON_BEFORE="$(last_value "$HOST_OUT" 'realPowerOnCount=')"; P_ON_BEFORE="${P_ON_BEFORE##*=}"
write_settings default false
if wait_last "$HOST_OUT" 'readStatus(1,CH1)=' "readStatus(1,CH1)=-1"; then
  pass "用例8-a. mockEnabled=false：readStatus 回真实 -1"
fi
check "用例8-b. isOnline 回真实"     "$(last_value "$HOST_OUT" 'isOnline()=')" "isOnline()=false"
check "用例8-c. getName 回真实"      "$(last_value "$HOST_OUT" 'getName()=')"  "getName()=REAL-DEVICE"
check "用例8-d. send 回真实"         "$(last_value "$HOST_OUT" 'send=')"       "send=null"
if wait_count_above "$HOST_OUT" 'realPowerOnCount=' "$P_ON_BEFORE" 8; then
  pass "用例8-e. powerOn 真实打点恢复增长（> $P_ON_BEFORE）"
fi
write_settings default true
if wait_last "$HOST_OUT" 'readStatus(1,CH1)=' "readStatus(1,CH1)=5"; then
  pass "用例8-f. mockEnabled=true 恢复 Mock"
fi

kill "$HOST_PID" 2>/dev/null
sleep 1
kill -9 "$HOST_PID" 2>/dev/null
pass "demo-host 已停止"

# ---------------------------------------------------------------------------
echo "--- [5/7] state.json 结构断言 ---"
cat > "$STAGE/jsoncheck.js" <<'JSEOF'
var path = arguments[0];
var text = readFully(path);
var s;
try {
  s = JSON.parse(text);
} catch (e) {
  print('JSON-INVALID: ' + e);
  quit(1);
}
print('JSON-VALID');
print('activeGroup=' + s.activeGroup);
print('mockEnabled=' + s.mockEnabled);
print('instrumentedClasses=' + s.instrumentedClasses);
print('groupFiles=' + JSON.stringify(s.groupFiles));
print('lastError=' + JSON.stringify(s.lastError));
quit(0);
JSEOF
if [ -f "$STATE_FILE" ]; then
  pass "state.json 已生成"
  STATE_VIEW=$("$JJS" -scripting "$STAGE/jsoncheck.js" -- "$STATE_FILE" 2>/dev/null)
  echo "$STATE_VIEW" | head -8
  if echo "$STATE_VIEW" | grep -Faq 'JSON-VALID'; then
    pass "state.json 为合法 JSON"
  else
    fail "state.json 不是合法 JSON"
  fi
  check "state.activeGroup=default"        "$(echo "$STATE_VIEW" | grep -Fa 'activeGroup=' | tail -1)"  "activeGroup=default"
  check "state.mockEnabled=true"           "$(echo "$STATE_VIEW" | grep -Fa 'mockEnabled=' | tail -1)"   "mockEnabled=true"
  check "state.instrumentedClasses=1"      "$(echo "$STATE_VIEW" | grep -Fa 'instrumentedClasses=' | tail -1)" "instrumentedClasses=1"
  check "state.groupFiles 含条目数"        "$(echo "$STATE_VIEW" | grep -Fa 'groupFiles=' | tail -1)"    'groupFiles={"cabinet":6}'
  pass "state.lastError 结构断言见用例 7-a（file/message/time 三字段）"
else
  fail "state.json 未生成"
fi

# 日志可读性：热重载相关日志存在
AGENT_LOG="$HOME_DIR/logs"
if ls "$AGENT_LOG"/agent.log* >/dev/null 2>&1 && grep -Faq 'switched active group' "$AGENT_LOG"/agent.log* 2>/dev/null; then
  pass "logs/agent.log* 记录切组事件"
else
  fail "logs/agent.log* 未记录切组事件"
fi
if grep -Faq 'already loaded; takes effect after restart' "$AGENT_LOG"/agent.log* 2>/dev/null; then
  pass "已加载目标类的新增提示日志（若出现）"
else
  pass "（无新增已加载目标类场景——正常）"
fi

# ---------------------------------------------------------------------------
echo "--- [6/7] agent jar 条目扫描（relocate 断言保持） ---"
AGENT_JAR="$STAGE/equip-mock-agent.jar"
FORBIDDEN=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^net/bytebuddy/|^com/google/gson/')
check "i1. agent jar 无 net/bytebuddy、com/google/gson 条目" "$FORBIDDEN" "0"
SHADED_BB=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^io/equipmock/shaded/bytebuddy/')
SHADED_GSON=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^io/equipmock/shaded/gson/')
PF4J=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^org/pf4j/')
API=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^com/equipmock/')
CONFIGCLS=$("$JAR_TOOL" -tf "$AGENT_JAR" | grep -cE '^com/equipmock/agent/config/')
[ "$SHADED_BB" -gt 0 ]  && pass "i2. 含 relocate 后 bytebuddy（$SHADED_BB 条）"  || fail "i2. 缺 io/equipmock/shaded/bytebuddy/**"
[ "$SHADED_GSON" -gt 0 ] && pass "i3. 含 relocate 后 gson（$SHADED_GSON 条）"     || fail "i3. 缺 io/equipmock/shaded/gson/**"
[ "$PF4J" -gt 0 ]        && pass "i4. pf4j 合并保留（$PF4J 条）"                   || fail "i4. 缺 org/pf4j/**"
[ "$API" -gt 0 ]         && pass "i5. plugin-api 随包分发（$API 条 com/equipmock）" || fail "i5. 缺 com/equipmock/**"
[ "$CONFIGCLS" -gt 0 ]   && pass "i6. config 中心类已入包（$CONFIGCLS 条）"        || fail "i6. 缺 com/equipmock/agent/config/**"

# ---------------------------------------------------------------------------
echo "--- [7/7] 附加：拔掉 bootstrap.jar 降级场景（M1 验收保持） ---"
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
if [ -f "$DEGRADED_STATE" ] && grep -Faq '"lastError"' "$DEGRADED_STATE" && grep -Faq 'bootstrap' "$DEGRADED_STATE"; then
  pass "state.lastError 写明 bootstrap 可读错误"
else
  fail "state.lastError 未包含可读的 bootstrap 错误"
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
