# Connector integration-test acceptance standard

A connector is considered covered only when the connector API is invoked against a real database and the result is asserted. Engine configuration, unit tests, or a similar scenario do not count as coverage.

| # | Category | Required connector-level assertion | Default |
|---|---|---|---|
| 1 | Basic full source | `discoverSchema`, `batchCount`, and `batchRead` return the prepared rows | Yes |
| 2 | Basic incremental source | `getStreamOffset` plus `streamRead` returns committed insert/update/delete events | Yes |
| 3 | TPCC full source | Every BenchmarkSQL table is discovered and every row is returned through `batchRead` | Opt-in `tpcc` |
| 4 | TPCC incremental source | A BenchmarkSQL workload changes multiple tables and CDC resumes from a saved offset | Opt-in `tpcc` |
| 5 | Basic target | Create, insert, update, delete, clear, and drop are verified through connector APIs | Yes |
| 6 | TPCC target | TPCC tables are recreated and their rows are written through `writeRecord`, then counted | Opt-in `tpcc` |
| 7 | Four DDL cases | Add, drop, rename, and alter a field are verified; source-DDL parsing is added when supported | Yes |
| 8 | Offset reset/resume | Save an offset, write while stopped, and read the historical rows from that offset | Yes |
| 9 | Full types source | All supported Tap type families plus connector-specific native boundary types pass full/CDC reads | Yes |
| 10 | Full types target | Engine codec wrap/unwrap and database round-trip pass for all supported Tap type families | Yes |
| 11 | Transactions | Commit, full rollback, savepoint rollback, long commit, and uncommitted visibility are asserted | Long transaction opt-in |
| 12 | Large fields | At least 1 MiB text/binary values pass both `batchRead` and `streamRead` | Yes |

TPCC and long-transaction tests are excluded from normal builds. Explicit activation must fail when prerequisites are missing; it must not silently skip.
