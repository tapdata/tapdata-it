# DBForge integration-test configuration

Connector IT defaults to the checked-in JSON connection configuration. Set
`CONNECTOR_IT_CONFIG_SOURCE=dbforge` to acquire one DBForge lease for the whole test class.
The test releases that lease in `@AfterAll`; DBForge TTL is the recovery path when Maven exits
abnormally.

```bash
CONNECTOR_IT_CONFIG_SOURCE=dbforge \
CONNECTOR_IT_DBFORGE_URL=http://<dbforge-host>:<port> \
CONNECTOR_IT_DBFORGE_TOKEN="$DBFORGE_TOKEN" \
CONNECTOR_IT_DBFORGE_TTL_MINUTES=60 \
mvn -pl connectors/<connector> -DskipITs=false -Dit.test=<ConnectorIT> verify
```

The equivalent Maven properties are `connector.it.config.source`,
`connector.it.dbforge.url`, `connector.it.dbforge.token`, and
`connector.it.dbforge.ttl.minutes`. `connector.it.dbforge.owner` overrides the audit owner and
`connector.it.dbforge.endpoint.scope` accepts `internal` or `external` when the test runner is
inside or outside the Kubernetes cluster respectively. Lease creation waits up to 900 seconds by
default, matching DBForge's provisioning deadline and covering OceanBase's first-start I/O
benchmark; override it with `connector.it.dbforge.provision.timeout.seconds` or
`CONNECTOR_IT_DBFORGE_PROVISION_TIMEOUT_SECONDS` for slower database images.

| Connector IT | DBForge request |
| --- | --- |
| MySQL | `mysql/dedicated/single` |
| MongoDB | `mongodb/dedicated/replicaset` |
| OceanBase MySQL | `oceanbase-mysql/dedicated/single` |
| DB2 for i | `as400/shared/single` |
| Dameng | `dameng/dedicated/single` |
| SQL Server | `sqlserver/dedicated/single` |
| OceanBase Oracle | `oceanbase-oracle/dedicated/single` |

DB2 LUW, Oracle, and Sybase ITs keep their JSON source because the deployed DBForge capability
set has no matching `db_type`. DBForge request failures are intentional test failures and never
fall back to JSON silently.
