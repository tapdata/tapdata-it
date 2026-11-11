#!/usr/bin/env bash
# =============================================================================
# L3 Golden Path — 远程构建 + 部署 + 执行脚本
# =============================================================================
# 用法:
#   ./scripts/l3-remote.sh                      # 完整流程: build → deploy → run
#   ./scripts/l3-remote.sh build                # 只本地 rebuild fat jar
#   ./scripts/l3-remote.sh deploy               # 只传 fat jar + cases + m2 repo
#   ./scripts/l3-remote.sh run                  # 只远程执行已有制品
#   ./scripts/l3-remote.sh run --case L3-SMOKE  # 指定 case_id
#   ./scripts/l3-remote.sh validate              # 远程跑 validate（不执行 step）
#
# 可选环境变量:
#   CASE_ID=L3-GP-MYSQL-CDC           → 指定执行的 case_id (逗号分隔)
#   JAVA17=/usr/java/jdk-17.0.12/bin/java → 远程 Java 17 路径
#   REMOTE_HOST / REMOTE_USER / REMOTE_DIR  → 服务器配置
# =============================================================================
set -euo pipefail

# ---------- 可配置 ----------
REMOTE_HOST="${REMOTE_HOST:-113.98.206.142}"
REMOTE_USER="${REMOTE_USER:-lg}"
REMOTE_DIR="${REMOTE_DIR:-/home2/lg/tapdata-l3-test}"
REMOTE_JAVA17="${JAVA17:-/usr/java/jdk-17.0.12/bin/java}"
REMOTE_NEXUS_REPO="${REMOTE_NEXUS_REPO:-https://nexus.tapdata.net}"
REMOTE_HTTP_PROXY="${REMOTE_HTTP_PROXY:-http://192.168.1.193:7890}"
LOCAL_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAT_JAR="$LOCAL_ROOT/tapdata-l3/tapdata-l3-cli/target/tapdata-l3-cli-fat.jar"
LOCAL_M2_TAPDATA="$HOME/.m2/repository/io/tapdata/it/l3"
CASE_ID="${CASE_ID:-L3-GP-MYSQL-CDC}"

# ---------- 颜色 ----------
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERR ]${NC} $*"; }
step()  { echo -e "${CYAN}── $* ──${NC}"; }

# ---------- 解析参数 ----------
BUILD=0; DEPLOY=0; RUN=0; VALIDATE=0
[[ $# -eq 0 ]] && { BUILD=1; DEPLOY=1; RUN=1; }
for arg in "$@"; do
  case "$arg" in
    build)    BUILD=1 ;;
    deploy)   DEPLOY=1 ;;
    run)      RUN=1 ;;
    validate) VALIDATE=1 ;;
    --case=*) CASE_ID="${arg#*=}" ;;
    *)        warn "未知参数 $arg (支持 build|deploy|run|validate|--case=ID)"; (echo "Usage: $0 [build|deploy|run|validate] [--case=ID]"; exit 1) ;;
  esac
done

# =============================================================================
# Step 1: 本地 Maven build
# =============================================================================
[[ $BUILD -eq 1 ]] && {
  step "本地 rebuild fat jar + step jars"
  cd "$LOCAL_ROOT"
  info "清理旧 step jars ..."
  rm -rf "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-step-tm" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-step-dbforge" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-step-jdbc" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-plugin" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-core" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-step-assert-jdbc" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-common"
  mvn clean install -DskipTests \
      -pl tapdata-l3/steps/tapdata-l3-step-tm,tapdata-l3/steps/tapdata-l3-step-dbforge,tapdata-l3/steps/tapdata-l3-step-jdbc,tapdata-l3/steps/tapdata-l3-step-assert-jdbc,tapdata-l3/tapdata-l3-plugin,tapdata-l3/tapdata-l3-cli \
      -am -q
  info "Build OK — fat jar: $(ls -lh "$FAT_JAR" | awk '{print $5}')"
}

# =============================================================================
# Step 2: 部署到远程服务器
# =============================================================================
[[ $DEPLOY -eq 1 ]] && {
  step "部署到 $REMOTE_HOST:$REMOTE_DIR"

  ssh "$REMOTE_USER@$REMOTE_HOST" "mkdir -p $REMOTE_DIR/cases ~/.m2/repository/io/tapdata/it/l3"

  # 2a. fat jar
  info "传 fat jar …"
  scp "$FAT_JAR" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/"

  # 2b. 所有 YAML case 文件
  info "传 cases …"
  scp "$LOCAL_ROOT"/docs/cases/*.yaml "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/cases/"

  # 2c. Maven step jars + 依赖
  info "rsync m2/io/tapdata/it/l3 …"
  rsync -avz "$LOCAL_M2_TAPDATA/" \
      "$REMOTE_USER@$REMOTE_HOST:~/.m2/repository/io/tapdata/it/l3/" > /dev/null

  # 2d. 确保远程 TM 内网配置存在
  info "确保远程 ~/.tapdata-tm 正确 …"
  ssh "$REMOTE_USER@$REMOTE_HOST" "cat > ~/.tapdata-tm <<'EOF'
url = http://192.168.1.190:24021
access_code = 3324cfdf-7d3e-4792-bd32-571638d4562f
EOF"

  info "部署完成 ✓"
}

# =============================================================================
# Step 3a: 远程 validate（全链路预校验，不执行 step）
# =============================================================================
[[ $VALIDATE -eq 1 ]] && {
  step "远程 validate ($REMOTE_HOST)"
  REMOTE_LOG="/tmp/l3-validate-$(date +%H%M%S).log"
  ssh "$REMOTE_USER@$REMOTE_HOST" "
    export HTTP_PROXY=$REMOTE_HTTP_PROXY HTTPS_PROXY=$REMOTE_HTTP_PROXY
    cd $REMOTE_DIR
    echo '=== Java ==='
    $REMOTE_JAVA17 -version 2>&1
    echo
    echo '=== Validate ==='
    $REMOTE_JAVA17 -jar tapdata-l3-cli-fat.jar validate \
        --dir=cases \
        --remote-repos=$REMOTE_NEXUS_REPO \
        --debug \
        2>&1 | tee $REMOTE_LOG
    echo
    echo '=== Exit ==='
    echo "EXIT_CODE=\$?"
  "
  info "Validate 日志已保存"
}

# =============================================================================
# Step 3b: 远程执行 L3 场景
# =============================================================================
[[ $RUN -eq 1 ]] && {
  step "远程执行 $CASE_ID ($REMOTE_HOST)"
  REMOTE_LOG="/tmp/l3-${CASE_ID}-$(date +%H%M%S).log"

  ssh "$REMOTE_USER@$REMOTE_HOST" "
    export HTTP_PROXY=$REMOTE_HTTP_PROXY HTTPS_PROXY=$REMOTE_HTTP_PROXY
    cd $REMOTE_DIR
    rm -f ./gp-output/*.json ./gp-output/*.xml ./gp-output/*.html 2>/dev/null || true

    echo '=== Preflight Check ==='
    $REMOTE_JAVA17 -version 2>&1
    curl -s 'http://192.168.1.190:24021/api/users/generatetoken' \
         -H 'Content-Type: application/json' \
         -d '{\"accesscode\":\"3324cfdf-7d3e-4792-bd32-571638d4562f\"}' \
         | python3 -c 'import sys,json; d=json.load(sys.stdin); print(\"TM OK, token len=\", len(d.get(\"data\",{}).get(\"id\",\"\")))' 2>/dev/null || echo 'TM check skipped'
    echo

    echo '=== Run ==='
    $REMOTE_JAVA17 -jar tapdata-l3-cli-fat.jar run \
        --dir=cases \
        --case-ids=$CASE_ID \
        --remote-repos=$REMOTE_NEXUS_REPO \
        2>&1 | tee $REMOTE_LOG
    RC=\${PIPESTATUS[0]}
    echo
    echo \"EXIT_CODE=\$RC\"
    echo
    echo '=== Summary (last 20 lines) ==='
    tail -20 $REMOTE_LOG
    echo
    echo \"RUN_EXIT_CODE=\$RC\"
  "

  # 拉回完整日志
  ssh "$REMOTE_USER@$REMOTE_HOST" "cat $REMOTE_LOG" > "/tmp/l3-remote-$(date +%Y%m%d-%H%M%S).log" 2>/dev/null || true
  info "完整日志已保存: /tmp/l3-remote-*.log"
}

step "全部完成 ✓"
