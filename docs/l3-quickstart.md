# L3 CLI 快速入门

> 面向第一次使用 l3-cli 的工程师。目标：5 分钟内跑通第一个集成测试用例。

## 前置条件

| 条件 | 检查命令 | 说明 |
|---|---|---|
| Java 17+ | `java -version` | 推荐 17 或 21，低于 17 无法运行 |
| fat jar | `ls tapdata-l3/tapdata-l3-cli/target/tapdata-l3-cli-fat.jar` | 约 21MB，构建产物 |
| Nexus 可达 | `curl -sI https://nexus.tapdata.net` | 外置 step 需要下载依赖 |

如果 fat jar 不存在，先构建：

```bash
cd tapdata-it
mvn clean install -DskipTests \
  -pl tapdata-l3/steps/tapdata-l3-step-tm,tapdata-l3/steps/tapdata-l3-step-dbforge,tapdata-l3/steps/tapdata-l3-step-jdbc,tapdata-l3/steps/tapdata-l3-step-assert-jdbc,tapdata-l3/steps/tapdata-l3-step-deploy,tapdata-l3/tapdata-l3-plugin,tapdata-l3/tapdata-l3-cli \
  -am -q
```

---

## 三种运行方式

### 方式 A：本地直接运行（最快）

```bash
cd tapdata-it
java -jar tapdata-l3/tapdata-l3-cli/target/tapdata-l3-cli-fat.jar \
  run --dir=docs/cases --case-ids=L3-C1-SMOKE
```

预期输出：

```
[L3]   [ 1/ 2] echo_test                    echo                     PASS  3ms
[L3]   [ 2/ 2] echo_again                   echo                     PASS  0ms
[L3] SUMMARY  1 passed  0 failed  1 total  |  pass rate 100.0%
```

### 方式 B：Docker 干净容器（零环境污染）

适合验证"新机器上能否直接跑起来"。

```bash
# 准备测试目录（只需 fat jar + cases）
mkdir -p /tmp/l3-quickstart/cases
cp tapdata-l3/tapdata-l3-cli/target/tapdata-l3-cli-fat.jar /tmp/l3-quickstart/
cp docs/cases/c1-smoke-test.yaml /tmp/l3-quickstart/cases/

# 启动干净容器
docker run --rm --network host \
  -v /tmp/l3-quickstart:/workspace -w /workspace \
  eclipse-temurin:17-jre-alpine \
  java -jar tapdata-l3-cli-fat.jar run --dir=cases --case-ids=L3-C1-SMOKE
```

> `--network host` 让容器共享宿主机网络，确保能直连内网 Nexus。如果去掉，容器内 DNS 和代理配置可能无法访问 `192.168.1.184`。

### 方式 C：远程服务器批量执行

```bash
# 完整流程：build → deploy → run
./scripts/l3-remote.sh

# 只跑指定 case
./scripts/l3-remote.sh run --case L3-C1-SMOKE

# 预校验（不执行 step，检查配置和依赖）
./scripts/l3-remote.sh validate
```

---

## 从简单到复杂：推荐学习路径

| 顺序 | Case | 验证点 | 命令 |
|---|---|---|---|
| 1 | `L3-C1-SMOKE` | 内置 echo step、SpEL 变量引用 | `run --case-ids=L3-C1-SMOKE` |
| 2 | `L3-C2-PARALLEL` | 并行块、超时控制、负向用例 | `run --case-ids=L3-C2-PARALLEL` |
| 3 | `L3-C3-DBFORGE` | 外置 step 自动下载、数据库创建 | `run --case-ids=L3-C3-DBFORGE` |
| 4 | 全部 | 批量执行、报告生成 | `run --dir=docs/cases` |

### 第 1 步：Smoke Test（纯内置）

`c1-smoke-test.yaml` 只用了 `echo`（内置 step，不需要下载任何依赖）：

```yaml
steps:
  - id: "echo_test"
    action: "echo"
    inputs:
      message: "#{ #variables['greeting'] }"
```

运行后观察：
- `PluginCenter - Runtime ready: 2 built-in, 0 PF4J plugin(s), 29 registry entrie(s)`
- 两个 step 通过 SpEL 链式引用传递输出

### 第 2 步：外置 Step 自动加载

`c3-dbforge-test.yaml` 使用了 `dbforge_create_database`（不在 fat jar 内）：

```yaml
steps:
  - id: "create_mysql"
    action: "dbforge_create_database"
    inputs:
      db_type: "mysql"
```

首次运行时会看到：

```
INFO  PluginCenter - Loading JAR plugin from Maven coord: io.tapdata.it.l3:tapdata-l3-step-dbforge:1.0.0-SNAPSHOT
INFO  PluginCenter - Loaded 3 handler(s) from io.tapdata.it.l3:tapdata-l3-step-dbforge:1.0.0-SNAPSHOT
```

这证明：
1. fat jar 内**没有**预打包 step jar
2. CLI 根据 `steps-registry.yaml` 找到 Maven 坐标
3. 从 Nexus 自动下载并加载到当前 JVM

### 第 3 步：Validate 预校验（不执行）

在跑真实 case 前，先用 `validate` 做静态检查：

```bash
java -jar tapdata-l3-cli-fat.jar validate \
  --dir=docs/cases \
  --remote-repos=https://nexus.tapdata.net
```

Validate 会：
- 解析所有 YAML，检查必填字段
- 预加载所有外置 step jar
- 静态扫描 SpEL 表达式引用是否正确
- 跳过非法文件并计入 error

---

## 故障排查

### 问题 1：`ClassNotFoundException` 或 `UnsupportedClassVersionError`

**原因**：Java 版本低于 17。

```bash
java -version  # 必须 17+
```

### 问题 2：`Failed to resolve plugin from Maven`

**可能原因**：

| 场景 | 排查 |
|---|---|
| 本地无 Nexus 访问 | `curl -sI https://nexus.tapdata.net` 是否 200 |
| Docker 容器内 | 加 `--network host`；或配置 JVM 代理 `-Dhttp.proxyHost=...` |
| 代理 TLS 失败 | 部分代理不支持 Java TLS 1.3 + CONNECT，建议直连或换代理 |

### 问题 3：YAML 被跳过（`SKIPPED FILE`）

`validate` 模式下，任何解析失败的 YAML 都会计入 error。常见原因：
- 缺少 `action` 字段
- YAML 语法错误（缩进、引号）
- `case_id` 重复

### 问题 4：Step 超时

在 step 上加 `timeout_seconds`：

```yaml
- id: "slow_step"
  action: "sleep"
  inputs:
    seconds: 30
  timeout_seconds: 10
```

### 问题 5：负向用例（预期失败）

```yaml
- id: "should_fail"
  action: "sleep"
  inputs:
    seconds: 30
  timeout_seconds: 2
  expect_failure: true
  expect_failure_message: "timed out"
```

---

## 核心概念速查

| 概念 | 说明 |
|---|---|
| **fat jar** | 单文件可执行 JAR，包含 CLI + Core + Spring Boot |
| **内置 step** | `echo`、`sleep`，打包在 fat jar 内，无需下载 |
| **外置 step** | `dbforge_*`、`tm_*` 等，通过 Maven 坐标从 Nexus 动态加载 |
| **steps-registry.yaml** | action 名 → Maven 坐标的映射表，打包在 fat jar 内 |
| **PluginCenter** | 负责加载 step jar、注册 Action handler |
| **validate** | 静态校验模式：解析 YAML + 预加载依赖 + SpEL 扫描，不真正执行 |
| **SpEL** | Spring Expression Language，用于 step 间传递数据：`#{ #steps['x'].outputs['y'] }` |

---

## 下一步

1. 复制 `docs/cases/c1-smoke-test.yaml` 为 `my-first-case.yaml`
2. 修改 `case_id` 和 `message`，运行 `run --case-ids=MY-FIRST`
3. 尝试加入 `dbforge_create_database`，观察自动下载过程
4. 查看生成的报告：`reports/<timestamp>/report.html`
