package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * tm_create_datasource Step 专属参数配置。
 *
 * <pre>
 * inputs:
 *   name: "l3_test_mysql"            # 必填
 *   database_type: "Mysql"           # 必填
 *   connection_type: "source_and_target" # 可选，默认 source_and_target
 *   database_host: "..."             # 必填
 *   database_port: 3306              # 必填
 *   database_username: "root"        # 必填
 *   database_password: "xxx"         # 可选
 *   database_name: "test"            # 可选
 *   pdk_hash: "a5af410b..."          # 可选，缺省自动从 /api/DatabaseTypes 解析
 *   config: { ssl: false }           # 可选，额外 PDK config override
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TmCreateDatasourceParameter extends ParameterConfig {

    /** Connection 名称 — 必填。 */
    private String name;

    /** 数据库类型（Mysql / PostgreSQL / MongoDB / ...）— 必填。 */
    private String databaseType;

    /** 连接类型（source / target / source_and_target）— 默认 source_and_target。 */
    private String connectionType = "source_and_target";

    /** 数据库主机 — 必填。 */
    private String databaseHost;

    /** 数据库端口 — 必填。 */
    private int databasePort;

    /** 数据库用户名 — 必填。 */
    private String databaseUsername;

    /** 数据库密码 — 可选。 */
    private String databasePassword;

    /** 数据库名 — 可选。 */
    private String databaseName;

    /** PDK hash — 可选，缺省时调用 /api/DatabaseTypes 自动解析。 */
    private String pdkHash;

    /** 额外 PDK config override — 可选，会与标准字段合并后传给 TM。 */
    private Map<String, Object> config;
}
