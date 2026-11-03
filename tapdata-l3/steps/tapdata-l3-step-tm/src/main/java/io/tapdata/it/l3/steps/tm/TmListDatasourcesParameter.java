package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * tm_list_datasources Step 专属参数配置。
 *
 * <pre>
 * inputs:
 *   database_type: "Mysql"   # 可选过滤
 *   limit: 10                # 可选，默认 50
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TmListDatasourcesParameter extends ParameterConfig {

    /** 数据库类型过滤 — 可选。 */
    private String databaseType;

    /** 返回数量上限 — 默认 50。 */
    private int limit = 50;
}
