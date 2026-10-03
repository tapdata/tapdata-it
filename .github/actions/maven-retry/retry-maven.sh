#!/usr/bin/env bash
# Retry Maven commands for transient Nexus/proxy failures without hiding the
# final Maven exit code. Callers pass -U so Maven refreshes missing or stale
# artifact metadata on each attempt.
set -euo pipefail

if (($# == 0)); then
  echo "Usage: $0 <maven-command> [args...]" >&2
  exit 2
fi

max_attempts="${MAVEN_RETRY_MAX_ATTEMPTS:-3}"
base_delay="${MAVEN_RETRY_DELAY_SECONDS:-5}"
if [[ ! "$max_attempts" =~ ^[1-9][0-9]*$ || ! "$base_delay" =~ ^[0-9]+$ ]]; then
  echo "MAVEN_RETRY_MAX_ATTEMPTS must be positive and MAVEN_RETRY_DELAY_SECONDS non-negative" >&2
  exit 2
fi

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  echo "Maven attempt ${attempt}/${max_attempts}: $*"
  if "$@"; then
    exit 0
  else
    status=$?
  fi

  if ((attempt == max_attempts)); then
    echo "::error::Maven command failed after ${max_attempts} attempts (exit ${status})"
    exit "$status"
  fi

  delay=$((base_delay * (1 << (attempt - 1))))
  ((delay > 60)) && delay=60
  echo "Maven command failed with exit ${status}; retrying in ${delay}s"
  sleep "$delay"
done
