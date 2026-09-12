package io.tapdata.it.tpcc;

import java.util.List;
import java.util.Map;

public interface TpccAdapter {

    List<String> tableNames();

    boolean isPrepared() throws Exception;

    void prepare(TpccConfig config) throws Exception;

    Map<String, Long> currentRowCounts() throws Exception;

    void runWorkload(TpccConfig config) throws Exception;

    void verifyConsistency() throws Exception;

    void cleanup() throws Exception;
}
