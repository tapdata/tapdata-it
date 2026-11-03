package io.tapdata.it.l3.core;

import io.tapdata.it.l3.core.plugin.PluginCenter;
import io.tapdata.it.l3.core.plugin.PluginCenterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot application entry point for tapdata-l3-core.
 * <p>
 * This is the ONLY module in the L3 framework that bootstraps Spring Boot.
 * The CLI module (tapdata-l3-cli) will {@link SpringApplication#run} this class.
 * <p>
 * Step handler 注册由 {@link PluginCenter} 自动完成：
 * <ul>
 *   <li>内置 Step —— Spring Boot 扫描 @Component 后通过构造器注入 {@code List<ActionHandler>}</li>
 *   <li>外置插件 Step —— PF4J 扫描插件目录 + @Extension 注解发现</li>
 * </ul>
 * 本类不再手动注册任何 handler。
 */
@SpringBootApplication(scanBasePackages = "io.tapdata.it.l3")
@EnableConfigurationProperties(PluginCenterProperties.class)
public class L3CoreApplication {

    private static final Logger log = LoggerFactory.getLogger(L3CoreApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(L3CoreApplication.class, args);
    }
}
