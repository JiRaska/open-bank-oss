#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Make sure an image's ECR repository exists before something tries to push to it.
#
# WHY THIS EXISTS
#   A new service's ECR repository is declared NOWHERE. `git grep aws_ecr_repository` over this
#   repository returns nothing: all existing repos were created by hand, outside Terraform. So the
#   first build of every new service compiles cleanly, runs the whole cold-cache Gradle build, and
#   then dies at the push with
#
#     name unknown: The repository with name 'openbank-<svc>' does not exist in the registry
#
#   Measured on openbank-delegation-service's first build, 2026-08-02 (issue #3423).
#
#   Nothing upstream can catch it, and each near-miss looks like a different, expected state:
#     - `deploy-drift-declaration` only checks the pin's SHAPE (`sandbox-<hex>`).
#     - the fleet attestation gate reports the image as ABSENT — which is the CORRECT state for a
#       first registration, and cannot distinguish "tag not built yet" from "repo does not exist".
#     - Kyverno blocks ArgoCD's dry-run diff for an absent tag, so the app sits at sync status
#       Unknown — again indistinguishable.
#   The first real signal is a red build, after the expensive part has already been paid for.
#
# WHY CREATE RATHER THAN GATE
#   #3423 proposes a PR-time gate asserting every gitops image pin has a repository. That gate
#   would need AWS credentials on the PR lane, which runs on ubuntu-latest with none, to answer a
#   question the push job is *already* authenticated for. Creating it at the point of use costs no
#   new credential and removes the failure outright rather than moving it earlier.
#
#   This does not make the registry declarative; declaring the repos in Terraform (#3423 option 1)
#   is still worth doing and composes with this. Note the trap called out there: a hand-typed
#   `for_each` service list becomes a second copy of ALL_SERVICES that can drift from it — derive
#   it from one source or do not add it.
#
# IDEMPOTENT BY CONSTRUCTION
#   describe-repositories runs first, so the common case (every existing repo, every build) makes
#   no mutating call at all. A create that loses a race against a concurrent build of the same new
#   service is a SUCCESS, not a failure — re-checked with describe rather than by parsing the
#   error string, since RepositoryAlreadyExistsException and a permissions denial are both just a
#   non-zero exit here.
#
# SETTINGS mirror the fleet (copied from openbank-campaign-service): scanOnPush, AES256, MUTABLE.
# Nothing else has to change for a new repo to be covered — `ecr-image-scanning.tf` is deliberately
# registry-level with an `openbank-*` wildcard, and says in place that this is so it covers repos
# that do not exist yet, "with no per-repo resource to forget".
#
# Usage:
#   ensure-ecr-repository.sh <repository-name> [region]
#   ensure-ecr-repository.sh --self-test
set -euo pipefail

selftest() {
  # The gate this script IS: prove it does not call create when the repo exists, does call it when
  # it does not, and fails loudly when creation is impossible. Runs against a stub `aws` on PATH —
  # and the stub is validated first, because a silent passthrough would run the real AWS CLI.
  local tmp rc out
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
  cat > "${tmp}/aws" <<'STUB'
#!/usr/bin/env bash
echo "aws $*" >> "${STUB_CALLS}"
case "$1 $2" in
  "ecr describe-repositories") [ "${REPO_EXISTS:-0}" = "1" ] && exit 0 || exit 254 ;;
  "ecr create-repository")     [ "${CREATE_OK:-1}" = "1" ] && exit 0 || exit 254 ;;
esac
exit 0
STUB
  chmod +x "${tmp}/aws"

  # Validate the stub before trusting anything it reports: a stub that fell through to the real
  # binary would make every case below pass while doing something else entirely.
  STUB_CALLS="${tmp}/calls" PATH="${tmp}:$PATH" REPO_EXISTS=1 aws ecr describe-repositories >/dev/null 2>&1 \
    || { echo "selftest FAIL: the stub does not answer describe-repositories"; return 1; }
  grep -q "^aws ecr describe-repositories" "${tmp}/calls" \
    || { echo "selftest FAIL: the stub did not record its call — it is not the binary being run"; return 1; }

  : > "${tmp}/calls"
  STUB_CALLS="${tmp}/calls" PATH="${tmp}:$PATH" REPO_EXISTS=1 "$0" openbank-demo >/dev/null 2>&1
  if grep -q "create-repository" "${tmp}/calls"; then
    echo "selftest FAIL: created a repository that already exists"; return 1
  fi

  : > "${tmp}/calls"
  STUB_CALLS="${tmp}/calls" PATH="${tmp}:$PATH" REPO_EXISTS=0 CREATE_OK=1 "$0" openbank-demo >/dev/null 2>&1
  if ! grep -q "create-repository" "${tmp}/calls"; then
    echo "selftest FAIL: a missing repository was not created — the push would still fail"; return 1
  fi

  : > "${tmp}/calls"
  set +e
  STUB_CALLS="${tmp}/calls" PATH="${tmp}:$PATH" REPO_EXISTS=0 CREATE_OK=0 "$0" openbank-demo >/dev/null 2>&1
  rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    echo "selftest FAIL: creation failed and the script exited 0 — the build would die at the push instead"
    return 1
  fi

  echo "selftest OK: existing repo makes no mutating call, missing repo is created, unrecoverable failure exits ${rc}."
  return 0
}

if [ "${1:-}" = "--self-test" ]; then selftest; exit $?; fi

REPO="${1:?usage: ensure-ecr-repository.sh <repository-name> [region]}"
REGION="${2:-${AWS_REGION:-eu-north-1}}"

if aws ecr describe-repositories --repository-names "$REPO" --region "$REGION" >/dev/null 2>&1; then
  exit 0
fi

echo "==> ECR repository ${REPO} does not exist — creating it (#3423)"
if ! aws ecr create-repository \
  --repository-name "$REPO" \
  --region "$REGION" \
  --image-tag-mutability MUTABLE \
  --image-scanning-configuration scanOnPush=true \
  --encryption-configuration encryptionType=AES256 >/dev/null 2>&1; then
  # Lost a race with a concurrent build of the same new service? That is a success.
  if ! aws ecr describe-repositories --repository-names "$REPO" --region "$REGION" >/dev/null 2>&1; then
    echo "ERROR: could not create ECR repository ${REPO} in ${REGION}." >&2
    echo "       Without it the push fails with 'name unknown: The repository ... does not exist'," >&2
    echo "       after the whole build has already run. Check ecr:CreateRepository on this role." >&2
    exit 1
  fi
fi
echo "==> created ${REPO}"
