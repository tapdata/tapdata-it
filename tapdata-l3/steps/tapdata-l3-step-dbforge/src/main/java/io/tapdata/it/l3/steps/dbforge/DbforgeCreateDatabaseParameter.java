package io.tapdata.it.l3.steps.dbforge;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * dbforge_create_database Step 专属参数配置。
 *
 * <pre>
 * inputs:
 *   db_type: mysql       # 必填
 *   mode: dedicated       # 可选
 *   topology: single      # 可选
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DbforgeCreateDatabaseParameter extends ParameterConfig {

    /** 数据库类型（mysql / postgresql / mongodb / ...）— 必填。 */
    private String dbType;

    /** lease 模式（dedicated / shared）— 可选。 */
    private String mode;

    /** 拓扑模式（single / replica）— 可选。 */
    private String topology;
}
