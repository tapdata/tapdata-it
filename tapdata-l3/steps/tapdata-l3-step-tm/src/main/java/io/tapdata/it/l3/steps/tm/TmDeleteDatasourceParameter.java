package io.tapdata.it.l3.steps.tm;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * tm_delete_datasource Step 专属参数配置。
 *
 * <pre>
 * inputs:
 *   connection_id: "#{ #steps['create_conn'].outputs['connection_id'] }"
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TmDeleteDatasourceParameter extends ParameterConfig {

    /** 要删除的 Connection ID — 必填。 */
    private String connectionId;
}
