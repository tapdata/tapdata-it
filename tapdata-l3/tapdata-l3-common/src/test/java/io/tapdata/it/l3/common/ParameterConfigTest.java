package io.tapdata.it.l3.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StepConfig.getInputParameterConfig() 的单元测试。
 * 覆盖：snake_case → camelCase 映射、基类字段自动填充、缓存语义、异常边界。
 */
class ParameterConfigTest {

    /** 一个真实的 ParameterConfig 子类 —— 模拟 Step 专属参数定义。 */
    public static class FakeStepParameter extends ParameterConfig {
        private String jdbcUrl;
        private String username;
        private String password;
        private String sql;
        private int pollInterval;         // int 装箱后 jackson 自动映射
        private boolean verbose;          // boolean — YAML 写 verbose: true
        private List<String> columns;     // List — 嵌套类型

        // getters — Lombok @Data would generate these
        public String getJdbcUrl() { return jdbcUrl; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public String getSql() { return sql; }
        public int getPollInterval() { return pollInterval; }
        public boolean isVerbose() { return verbose; }
        public List<String> getColumns() { return columns; }
    }

    /** 另一个不同的子类 —— 用来验证不同 Class 返回不同实例。 */
    public static class AnotherStepParameter extends ParameterConfig {
        private String token;
        public String getToken() { return token; }
    }

    // ==================== 基础映射 ====================

    @Nested
    @DisplayName("snake_case → camelCase 自动映射")
    class SnakeToCamel {

        @Test
        @DisplayName("单下划线字段正确映射")
        void singleUnderscore() {
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("jdbc_url", "jdbc:mysql://localhost:3306/test");
            inputs.put("username", "root");
            inputs.put("poll_interval", 5);

            StepConfig config = StepConfig.builder()
                    .id("step-1")
                    .action("fake_action")
                    .inputs(inputs)
                    .timeoutSeconds(60)
                    .build();

            FakeStepParameter p = config.getInputParameterConfig(FakeStepParameter.class);

            assertEquals("jdbc:mysql://localhost:3306/test", p.getJdbcUrl());
            assertEquals("root", p.getUsername());
            assertEquals(5, p.getPollInterval());
        }

        @Test
        @DisplayName("多下划线字段正确映射（driver_class → driverClass 同理测试）")
        void multipleUnderscores() {
            // 在 FakeStepParameter 里没有 driverClass 字段，但我们验证 unknown 字段被忽略
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("jdbc_url", "jdbc:mysql://localhost");
            inputs.put("this_is_a_weird_field", "value");

            StepConfig config = StepConfig.builder()
                    .action("fake_action")
                    .inputs(inputs)
                    .build();

            FakeStepParameter p = config.getInputParameterConfig(FakeStepParameter.class);

            assertEquals("jdbc:mysql://localhost", p.getJdbcUrl());
            // unknown 字段不应报错
            assertNull(p.getUsername());
        }

        @Test
        @DisplayName("boolean 和 List 类型字段正确映射")
        void booleanAndList() {
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("verbose", true);
            inputs.put("columns", List.of("id", "name", "age"));

            StepConfig config = StepConfig.builder()
                    .action("fake_action")
                    .inputs(inputs)
                    .build();

            FakeStepParameter p = config.getInputParameterConfig(FakeStepParameter.class);

            assertTrue(p.isVerbose());
            assertEquals(List.of("id", "name", "age"), p.getColumns());
        }
    }

    // ==================== 基类通用字段自动填充 ====================

    @Nested
    @DisplayName("基类通用字段（stepId / action / timeoutSeconds）自动填充")
    class BaseFieldsAutoFill {

        @Test
        void allThreeFieldsFilledFromStepConfig() {
            StepConfig config = StepConfig.builder()
                    .id("my-step-id")
                    .action("dbforge_execute_sql")
                    .timeoutSeconds(120)
                    .inputs(Map.of("jdbc_url", "x"))
                    .build();

            FakeStepParameter p = config.getInputParameterConfig(FakeStepParameter.class);

            assertEquals("my-step-id", p.getStepId());
            assertEquals("dbforge_execute_sql", p.getAction());
            assertEquals(120, p.getTimeoutSeconds());
        }

        @Test
        void timeoutDefaultIsMinusOneWhenStepConfigNoTimeout() {
            StepConfig config = StepConfig.builder()
                    .action("fake")
                    .inputs(Map.of("jdbc_url", "x"))
                    .build();

            FakeStepParameter p = config.getInputParameterConfig(FakeStepParameter.class);

            assertEquals(-1, p.getTimeoutSeconds()); // 默认值
        }
    }

    // ==================== 缓存语义 ====================

    @Nested
    @DisplayName("缓存：同 StepConfig + 同 Class → 同一实例")
    class Cache {

        @Test
        void sameClassReturnsSameInstance() {
            StepConfig config = StepConfig.builder()
                    .action("fake")
                    .inputs(Map.of("jdbc_url", "x"))
                    .build();

            FakeStepParameter p1 = config.getInputParameterConfig(FakeStepParameter.class);
            FakeStepParameter p2 = config.getInputParameterConfig(FakeStepParameter.class);

            assertSame(p1, p2, "多次调用应该返回同一个缓存实例");
        }

        @Test
        void differentClassesReturnDifferentInstances() {
            StepConfig config = StepConfig.builder()
                    .action("fake")
                    .inputs(Map.of("jdbc_url", "x", "token", "abc123"))
                    .build();

            FakeStepParameter p1 = config.getInputParameterConfig(FakeStepParameter.class);
            AnotherStepParameter p2 = config.getInputParameterConfig(AnotherStepParameter.class);

            assertNotSame(p1, p2);
            // FakeStepParameter 没有 token 字段 —— 它只拿到自己关心的 jdbc_url
            assertEquals("x", p1.getJdbcUrl());
            // AnotherStepParameter 拿到 token
            assertEquals("abc123", p2.getToken());
        }

        @Test
        void clearCacheForcesRebuild() {
            StepConfig config = StepConfig.builder()
                    .action("fake")
                    .inputs(new LinkedHashMap<>(Map.of("jdbc_url", "original")))
                    .build();

            FakeStepParameter p1 = config.getInputParameterConfig(FakeStepParameter.class);
            assertEquals("original", p1.getJdbcUrl());

            // 修改 inputs（模拟 SpEL 二次求值后）
            config.getInputs().put("jdbc_url", "updated");
            config.clearParameterConfigCache();

            FakeStepParameter p2 = config.getInputParameterConfig(FakeStepParameter.class);
            assertNotSame(p1, p2);
            assertEquals("updated", p2.getJdbcUrl());
        }

        @Test
        void differentStepConfigsHaveIndependentCaches() {
            StepConfig config1 = StepConfig.builder()
                    .id("c1").action("a").inputs(Map.of("jdbc_url", "url-1")).build();
            StepConfig config2 = StepConfig.builder()
                    .id("c2").action("a").inputs(Map.of("jdbc_url", "url-2")).build();

            FakeStepParameter p1 = config1.getInputParameterConfig(FakeStepParameter.class);
            FakeStepParameter p2 = config2.getInputParameterConfig(FakeStepParameter.class);

            assertNotSame(p1, p2);
            assertEquals("url-1", p1.getJdbcUrl());
            assertEquals("url-2", p2.getJdbcUrl());
        }
    }

    // ==================== null / 空 inputs 边界 ====================

    @Nested
    @DisplayName("null / 空 inputs 边界")
    class NullEmptyInputs {

        @Test
        void nullInputsCreatesEmptyInstanceViaReflection() {
            StepConfig config = StepConfig.builder()
                    .id("step-1")
                    .action("fake_action")
                    .inputs(null)
                    .timeoutSeconds(30)
                    .build();

            FakeStepParameter p = config.getInputParameterConfig(FakeStepParameter.class);

            assertNotNull(p);
            assertNull(p.getJdbcUrl());
            assertEquals("step-1", p.getStepId());
            assertEquals("fake_action", p.getAction());
            assertEquals(30, p.getTimeoutSeconds());
        }

        @Test
        void emptyMapInputsCreatesEmptyInstance() {
            StepConfig config = StepConfig.builder()
                    .action("fake")
                    .inputs(new LinkedHashMap<>())
                    .build();

            FakeStepParameter p = config.getInputParameterConfig(FakeStepParameter.class);
            assertNotNull(p);
            assertNull(p.getSql());
        }
    }

    // ==================== 异常边界 ====================

    @Nested
    @DisplayName("异常边界：非法参数")
    class EdgeCases {

        @Test
        @DisplayName("null parameterClass → IllegalArgumentException")
        void nullParameterClassThrows() {
            StepConfig config = StepConfig.builder()
                    .action("fake")
                    .inputs(Map.of())
                    .build();

            assertThrows(IllegalArgumentException.class,
                    () -> config.getInputParameterConfig(null));
        }

        @Test
        @DisplayName("非 ParameterConfig 子类 → IllegalArgumentException（泛型运行时检查）")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void nonParameterConfigSubclassThrows() {
            StepConfig config = StepConfig.builder()
                    .action("fake")
                    .inputs(Map.of())
                    .build();

            // 用 raw type 绕过编译期泛型约束，测试运行时检查
            Class stringClass = String.class;
            assertThrows(IllegalArgumentException.class,
                    () -> config.getInputParameterConfig(stringClass));
        }

        @Test
        @DisplayName("基类 ParameterConfig.class 本身也应允许（反射构造）")
        void rawParameterConfigClassWorks() {
            StepConfig config = StepConfig.builder()
                    .id("x").action("y").timeoutSeconds(5)
                    .inputs(Map.of("jdbc_url", "ignored"))
                    .build();

            ParameterConfig p = config.getInputParameterConfig(ParameterConfig.class);
            assertNotNull(p);
            assertEquals("x", p.getStepId());
            assertEquals("y", p.getAction());
            assertEquals(5, p.getTimeoutSeconds());
        }
    }

    // ==================== 保留 getInputs() 方法 ====================

    @Nested
    @DisplayName("向后兼容：getInputs() 仍然可用")
    class BackwardCompat {

        @Test
        void getInputsStillWorks() {
            Map<String, Object> inputs = Map.of("jdbc_url", "jdbc:mysql://x", "sql", "SELECT 1");
            StepConfig config = StepConfig.builder()
                    .action("fake")
                    .inputs(inputs)
                    .build();

            // 老代码仍然能拿到 Map
            assertEquals("jdbc:mysql://x", config.getInputs().get("jdbc_url"));
            assertEquals("SELECT 1", config.getInputs().get("sql"));
        }
    }
}
