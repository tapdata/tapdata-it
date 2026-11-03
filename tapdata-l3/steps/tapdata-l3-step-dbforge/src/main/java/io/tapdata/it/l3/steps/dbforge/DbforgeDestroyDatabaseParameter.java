package io.tapdata.it.l3.steps.dbforge;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * dbforge_destroy_database Step 专属参数配置。
 *
 * <pre>
 * inputs:
 *   lease_id: "#{ #steps['create_mysql'].outputs['lease_id'] }"
 * </pre>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DbforgeDestroyDatabaseParameter extends ParameterConfig {

    /** 要释放的 dbforge lease ID — 必填。 */
    private String leaseId;
}
