# K3s runner-it

## Configuration audit (2026-10-12)

The manifests have been checked against all 16 live Deployment definitions
(24 runner replicas). Images use `github-action-runner:5.12`; build-common and
IT pools keep 4 CPU / 12Gi limits, while enterprise/cloud/scan pools keep
4 CPU / 8Gi limits. Requests remain 2 CPU / 4Gi. This change does not deploy
or restart any runner, change pool concurrency, or modify workflow ordering.

The cache-prune CronJob is new and was not present in the live cluster at
audit time. It schedules at 03:17 Asia/Hong_Kong with `DRY_RUN=true`; deletion
requires explicit operator approval and a subsequent configuration change.
The cache seed Job is deliberately excluded from `kustomization.yaml`.

The existing `buildserver.tapdata.io` host alias still points at the legacy
170 server, including in the live deployments. It is not the Harbor alias.
Do not interpret this PR as removing every dependency on 170: inspect callers
and migrate the buildserver service separately before retiring that host.
Likewise, runner host aliases do not configure DNS inside external BuildKit
containers; Harbor DNS must be maintained at the infrastructure layer.

Python bytecode (`__pycache__`, `*.pyc`) is ignored, not a deployment asset.

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
  mutable cache. Deployment pools with multiple replicas must not use the
  Deployment name as a shared cache key; that would reintroduce concurrent writes.
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

## Retiring orphan Maven caches

`runner-cache-prune.yaml` adds a daily CronJob with namespace-only permission
to list Pods. It skips live Pods, unknown directory names and symlinks, and
never deletes the PVC root or seed data outside `_runner-cache/`.
After deletion is enabled, it marks a missing Pod's cache with `.orphaned`
on first observation, then waits at least `GRACE_DAYS` (default 7) before
deletion. It checks the live Pod list again immediately before each deletion.
API errors abort cleanup; they are not interpreted as an empty Pod list.

The manifest defaults to `DRY_RUN=true`: it logs orphan candidates but does
not delete caches or create markers. Inspect the logs and PVC usage, then
explicitly change the manifest to `DRY_RUN=false` and apply it to enable
reclamation. The grace period starts with the first non-dry-run observation.
This default is a deployment safety gate, **not active disk reclamation**.
Old caches remain until cleanup is enabled; monitor capacity in the meantime.
Reclamation is destructive: use a volume backup if you need to retain orphan
artifacts.

## Job-specific Maven credentials

Connector IT no longer replaces the persistent `~/.m2/settings.xml` with
`MAVEN_SETTINGS`. It merges the Secret into a mode-600 temporary settings file
under `RUNNER_TEMP`. Secret server credentials and profiles override matching
IDs; runner-only entries and pluginGroups are retained. The Secret cannot
override runner mirrors or `localRepository`; Nexus routing and private-cache
isolation remain authoritative. Missing required Nexus configuration fails
explicitly rather than silently changing routing.

All IT Maven calls use the retry action, which adds
`--settings "$MAVEN_JOB_SETTINGS"` when this temporary file exists.
An always-run cleanup step removes it on normal completion/failure. A forced
runner termination can bypass cleanup; runner temp cleanup on the next job
must still be retained. Merge both workflow and retry-action changes into
`tapdata-it/main` together, since reusable action references use `@main`.

## Office scan Gitee SSH credentials

The `runner-scan` and `runner-scan-common` Deployments mask the runner image's
legacy `/root/.ssh/id_rsa` with a read-only empty file. Workflows that need Gitee
SSH must use their own repository's `GITEE_SSH_KEY` and `GITEE_KNOWN_HOSTS`
Secrets in a job-scoped temporary directory, then remove that directory at job
completion. Do not bake or mount a Gitee private key into these shared runners;
otherwise jobs can inherit an identity from an earlier run.

The following runtime Secrets are intentionally not stored in Git:

- `runner-it-auth`: GitHub organization runner registration credentials.
- `runner-it-ssh`: the proxy SSH key and SSH config.
- `runner-it-truststore-v2`: the Java 17 truststore, with key `cacerts`.
  The cache patch replaces the bases' `runner-it-truststore` reference with
  this v2 Secret in the rendered deployment.
- `runner-github-token`: key `GITHUB_TOKEN`, a PAT with read access to the
  repositories restored by the sync job or fetched by runners.
- `runner-rsync-auth`: key `RSYNC_PASSWORD`, needed only by the cache sync Job.

`runner-git-askpass.yaml` defines the non-secret ConfigMap
`runner-git-askpass`, with key `runner-git-askpass`. Kustomize includes it
automatically. The mounted script supplies GitHub HTTPS credentials from the
runtime environment, and rejects prompts for other hosts.

Create the PAT Secret from a protected, runtime-only file (never commit the
file or a populated Secret manifest):

```bash
kubectl apply -f deploy/k3s/runner-it/namespace.yaml
kubectl -n tapdata-it create secret generic runner-github-token \
  --from-file=GITHUB_TOKEN=/secure/path/github-token \
  --dry-run=client -o yaml | kubectl apply -f -
```

The token file should contain the PAT only, with file mode 600. Registration,
SSH, and truststore Secrets must also exist before applying the deployments.
Missing required Secrets will prevent Pods from starting; do not make them
optional to mask misconfiguration.

The cache patch intentionally targets **all** GitHub runner Deployments,
including cloud and scan pools: they get private Maven caches, shared source
and npm/pkg caches, the v2 Java truststore and GitHub askpass credentials.
This is the existing deployment scope, not an IT-only patch. Review PAT
permissions accordingly; changing pool scope is a separate deployment change.

The runner image also requires access to a Docker-compatible socket because
the Connector IT workflow uses Testcontainers. The first migration uses the
rootful Podman API socket on `tapserver193` as a compatibility bridge; it
should be replaced by a dedicated build/test runtime before moving to a
multi-node cluster.

Apply with:

```bash
kubectl -n tapdata-it get secret runner-it-auth runner-it-ssh \
  runner-it-truststore-v2 runner-github-token
kubectl apply -k deploy/k3s/runner-it
kubectl -n tapdata-it get pods -o wide
```

The one-time `runner-cache-sync-job.yaml` can restore Maven/pkg data from the
file server and shallow-clone repositories that the supplied GitHub PAT can
read. It requires the runtime-only Secret `runner-rsync-auth` (not stored in
Git). Private repositories that the PAT cannot read must be seeded from a
backup or a local working copy; the sync job does not put credentials in the
repository.

Run the cache sync **before** starting runners and seeding private caches.
Do not rerun it with active runners: it replaces shared baseline/pkg/source
data and does not refresh already seeded private Maven repositories.
The Maven rsync explicitly excludes `/_runner-cache/`, so an accidental rerun
does not delete existing runner-private caches. Do not add `--delete-excluded`.
