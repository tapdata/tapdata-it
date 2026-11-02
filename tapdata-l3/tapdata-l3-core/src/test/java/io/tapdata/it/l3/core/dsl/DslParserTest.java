package io.tapdata.it.l3.core.dsl;

import io.tapdata.it.l3.common.ParallelBlock;
import io.tapdata.it.l3.common.ScenarioConfig;
import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.common.SafeSpelEvaluator;
import io.tapdata.it.l3.common.exception.DslParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DslParser + ScenarioContext + SafeSpelEvaluator 变量引用测试。
 * <p>
 * 验证三类引用的正确性：
 * <ol>
 *   <li>跨 step output 引用解析 #{#steps['step_id'].outputs['key']}</li>
 *   <li>Scenario 变量引用 #{#variables['var_name']}</li>
 *   <li>built-in 变量 (timestamp, uuid)</li>
 * </ol>
 */
class DslParserTest {

    private final DslParser parser = new DslParser();

    // ==================== 基础解析 + Step Output 引用 ====================

    @Test
    @DisplayName("基础场景解析 — 正确加载 case_id、variables、steps")
    void basicScenarioParse() throws IOException {
        String yaml = """
                case_id: L3-VAR-01
                scenario: "变量引用基础场景"
                group: functional
                variables:
                  mysql_port: 3306
                  db_name: test_db
                steps:
                  - id: s1
                    action: dbforge_create_database
                    inputs:
                      name: "#{ #variables['db_name'] }"
                      port: "#{ #variables['mysql_port'] }"
                    outputs: [db_uri]
                  - id: s2
                    action: echo
                    inputs:
                      msg: "DB URI is #{ #steps['s1'].outputs['db_uri'] }"
                """;

        ScenarioConfig config = parseYaml(yaml);

        assertEquals("L3-VAR-01", config.getCaseId());
        assertEquals("变量引用基础场景", config.getScenario());
        assertEquals("functional", config.getGroup());
        assertEquals(2, config.getVariables().size());
        assertEquals(3306, config.getVariables().get("mysql_port"));
        assertEquals("test_db", config.getVariables().get("db_name"));
        assertEquals(2, config.getBlocks().size());
        assertInstanceOf(StepConfig.class, config.getBlocks().get(0));
        assertInstanceOf(StepConfig.class, config.getBlocks().get(1));
    }

    @Test
    @DisplayName("extractStepReferences — 提取单个 step 引用")
    void extractSingleStepReference() {
        Map<String, Object> inputs = Map.of(
                "uri", "#{ #steps['create_db'].outputs['db_uri'] }"
        );

        Set<String> refs = parser.extractStepReferences(inputs);

        assertEquals(Set.of("create_db"), refs);
    }

    @Test
    @DisplayName("extractStepReferences — 提取多个不同 step 引用")
    void extractMultipleStepReferences() {
        Map<String, Object> inputs = Map.of(
                "src", "#{ #steps['create_src'].outputs['uri'] }",
                "dst", "#{ #steps['create_dst'].outputs['uri'] }",
                "task", "#{ #steps['create_task'].outputs['task_id'] }"
        );

        Set<String> refs = parser.extractStepReferences(inputs);

        assertEquals(Set.of("create_src", "create_dst", "create_task"), refs);
    }

    @Test
    @DisplayName("extractStepReferences — 同一 step 多次引用只记一次")
    void extractDeduplicateStepReferences() {
        Map<String, Object> inputs = Map.of(
                "host", "#{ #steps['db'].outputs['host'] }",
                "port", "#{ #steps['db'].outputs['port'] }",
                "user", "#{ #steps['db'].outputs['user'] }"
        );

        Set<String> refs = parser.extractStepReferences(inputs);

        assertEquals(Set.of("db"), refs);
        assertEquals(1, refs.size());
    }

    @Test
    @DisplayName("extractStepReferences — 嵌套 Map 中的引用")
    void extractNestedMapReferences() {
        Map<String, Object> inner = Map.of(
                "connection", "#{ #steps['setup'].outputs['uri'] }"
        );
        Map<String, Object> inputs = Map.of(
                "jdbc", inner
        );

        Set<String> refs = parser.extractStepReferences(inputs);

        assertEquals(Set.of("setup"), refs);
    }

    @Test
    @DisplayName("extractStepReferences — List 中的引用")
    void extractListReferences() {
        Map<String, Object> inputs = Map.of(
                "uris", List.of(
                        "#{ #steps['s1'].outputs['uri'] }",
                        "#{ #steps['s2'].outputs['uri'] }"
                )
        );

        Set<String> refs = parser.extractStepReferences(inputs);

        assertEquals(Set.of("s1", "s2"), refs);
    }

    @Test
    @DisplayName("extractStepReferences — 深层嵌套 Map + List")
    void extractDeepNestedReferences() {
        Map<String, Object> inputs = Map.of(
                "top", Map.of(
                        "middle", List.of(
                                Map.of("inner", "#{ #steps['deep'].outputs['value'] }")
                        )
                )
        );

        Set<String> refs = parser.extractStepReferences(inputs);

        assertEquals(Set.of("deep"), refs);
    }

    @Test
    @DisplayName("extractStepReferences — 无引用返回空集合")
    void extractNoReferences() {
        Map<String, Object> inputs = Map.of(
                "name", "hello",
                "count", 42,
                "flag", true
        );

        Set<String> refs = parser.extractStepReferences(inputs);

        assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("extractStepReferences — null inputs 安全返回")
    void extractNullInputs() {
        Set<String> refs = parser.extractStepReferences(null);
        assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("extractStepReferences — 忽略非字符串值 (数字/布尔/null)")
    void extractIgnoresNonString() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("port", 3306);
        inputs.put("enabled", true);
        inputs.put("desc", null);

        Set<String> refs = parser.extractStepReferences(inputs);
        assertTrue(refs.isEmpty());
    }

    // ==================== Parallel Block 独立性校验 ====================

    @Test
    @DisplayName("Parallel block — 允许引用 block 外部 step 的 outputs")
    void parallelCanReferenceExternalStep() throws IOException {
        String yaml = """
                case_id: L3-PAR-01
                steps:
                  - id: setup
                    action: prepare
                    outputs: [token]
                  - parallel:
                      - id: worker1
                        action: task_a
                        inputs:
                          auth: "#{ #steps['setup'].outputs['token'] }"
                      - id: worker2
                        action: task_b
                        inputs:
                          auth: "#{ #steps['setup'].outputs['token'] }"
                """;

        ScenarioConfig config = parseYaml(yaml);

        assertEquals(2, config.getBlocks().size());
        assertInstanceOf(StepConfig.class, config.getBlocks().get(0));
        assertInstanceOf(ParallelBlock.class, config.getBlocks().get(1));
    }

    @Test
    @DisplayName("Parallel block — 禁止引用 block 内部其他 step 的 outputs")
    void parallelCannotReferenceInternalStep() {
        String yaml = """
                case_id: L3-PAR-02
                steps:
                  - parallel:
                      - id: producer
                        action: emit
                        outputs: [result]
                      - id: consumer
                        action: consume
                        inputs:
                          data: "#{ #steps['producer'].outputs['result'] }"
                """;

        DslParseException ex = assertThrows(DslParseException.class, () -> parseYaml(yaml));
        assertTrue(ex.getMessage().contains("parallel"));
        assertTrue(ex.getMessage().contains("independent"));
        assertTrue(ex.getMessage().contains("producer"));
    }

    @Test
    @DisplayName("Parallel block — 禁止同一 block 内两个 step 互相引用")
    void parallelBidirectionalReferenceForbidden() {
        String yaml = """
                case_id: L3-PAR-03
                steps:
                  - parallel:
                      - id: a
                        action: act_a
                        inputs:
                          other: "#{ #steps['b'].outputs['key'] }"
                      - id: b
                        action: act_b
                        outputs: [key]
                """;

        DslParseException ex = assertThrows(DslParseException.class, () -> parseYaml(yaml));
        assertTrue(ex.getMessage().contains("Step 'a'"));
        assertTrue(ex.getMessage().contains("b"));
    }

    @Test
    @DisplayName("Sequential steps — 允许引用前置 step 的 outputs")
    void sequentialCanReferencePrevious() throws IOException {
        String yaml = """
                case_id: L3-SEQ-01
                steps:
                  - id: create_user
                    action: create
                    outputs: [user_id]
                  - id: assign_role
                    action: assign
                    inputs:
                      target: "#{ #steps['create_user'].outputs['user_id'] }"
                  - id: grant_perm
                    action: grant
                    inputs:
                      user: "#{ #steps['create_user'].outputs['user_id'] }"
                """;

        ScenarioConfig config = parseYaml(yaml);

        assertEquals(3, config.getBlocks().size());

        StepConfig assignRole = (StepConfig) config.getBlocks().get(1);
        assertEquals("#{ #steps['create_user'].outputs['user_id'] }",
                assignRole.getInputs().get("target"));

        StepConfig grantPerm = (StepConfig) config.getBlocks().get(2);
        assertEquals("#{ #steps['create_user'].outputs['user_id'] }",
                grantPerm.getInputs().get("user"));
    }

    // ==================== ScenarioContext 变量引用解析 ====================

    @Test
    @DisplayName("ScenarioContext — 解析 variables 中的基础类型变量")
    void contextResolveVariables() {
        ScenarioContext ctx = new ScenarioContext();
        ctx.setVariable("db_host", "10.0.0.1");
        ctx.setVariable("db_port", 3306);

        assertEquals("10.0.0.1", ctx.evaluateExpression("#{ #variables['db_host'] }"));
        assertEquals(3306, ctx.evaluateExpression("#{ #variables['db_port'] }"));
    }

    @Test
    @DisplayName("ScenarioContext — 解析 step outputs 引用")
    void contextResolveStepOutputs() {
        ScenarioContext ctx = new ScenarioContext();
        ctx.setStepOutput("create_db", "uri", "jdbc:mysql://localhost:3306/test");
        ctx.setStepOutput("create_db", "schema", "test");

        Object uri = ctx.evaluateExpression("#{ #steps['create_db'].outputs['uri'] }");
        assertEquals("jdbc:mysql://localhost:3306/test", uri);

        Object schema = ctx.evaluateExpression("#{ #steps['create_db'].outputs['schema'] }");
        assertEquals("test", schema);
    }

    @Test
    @DisplayName("ScenarioContext — 字符串插值多个 SpEL 块")
    void contextStringInterpolation() {
        ScenarioContext ctx = new ScenarioContext();
        ctx.setVariable("host", "localhost");
        ctx.setVariable("port", 8080);

        String result = (String) ctx.evaluateExpression("http://#{ #variables['host'] }:#{ #variables['port'] }/api");
        assertEquals("http://localhost:8080/api", result);
    }

    @Test
    @DisplayName("ScenarioContext — 混合 variables 和 step outputs 引用")
    void contextMixedReferences() {
        ScenarioContext ctx = new ScenarioContext();
        ctx.setVariable("table_name", "users");
        ctx.setStepOutput("get_schema", "db", "test_db");

        String result = (String) ctx.evaluateExpression(
                "SELECT * FROM #{ #steps['get_schema'].outputs['db'] }.#{ #variables['table_name'] }");
        assertEquals("SELECT * FROM test_db.users", result);
    }

    @Test
    @DisplayName("ScenarioContext — evaluateInputs 递归解析嵌套结构")
    void contextEvaluateInputsNested() {
        ScenarioContext ctx = new ScenarioContext();
        ctx.setVariable("timeout", 30);
        ctx.setStepOutput("setup", "token", "abc123");

        Map<String, Object> inputs = Map.of(
                "conn", Map.of(
                        "auth", "#{ #steps['setup'].outputs['token'] }",
                        "read_timeout", "#{ #variables['timeout'] }"
                ),
                "tags", List.of("#{ #variables['timeout'] }s", "#{ #steps['setup'].outputs['token'] }")
        );

        Map<String, Object> evaluated = ctx.evaluateInputs(inputs);

        @SuppressWarnings("unchecked")
        Map<String, Object> conn = (Map<String, Object>) evaluated.get("conn");
        assertEquals("abc123", conn.get("auth"));
        assertEquals(30, conn.get("read_timeout"));

        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) evaluated.get("tags");
        assertEquals("30s", tags.get(0));
        assertEquals("abc123", tags.get(1));
    }

    @Test
    @DisplayName("ScenarioContext — evaluateInputs 无 SpEL 的值原样返回")
    void contextEvaluateInputsPlain() {
        ScenarioContext ctx = new ScenarioContext();

        Map<String, Object> inputs = Map.of(
                "name", "plain_value",
                "count", 42,
                "enabled", true
        );

        Map<String, Object> evaluated = ctx.evaluateInputs(inputs);
        assertEquals("plain_value", evaluated.get("name"));
        assertEquals(42, evaluated.get("count"));
        assertEquals(true, evaluated.get("enabled"));
    }

    @Test
    @DisplayName("ScenarioContext — built-in timestamp 和 uuid")
    void contextBuiltInVariables() {
        ScenarioContext ctx = new ScenarioContext();

        assertNotNull(ctx.getVariable("timestamp"));
        assertInstanceOf(Long.class, ctx.getVariable("timestamp"));

        assertNotNull(ctx.getVariable("uuid"));
        assertInstanceOf(String.class, ctx.getVariable("uuid"));
        assertTrue(((String) ctx.getVariable("uuid")).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));

        // 解析 built-in 变量引用
        Object ts = ctx.evaluateExpression("#{ #variables['timestamp'] }");
        assertNotNull(ts);
        assertInstanceOf(Long.class, ts);
    }

    @Test
    @DisplayName("ScenarioContext — 引用不存在的 step 抛出 SpelNullReferenceException")
    void contextMissingStepReference() {
        ScenarioContext ctx = new ScenarioContext();

        SafeSpelEvaluator.SpelNullReferenceException ex = assertThrows(
                SafeSpelEvaluator.SpelNullReferenceException.class,
                () -> ctx.evaluateExpression("#{ #steps['nonexistent'].outputs['key'] }"));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    @Test
    @DisplayName("ScenarioContext — step 存在但 outputs 里没有该 key 时抛出异常")
    void contextMissingOutputKey() {
        ScenarioContext ctx = new ScenarioContext();
        ctx.setStepOutput("setup", "uri", "jdbc:xxx");

        SafeSpelEvaluator.SpelNullReferenceException ex = assertThrows(
                SafeSpelEvaluator.SpelNullReferenceException.class,
                () -> ctx.evaluateExpression("#{ #steps['setup'].outputs['nonexistent_key'] }"));
        assertTrue(ex.getMessage().contains("setup"));
        assertTrue(ex.getMessage().contains("nonexistent_key"));
    }

    @Test
    @DisplayName("ScenarioContext — step 注册了但 outputs 为空时抛出异常")
    void contextStepWithNoOutputs() {
        ScenarioContext ctx = new ScenarioContext();
        // setStepOutput 用 ConcurrentHashMap，第一次调用会创建 map
        // 然后移除 key 让 outputs 为空（ConcurrentHashMap 不允许 null value，用 remove）
        ctx.setStepOutput("empty_step", "temp_key", true);
        // 直接获取内部 raw map 移除 key
        ctx.getSteps().get("empty_step").remove("temp_key");

        // outputs 为空 → 触发 "has no outputs" 异常
        SafeSpelEvaluator.SpelNullReferenceException ex = assertThrows(
                SafeSpelEvaluator.SpelNullReferenceException.class,
                () -> ctx.evaluateExpression("#{ #steps['empty_step'].outputs['something'] }"));
        assertTrue(ex.getMessage().contains("empty_step"));
    }

    // ==================== 端到端：DSL 解析 + Context 执行 ====================

    @Test
    @DisplayName("端到端 — DSL variables 注入 Context 后可被 steps 引用")
    void endToEndVariablesFlow() throws IOException {
        String yaml = """
                case_id: L3-E2E-01
                variables:
                  env: production
                  retries: 3
                steps:
                  - id: deploy
                    action: deploy_app
                    inputs:
                      environment: "#{ #variables['env'] }"
                      max_retries: "#{ #variables['retries'] }"
                    outputs: [deploy_id]
                  - id: verify
                    action: check_status
                    inputs:
                      target: "#{ #steps['deploy'].outputs['deploy_id'] }"
                      env_repeat: "#{ #variables['env'] }"
                """;

        ScenarioConfig config = parseYaml(yaml);

        // 构造 Context，设置 variables 和 step outputs
        ScenarioContext ctx = new ScenarioContext();
        ctx.setVariable("env", config.getVariables().get("env"));
        ctx.setVariable("retries", config.getVariables().get("retries"));
        ctx.setStepOutput("deploy", "deploy_id", "DEPLOY-12345");

        // 执行 deploy step 的 inputs 解析
        StepConfig deployStep = (StepConfig) config.getBlocks().get(0);
        Map<String, Object> deployInputs = ctx.evaluateInputs(deployStep.getInputs());
        assertEquals("production", deployInputs.get("environment"));
        assertEquals(3, deployInputs.get("max_retries"));

        // 执行 verify step 的 inputs 解析（引用前序 step 的 outputs）
        StepConfig verifyStep = (StepConfig) config.getBlocks().get(1);
        Map<String, Object> verifyInputs = ctx.evaluateInputs(verifyStep.getInputs());
        assertEquals("DEPLOY-12345", verifyInputs.get("target"));
        assertEquals("production", verifyInputs.get("env_repeat"));
    }

    @Test
    @DisplayName("端到端 — 多 step outputs 链式传递")
    void endToEndChainedStepOutputs() throws IOException {
        String yaml = """
                case_id: L3-E2E-02
                steps:
                  - id: step_a
                    action: create
                    outputs: [resource_id]
                  - id: step_b
                    action: configure
                    inputs:
                      target: "#{ #steps['step_a'].outputs['resource_id'] }"
                    outputs: [config_id]
                  - id: step_c
                    action: activate
                    inputs:
                      resource: "#{ #steps['step_a'].outputs['resource_id'] }"
                      config: "#{ #steps['step_b'].outputs['config_id'] }"
                """;

        ScenarioConfig config = parseYaml(yaml);

        ScenarioContext ctx = new ScenarioContext();
        // 模拟 step_a 执行完成
        ctx.setStepOutput("step_a", "resource_id", "RES-001");
        // 模拟 step_b 执行完成
        ctx.setStepOutput("step_b", "config_id", "CFG-001");

        StepConfig stepC = (StepConfig) config.getBlocks().get(2);
        Map<String, Object> inputs = ctx.evaluateInputs(stepC.getInputs());

        assertEquals("RES-001", inputs.get("resource"));
        assertEquals("CFG-001", inputs.get("config"));
    }

    // ==================== 无效 DSL 校验 ====================

    @Test
    @DisplayName("缺少必填字段 case_id 抛异常")
    void missingCaseId() {
        String yaml = """
                steps:
                  - id: s1
                    action: test
                """;
        assertThrows(DslParseException.class, () -> parseYaml(yaml));
    }

    @Test
    @DisplayName("缺少 steps 抛异常")
    void missingSteps() {
        String yaml = """
                case_id: L3-EMPTY
                """;
        assertThrows(DslParseException.class, () -> parseYaml(yaml));
    }

    @Test
    @DisplayName("重复 step id 抛异常")
    void duplicateStepIds() {
        String yaml = """
                case_id: L3-DUP
                steps:
                  - id: same
                    action: a
                  - id: same
                    action: b
                """;
        assertThrows(DslParseException.class, () -> parseYaml(yaml));
    }

    @Test
    @DisplayName("assert_* / wait_* action 缺少 timeout_seconds 抛异常")
    void assertActionsNeedTimeout() {
        String yaml = """
                case_id: L3-TO
                steps:
                  - id: check
                    action: assert_jdbc_data
                    inputs: {}
                """;
        DslParseException ex = assertThrows(DslParseException.class, () -> parseYaml(yaml));
        assertTrue(ex.getMessage().contains("timeout_seconds"));
    }

    @Test
    @DisplayName("assert_* action 有 timeout_seconds 则通过")
    void assertActionsWithTimeoutPass() throws IOException {
        String yaml = """
                case_id: L3-TO-OK
                steps:
                  - id: check
                    action: assert_jdbc_data
                    inputs: {}
                    timeout_seconds: 30
                """;
        ScenarioConfig config = parseYaml(yaml);
        StepConfig step = (StepConfig) config.getBlocks().get(0);
        assertEquals(30, step.getTimeoutSeconds());
    }

    // ==================== parseDirectory / parseDirectorySafe ====================

    @Test
    @DisplayName("parseDirectory — 解析目录下所有 yaml 文件")
    void parseDirectoryAll(@TempDir Path tempDir) throws IOException {
        createFile(tempDir, "case1.yaml", """
                case_id: L3-DIR-01
                steps:
                  - id: s1
                    action: echo
                    inputs: { msg: hello }
                """);
        createFile(tempDir, "case2.yml", """
                case_id: L3-DIR-02
                steps:
                  - id: s2
                    action: echo
                    inputs: { msg: world }
                """);

        List<ScenarioConfig> configs = parser.parseDirectory(tempDir);
        assertEquals(2, configs.size());
        assertTrue(configs.stream().anyMatch(c -> "L3-DIR-01".equals(c.getCaseId())));
        assertTrue(configs.stream().anyMatch(c -> "L3-DIR-02".equals(c.getCaseId())));
    }

    @Test
    @DisplayName("parseDirectorySafe — 跳过损坏的 yaml 继续解析其他文件")
    void parseDirectorySafeSkipsBroken(@TempDir Path tempDir) throws IOException {
        createFile(tempDir, "good.yaml", """
                case_id: L3-SAFE-01
                steps:
                  - id: ok
                    action: echo
                """);
        // 合法 YAML 结构但缺少 case_id → 触发 DslParseException 被安全跳过
        createFile(tempDir, "broken.yaml", """
                steps:
                  - id: x
                    action: test
                """);
        createFile(tempDir, "good2.yaml", """
                case_id: L3-SAFE-02
                steps:
                  - id: ok2
                    action: echo
                """);

        List<ScenarioConfig> configs = parser.parseDirectorySafe(tempDir);
        assertEquals(2, configs.size());
        assertTrue(configs.stream().anyMatch(c -> "L3-SAFE-01".equals(c.getCaseId())));
        assertTrue(configs.stream().anyMatch(c -> "L3-SAFE-02".equals(c.getCaseId())));
        assertFalse(configs.stream().anyMatch(c -> "L3-BAD-01".equals(c.getCaseId())));
    }

    @Test
    @DisplayName("parseDirectorySafe — 全坏文件返回空列表不抛异常")
    void parseDirectorySafeAllBroken(@TempDir Path tempDir) throws IOException {
        // 这些文件能被 SnakeYAML 解析但触发 DslParseException
        createFile(tempDir, "bad1.yaml", """
                steps:
                  - id: a
                    action: x
                """);
        createFile(tempDir, "bad2.yaml", """
                case_id: BAD2
                steps: []
                """);

        List<ScenarioConfig> configs = parser.parseDirectorySafe(tempDir);
        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    // ==================== Helper Methods ====================

    private ScenarioConfig parseYaml(String yamlContent) {
        try {
            Path temp = Files.createTempFile("dsl-test-", ".yaml");
            Files.writeString(temp, yamlContent);
            try {
                return parser.parse(temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createFile(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }
}
