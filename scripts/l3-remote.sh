#!/usr/bin/env bash
# =============================================================================
# L3 Golden Path — 远程部署 + 执行脚本
# =============================================================================
# 用法:
#   ./scripts/l3-remote.sh                 # 完整流程: rebuild → deploy → run
#   ./scripts/l3-remote.sh build           # 只本地 rebuild fat jar + m2 repo
#   ./scripts/l3-remote.sh deploy          # 只传 fat jar + m2 repo + cases
#   ./scripts/l3-remote.sh run             # 只在远程执行已有制品
#   ./scripts/l3-remote.sh build deploy   # rebuild + deploy (不 run)
#   ./scripts/l3-remote.sh run --case L3-SMOKE --timeout 180
#
# 可选环境变量:
#   RUN_TAG=mytest  → 覆盖 YAML 里的 run31 等名称后缀
#   CASE_ID=L3-GP-MYSQL-CDC  → 指定执行的 case_id
#   JAVA17=/usr/java/jdk-17.0.12/bin/java  → 远程 Java 17 路径
# =============================================================================
set -euo pipefail

# ---------- 可配置 ----------
REMOTE_HOST="${REMOTE_HOST:-113.98.206.142}"
REMOTE_USER="${REMOTE_USER:-lg}"
REMOTE_DIR="${REMOTE_DIR:-/home2/lg/tapdata-l3-test}"
REMOTE_JAVA17="${JAVA17:-/usr/java/jdk-17.0.12/bin/java}"
LOCAL_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAT_JAR="$LOCAL_ROOT/tapdata-l3/tapdata-l3-cli/target/tapdata-l3-cli-fat.jar"
LOCAL_M2_TAPDATA="$HOME/.m2/repository/io/tapdata/it/l3"
CASE_ID="${CASE_ID:-L3-GP-MYSQL-CDC}"
RUN_TAG="${RUN_TAG:-run31}"

# ---------- 颜色 ----------
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERR ]${NC} $*"; }
step()  { echo -e "${CYAN}── $* ──${NC}"; }

# ---------- 解析参数 ----------
BUILD=0; DEPLOY=0; RUN=0
[[ $# -eq 0 ]] && { BUILD=1; DEPLOY=1; RUN=1; }
for arg in "$@"; do
  case "$arg" in
    build)  BUILD=1 ;;
    deploy) DEPLOY=1 ;;
    run)    RUN=1 ;;
    *)      warn "未知参数 $arg (支持 build|deploy|run)"; BUILD=1; DEPLOY=1; RUN=1 ;;
  esac
done

# =============================================================================
# Step 1: 本地 Maven build
# =============================================================================
[[ $BUILD -eq 1 ]] && {
  step "本地 rebuild fat jar + step jars"
  cd "$LOCAL_ROOT"
  rm -rf "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-step-tm" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-step-dbforge" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-step-jdbc" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-plugin" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-core" \
         "$HOME/.m2/repository/io/tapdata/it/l3/tapdata-l3-common"
  mvn clean install -DskipTests \
      -pl tapdata-l3/steps/tapdata-l3-step-tm,tapdata-l3/steps/tapdata-l3-step-dbforge,tapdata-l3/steps/tapdata-l3-step-jdbc,tapdata-l3/tapdata-l3-plugin,tapdata-l3/tapdata-l3-cli \
      -am -q
  info "Build OK — fat jar: $(ls -lh "$FAT_JAR" | awk '{print $5}')"
}

# =============================================================================
# Step 2: 部署到远程服务器
# =============================================================================
[[ $DEPLOY -eq 1 ]] && {
  step "部署到 $REMOTE_HOST:$REMOTE_DIR"

  # 确保远程目录存在
  ssh "$REMOTE_USER@$REMOTE_HOST" "mkdir -p $REMOTE_DIR/cases $REMOTE_DIR/gp-output ~/.m2/repository/io/tapdata/it/l3"

  # 2a. fat jar
  info "传 fat jar …"
  scp "$FAT_JAR" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/"

  # 2b. 所有 YAML case 文件
  info "传 cases …"
  scp "$LOCAL_ROOT"/docs/cases/*.yaml "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/cases/"

  # 2c. Maven step jars + 依赖（PluginClassLoader 加载）
  info "rsync m2/io/tapdata/it/l3 …"
  rsync -avz "$LOCAL_M2_TAPDATA/" \
      "$REMOTE_USER@$REMOTE_HOST:~/.m2/repository/io/tapdata/it/l3/" > /dev/null

  # 2d. ~/.tapdata-tm 远程内网配置（如果不存在）
  info "确保远程 ~/.tapdata-tm 正确 …"
  ssh "$REMOTE_USER@$REMOTE_HOST" "cat > ~/.tapdata-tm <<'EOF'
# TM 内网地址 — 远程服务器在内网，直接访问
url = http://192.168.1.190:24021
access_code = 3324cfdf-7d3e-4792-bd32-571638d4562f
EOF"

  info "部署完成"
}

# =============================================================================
# Step 3: 远程执行 L3 场景
# =============================================================================
[[ $RUN -eq 1 ]] && {
  step "远程执行 L3-GP 场景 ($REMOTE_HOST)"

  # 3a. 把 run tag 注入 YAML（避免名字冲突）
  ssh "$REMOTE_USER@$REMOTE_HOST" "sed -i 's/run[0-9][0-9]/${RUN_TAG}/g' $REMOTE_DIR/cases/gp-mysql-fullsync-cdc-inspect.yaml"

  # 3b. 清理旧输出 + 执行
  REMOTE_LOG="/tmp/l3-${CASE_ID}-$(date +%H%M%S).log"
  ssh "$REMOTE_USER@$REMOTE_HOST" "
    cd $REMOTE_DIR
    rm -rf ./gp-output
    echo '=== Java ==='
    $REMOTE_JAVA17 -version 2>&1
    echo
    echo '=== TM ==='
    curl -s 'http://192.168.1.190:24021/api/users/generatetoken' \
         -H 'Content-Type: application/json' \
         -d '{\"accesscode\":\"3324cfdf-7d3e-4792-bd32-571638d4562f\"}' \
         | python3 -c 'import sys,json; print(\"TM OK, token len=\", len(json.load(sys.stdin)[\"data\"][\"id\"]))'
    echo
    echo '=== Run ==='
    $REMOTE_JAVA17 -jar tapdata-l3-cli-fat.jar run \
        --dir=cases \
        --case-ids=$CASE_ID \
        --output=./gp-output \
        2>&1 | tee $REMOTE_LOG
    echo
    echo '=== Summary ==='
    tail -20 $REMOTE_LOG
  "

  # 3c. 把日志拉回本地
  ssh "$REMOTE_USER@$REMOTE_HOST" "cat $REMOTE_LOG" > "/tmp/l3-remote-$(date +%Y%m%d-%H%M%S).log"
  info "日志已保存: /tmp/l3-remote-*.log"
}

step "全部完成 ✓"
