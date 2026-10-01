# K3s runner-it

This deployment runs the four `runner-it` instances plus the remaining build,
cloud, scan, and common-build pools on the `tapserver193` K3s node. All pools
use the same node-local persistent cache PVCs so a Pod restart does not discard
the recovered data:

- `runner-build-source` (300Gi): shared Git working copies under
  `/root/build_source`.
- `runner-maven-cache` (300Gi): durable backing volume for Maven repositories.
  Each runner Pod gets its own `/root/.m2` directory at
  `_runner-cache/<pod-name>`; the first start seeds it from the recovered cache
  at the PVC root. Runner Maven writes therefore do not race across Pods, and
  each Pod's cache survives container restarts. A newly created Pod gets a new
  directory seeded from the baseline rather than inheriting another Pod's
  mutable cache.
- `runner-node-cache` (100Gi): shared npm cache at `/root/.npm`.
- `runner-pkg-cache` (100Gi): shared pkg cache at `/root/.pkg-cache`.

The `local-path` volumes are durable across Pod restarts, but are local to
`tapserver193`; they are not replicated to `tapserver179`. Runner workspaces
remain isolated per runner where configured, and are not treated as the shared
cache.

All Maven artifact resolution is mirrored through
`https://nexus.tapdata.net/repository/maven-public/` by the runner's
`settings.xml`. Existing settings (including server credentials) are retained;
the Nexus mirror is added or updated without embedding credentials in this
repository.

The following runtime Secrets are intentionally not stored in Git:

- `runner-it-auth`: GitHub organization runner registration credentials.
- `runner-it-ssh`: the proxy SSH key and SSH config.
- `runner-it-truststore`: the Java 17 truststore.

The runner image also requires access to a Docker-compatible socket because
the Connector IT workflow uses Testcontainers. The first migration uses the
rootful Podman API socket on `tapserver193` as a compatibility bridge; it
should be replaced by a dedicated build/test runtime before moving to a
multi-node cluster.

Apply with:

```bash
kubectl apply -k deploy/k3s/runner-it
kubectl -n tapdata-it get pods -o wide
```

The one-time `runner-cache-sync-job.yaml` can restore Maven/pkg data from the
file server and shallow-clone repositories that the supplied GitHub PAT can
read. It requires the runtime-only Secret `runner-rsync-auth` (not stored in
Git). Private repositories that the PAT cannot read must be seeded from a
backup or a local working copy; the sync job does not put credentials in the
repository.
