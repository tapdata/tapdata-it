package io.tapdata.it.l3.core.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PluginCenter 单元测试 — 默认注册表 YAML 格式校验。
 *
 * steps-registry.yaml 位于 CLI 模块 resources（打包时进 fat jar classpath），
 * core 模块单测环境不会自动带上。这里做格式校验（假设内容）+ 直接解析一份模拟 YAML。
 */
class PluginCenterTest {

    /** 模拟 CLI 的 steps-registry.yaml 内容 */
    private static final String SAMPLE_REGISTRY = """
            dbforge_create_database: "io.tapdata.it.l3:tapdata-l3-step-dbforge:1.0.0-SNAPSHOT"
            dbforge_destroy_database: "io.tapdata.it.l3:tapdata-l3-step-dbforge:1.0.0-SNAPSHOT"
            dbforge_execute_sql: "io.tapdata.it.l3:tapdata-l3-step-dbforge:1.0.0-SNAPSHOT"
            tm_login: "io.tapdata.it.l3:tapdata-l3-step-tm:1.0.0-SNAPSHOT"
            tm_create_datasource: "io.tapdata.it.l3:tapdata-l3-step-tm:1.0.0-SNAPSHOT"
            tm_delete_datasource: "io.tapdata.it.l3:tapdata-l3-step-tm:1.0.0-SNAPSHOT"
            tm_list_datasources: "io.tapdata.it.l3:tapdata-l3-step-tm:1.0.0-SNAPSHOT"
            tm_get_task: "io.tapdata.it.l3:tapdata-l3-step-tm:1.0.0-SNAPSHOT"
            assert_jdbc_data: "io.tapdata.it.l3:tapdata-l3-step-assert-jdbc:1.0.0-SNAPSHOT"
            """;

    @Test
    @DisplayName("默认注册表 YAML 能被 SnakeYAML 正确解析")
    void yamlParsesToMap() {
        Map<String, Object> registry = new org.yaml.snakeyaml.Yaml().load(SAMPLE_REGISTRY);

        assertNotNull(registry);
        assertEquals(9, registry.size());
        assertTrue(registry.containsKey("dbforge_create_database"));
        assertTrue(registry.containsKey("tm_create_datasource"));
        assertTrue(registry.containsKey("assert_jdbc_data"));
    }

    @Test
    @DisplayName("Maven 坐标格式校验 — 每个坐标都是 groupId:artifactId:version (3 段)")
    void allCoordsHave3Segments() {
        Map<String, Object> registry = new org.yaml.snakeyaml.Yaml().load(SAMPLE_REGISTRY);

        for (var e : registry.entrySet()) {
            String coord = e.getValue().toString();
            String[] parts = coord.split(":");
            assertEquals(3, parts.length, "coord '" + e.getKey() + "' must be groupId:artifactId:version, got: " + coord);

            // 每段都非空
            for (String p : parts) {
                assertFalse(p.isBlank(), "coord part is blank in '" + coord + "'");
            }
        }
    }

    @Test
    @DisplayName("内置 step (echo/sleep) 不在注册表里 — 它们走 classpath scan")
    void coreBuiltinsNotInRegistry() {
        Map<String, Object> registry = new org.yaml.snakeyaml.Yaml().load(SAMPLE_REGISTRY);

        assertFalse(registry.containsKey("echo"), "echo is a core built-in, not external plugin");
        assertFalse(registry.containsKey("sleep"), "sleep is a core built-in, not external plugin");
    }

    @Test
    @DisplayName("同一坐标可以被多个 action 共享 (dbforge jar 有 3 个 action)")
    void oneJarMultipleActions() {
        Map<String, Object> registry = new org.yaml.snakeyaml.Yaml().load(SAMPLE_REGISTRY);

        long dbforgeActions = registry.entrySet().stream()
                .filter(e -> e.getValue().toString().contains("tapdata-l3-step-dbforge"))
                .count();
        assertEquals(3, dbforgeActions, "dbforge jar should have 3 actions registered");

        long tmActions = registry.entrySet().stream()
                .filter(e -> e.getValue().toString().contains("tapdata-l3-step-tm"))
                .count();
        assertEquals(5, tmActions, "tm jar should have 5 actions registered");
    }

    @Test
    @DisplayName("PluginCenter 构造器不再依赖 Spring 自动注入 builtInHandlers")
    void pluginCenterConstructorIsPlain() {
        // 验证 PluginCenter 只有 PluginCenterProperties 一个构造参数
        var ctors = PluginCenter.class.getDeclaredConstructors();
        assertEquals(1, ctors.length,
                "PluginCenter should have exactly one public constructor taking only PluginCenterProperties");
        var paramTypes = ctors[0].getParameterTypes();
        assertEquals(1, paramTypes.length);
        assertEquals(PluginCenterProperties.class, paramTypes[0]);
    }
}
