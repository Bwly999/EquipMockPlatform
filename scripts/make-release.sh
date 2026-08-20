#!/usr/bin/env bash
# EquipMock Java 侧发布打包（M7-1）：从各模块 target 组装发布目录并压 zip，随后冒烟验证。
# 前置：先在仓库根执行过 mvn clean verify（JDK8）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REL="$ROOT/target/release/equip-mock"
OUT_ZIP="$ROOT/target/release"

AGENT_JAR="$ROOT/java/equipmock-agent/target/equip-mock-agent.jar"
BOOT_JAR="$ROOT/java/equipmock-bootstrap/target/equip-mock-bootstrap.jar"
CAB_JAR="$(ls "$ROOT"/java/plugins/plugin-mock-cabinet/target/mock-cabinet-*.jar 2>/dev/null | head -1 || true)"
RAD_JAR="$(ls "$ROOT"/java/plugins/plugin-mock-radar/target/mock-radar-*.jar 2>/dev/null | head -1 || true)"
HOST_JAR="$ROOT/java/demo-host/target/demo-host.jar"
KIT_JAR="$ROOT/java/equipmock-testkit/target/equipmock-testkit-*.jar"
API_JAR="$ROOT/java/equipmock-plugin-api/target/equipmock-plugin-api-*.jar"

for f in "$AGENT_JAR" "$BOOT_JAR" "$CAB_JAR" "$RAD_JAR" "$HOST_JAR"; do
  [ -f "$f" ] || { echo "[FAIL] 缺少构建产物：$f（先在根目录执行 mvn clean verify）"; exit 1; }
done

VER="$(ls "$ROOT"/java/plugins/plugin-mock-cabinet/target/ | sed -n 's/^mock-cabinet-\(.*\)\.jar$/\1/p' | head -1)"
echo "[1/4] 版本：$VER ；清理 $REL"
rm -rf "$REL"
mkdir -p "$REL/plugins" "$REL/config/groups/default" "$REL/examples" "$REL/dev"

echo "[2/4] 拷贝构件与示例配置"
cp "$AGENT_JAR" "$REL/equip-mock-agent.jar"
cp "$BOOT_JAR" "$REL/equip-mock-bootstrap.jar"
cp "$CAB_JAR" "$REL/plugins/"
cp "$RAD_JAR" "$REL/plugins/"
cp "$HOST_JAR" "$REL/examples/demo-host.jar"
cp "$ROOT"/java/equipmock-testkit/target/equipmock-testkit-*.jar "$REL/dev/" 2>/dev/null || true
cp "$ROOT"/java/equipmock-plugin-api/target/equipmock-plugin-api-*.jar "$REL/dev/" 2>/dev/null || true
cp "$ROOT/scripts/release/README-接入.md" "$REL/"
cp "$ROOT/scripts/release/run-demo.bat" "$REL/"
# run-demo.bat 转为 CRLF，保证记事本/双击兼容
if command -v unix2dos >/dev/null 2>&1; then unix2dos -q "$REL/run-demo.bat" 2>/dev/null || true; fi
cp "$ROOT/scripts/release/cabinet.json" "$REL/config/groups/default/"
cp "$ROOT/scripts/release/radar.json" "$REL/config/groups/default/"
echo "$VER" > "$REL/VERSION"

CAB_NAME="$(basename "$CAB_JAR")"
RAD_NAME="$(basename "$RAD_JAR")"
cat > "$REL/plugins/plugin-registry.json" <<EOF
{
  "\$schema": "equipmock/plugin-registry@1",
  "plugins": [
    { "id": "mock-cabinet", "alias": "机柜电源Mock", "jar": "$CAB_NAME", "enabled": true,
      "note": "示例插件：powerOn 写死 VOID；readStatus busy 时抛 IOException，其余落配置" },
    { "id": "mock-radar", "alias": "雷达伺服Mock", "jar": "$RAD_NAME", "enabled": true,
      "note": "示例插件：getAzimuth 写死 123.45；track 纯配置" }
  ]
}
EOF

echo "[3/4] 压缩 zip"
powershell.exe -NoProfile -Command "Compress-Archive -Path '$(cygpath -w "$REL" | sed 's/\\/\\\\/g')\\*' -DestinationPath '$(cygpath -w "$OUT_ZIP" | sed 's/\\/\\\\/g')\\equip-mock-$VER.zip' -Force" >/dev/null
echo "      产物：$OUT_ZIP/equip-mock-$VER.zip"

echo "[4/4] 冒烟验证（发布树直跑示例宿主）"
RUN="$REL"
OUT="$(JAVA_HOME="${JAVA_HOME:-/d/Java/jdk1.8.0_281}" "$JAVA_HOME/bin/java.exe" \
  -Dequipmock.home="$RUN" -javaagent:"$RUN/equip-mock-agent.jar" \
  -cp "$RUN/examples/demo-host.jar" -Dequipmock.demo.iterations=2 com.equip.demo.DemoMain 2>&1 || true)"
pass=0; fail=0
check() { if echo "$OUT" | grep -q "$1"; then echo "[PASS] $2"; pass=$((pass+1)); else echo "[FAIL] $2 —— 输出片段：$(echo "$OUT" | tail -5 | tr '\n' ' ')"; fail=$((fail+1)); fi; }
check "readStatus(1,CH1)=5"            "readStatus FULL_MATCH=5"
check "readStatus(2,CH2)=0"            "readStatus 不命中走 defaultAction=0"
check "getName()=MOCK-DEVICE"          "getName 配置值"
check "powered=true, voltage=220"      "POJO 字段注入"
check "send=\[1,2,3\]"                 "byte[] \$hex 返回"
check "realPowerOnCount=0"             "powerOn VOID（插件写死）"
check "unrelated=real-hello"           "未拦截方法原样执行"
if [ -s "$RUN/state.json" ] && grep -q '"activeGroup"' "$RUN/state.json"; then echo "[PASS] state.json 生成"; pass=$((pass+1)); else echo "[FAIL] state.json"; fail=$((fail+1)); fi
echo "冒烟结果: PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ] || exit 1
echo "完成：$OUT_ZIP/equip-mock-$VER.zip"
