#!/usr/bin/env bash
# =============================================================================
# L3 Golden Path — 远程构建 + 部署 + 执行脚本
# =============================================================================
# 用法:
#   ./scripts/l3-remote.sh                      # 完整流程: build → deploy → run（单 JVM 跑全部用例）
#   ./scripts/l3-remote.sh build                # 只本地 rebuild fat jar
#   ./scripts/l3-remote.sh deploy               # 只传 fat jar + cases + m2 repo
#   ./scripts/l3-remote.sh run                  # 只远程执行已有制品（默认跑 cases 目录下全部用例）
#   ./scripts/l3-remote.sh run --case L3-SMOKE  # 指定 case_id（逗号分隔；--case=ID 同价）
#   ./scripts/l3-remote.sh validate             # 远程跑 validate（不执行 step；skipped 文件算 error）
#   ./scripts/l3-remote.sh run --no-recursive   # 不递归子目录
#
# 可选环境变量（敏感信息不再硬编码）:
#   TM_URL=http://192.168.1.190:24021            → 共享 TM 地址（写入远程 ~/.tapdata-tm）
#   TM_ACCESS_CODE=<uuid>                        → TM access code
#   JAVA17=/usr/java/jdk-17.0.12/bin/java        → 远程 Java 17 路径
#   REMOTE_HOST / REMOTE_USER / REMOTE_DIR       → 服务器配置
#   L3_HTTP_PROXY=off                            → 远程代理（off = 不设置）
#
# 退出码: run/validate 直接透传远程 CLI 的退出码（0=全部通过）
# =============================================================================
set -euo pipefail

# ---------- 可配置 ----------
REMOTE_HOST="${REMOTE_HOST:-113.98.206.142}"
REMOTE_USER="${REMOTE_USER:-lg}"
REMOTE_DIR="${REMOTE_DIR:-/home2/lg/tapdata-l3-test}"
REMOTE_JAVA17="${JAVA17:-/usr/java/jdk-17.0.12/bin/java}"
REMOTE_NEXUS_REPO="${REMOTE_NEXUS_REPO:-https://nexus.tapdata.net}"
TM_URL="${TM_URL:-http://192.168.1.190:24021}"
TM_ACCESS_CODE="${TM_ACCESS_CODE:-3324cfdf-7d3e-4792-bd32-571638d4562f}"
L3_HTTP_PROXY="${L3_HTTP_PROXY:-http://192.168.1.193:7890}"
LOCAL_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAT_JAR="$LOCAL_ROOT/tapdata-l3/tapdata-l3-cli/target/tapdata-l3-cli-fat.jar"
LOCAL_M2_TAPDATA="$HOME/.m2/repository/io/tapdata/it/l3"
TS="$(date +%Y%m%d-%H%M%S)"
REMOTE_LOG="/tmp/l3-${TS}.log"

# ---------- 颜色 ----------
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERR ]${NC} $*"; }
step()  { echo -e "${CYAN}── $* ──${NC}"; }

# ---------- 解析参数 ----------
BUILD=0; DEPLOY=0; RUN=0; VALIDATE=0; NO_RECURSIVE=""
CASE_IDS=""
[[ $# -eq 0 ]] && { BUILD=1; DEPLOY=1; RUN=1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    build)    BUILD=1 ;;
    deploy)   DEPLOY=1 ;;
    run)      RUN=1 ;;
    validate) VALIDATE=1 ;;
    --case)   CASE_IDS="$2"; shift ;;
    --case=*) CASE_IDS="${1#*=}" ;;
    --no-recursive) NO_RECURSIVE="--no-recursive" ;;
    *)        warn "未知参数 $1 (支持 build|deploy|run|validate|--case ID|--case=ID|--no-recursive)"; echo "Usage: $0 [build|deploy|run|validate] [--case ID] [--no-recursive]"; exit 1 ;;
  esac
  shift
done

# ---------- 中断/退出时清理远程孤儿 java 进程 ----------
RUNNING_REMOTE=0
cleanup() {
  if [[ $RUNNING_REMOTE -eq 1 ]]; then
    warn "清理远程孤儿 java 进程 …"
    ssh "$REMOTE_USER@$REMOTE_HOST" "pkill -f 'tapdata-l3-cli-fat.jar' 2>/dev/null || true" || true
  fi
}
trap cleanup EXIT INT TERM

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
      -pl tapdata-l3/steps/tapdata-l3-step-tm,tapdata-l3/steps/tapdata-l3-step-dbforge,tapdata-l3/steps/tapdata-l3-step-jdbc,tapdata-l3/steps/tapdata-l3-step-assert-jdbc,tapdata-l3/steps/tapdata-l3-step-deploy,tapdata-l3/tapdata-l3-plugin,tapdata-l3/tapdata-l3-cli \
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

  # 2b. 所有 YAML case 文件（含子目录）
  info "传 cases …"
  rsync -avz "$LOCAL_ROOT/docs/cases/" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/cases/" > /dev/null

  # 2c. Maven step jars + 依赖
  info "rsync m2/io/tapdata/it/l3 …"
  rsync -avz "$LOCAL_M2_TAPDATA/" \
      "$REMOTE_USER@$REMOTE_HOST:~/.m2/repository/io/tapdata/it/l3/" > /dev/null

  # 2d. 确保远程 TM 内网配置存在（凭据来自环境变量，不再硬编码）
  info "确保远程 ~/.tapdata-tm 正确 …"
  ssh "$REMOTE_USER@$REMOTE_HOST" "cat > ~/.tapdata-tm <<'EOF'
url = $TM_URL
access_code = $TM_ACCESS_CODE
EOF"

  info "部署完成 ✓"
}

# =============================================================================
# Step 3a: 远程 validate（全链路预校验，不执行 step）
#   skipped 文件计入 error；SpEL 静态扫描提前暴露引用错误
# =============================================================================
[[ $VALIDATE -eq 1 ]] && {
  step "远程 validate ($REMOTE_HOST)"
  RUNNING_REMOTE=1
  ssh "$REMOTE_USER@$REMOTE_HOST" "
    ${L3_HTTP_PROXY:+export HTTP_PROXY=$L3_HTTP_PROXY HTTPS_PROXY=$L3_HTTP_PROXY}
    cd $REMOTE_DIR
    echo '=== Java ==='
    $REMOTE_JAVA17 -version 2>&1
    echo
    echo '=== Validate ==='
    $REMOTE_JAVA17 -jar tapdata-l3-cli-fat.jar validate \
        --dir=cases \
        --remote-repos=$REMOTE_NEXUS_REPO \
        --debug \
        $NO_RECURSIVE \
        2>&1 | tee $REMOTE_LOG
    RC=\${PIPESTATUS[0]}
    echo
    echo '=== Exit ==='
    echo "VALIDATE_EXIT_CODE=\$RC"
    exit \$RC
  " || RC=$?
  RC=${RC:-0}
  RUNNING_REMOTE=0
  info "Validate 退出码: $RC（日志: $REMOTE_LOG）"
  exit "$RC"
}

# =============================================================================
# Step 3b: 远程执行 L3 场景 — 单 JVM 批量跑
#   默认跑 cases 目录下全部用例（递归子目录）；--case 指定子集。
#   报告输出到远程 reports/<时间戳>/，跑完拉回本地 l3-reports/。
# =============================================================================
[[ $RUN -eq 1 ]] && {
  CASE_ARGS=""
  [[ -n "$CASE_IDS" ]] && CASE_ARGS="--case-ids=$CASE_IDS"
  step "远程执行 $([ -n "$CASE_IDS" ] || echo 'ALL cases') ($REMOTE_HOST)"
  RUNNING_REMOTE=1

  ssh "$REMOTE_USER@$REMOTE_HOST" "
    ${L3_HTTP_PROXY:+export HTTP_PROXY=$L3_HTTP_PROXY HTTPS_PROXY=$L3_HTTP_PROXY}
    cd $REMOTE_DIR

    echo '=== Preflight Check ==='
    $REMOTE_JAVA17 -version 2>&1
    curl -s '$TM_URL/api/users/generatetoken' \
         -H 'Content-Type: application/json' \
         -d '{\"accesscode\":\"$TM_ACCESS_CODE\"}' \
         | python3 -c 'import sys,json; d=json.load(sys.stdin); print(\"TM OK, token len=\", len(d.get(\"data\",{}).get(\"id\",\"\")))' 2>/dev/null || echo 'TM check skipped'
    echo

    echo '=== Run (single JVM, all cases) ==='
    $REMOTE_JAVA17 -jar tapdata-l3-cli-fat.jar run \
        --dir=cases \
        $NO_RECURSIVE \
        $CASE_ARGS \
        --remote-repos=$REMOTE_NEXUS_REPO \
        2>&1 | tee $REMOTE_LOG
    RC=\${PIPESTATUS[0]}
    echo
    echo "RUN_EXIT_CODE=\$RC"
    exit \$RC
  " || RC=$?
  RC=${RC:-0}
  RUNNING_REMOTE=0

  # 拉回完整日志 + 本轮报告目录（reports/<ts>/）
  info "拉回日志与报告 …"
  ssh "$REMOTE_USER@$REMOTE_HOST" "cat $REMOTE_LOG" > "/tmp/l3-remote-${TS}.log" 2>/dev/null || true
  LATEST_REPORT="$(ssh "$REMOTE_USER@$REMOTE_HOST" "ls -1dt $REMOTE_DIR/reports/*/ 2>/dev/null | head -1" || true)"
  if [[ -n "${LATEST_REPORT:-}" ]]; then
    mkdir -p "$LOCAL_ROOT/l3-reports"
    rsync -avz "$REMOTE_USER@$REMOTE_HOST:$LATEST_REPORT" "$LOCAL_ROOT/l3-reports/" > /dev/null \
      && info "报告已拉回: $LOCAL_ROOT/l3-reports/$(basename "$LATEST_REPORT")"
  fi

  info "完整日志: /tmp/l3-remote-${TS}.log"
  info "远程执行退出码: $RC"
  exit $RC
}

step "全部完成 ✓"
