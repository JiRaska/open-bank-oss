#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Make sure an image's ECR repository exists before something tries to push to it — and never
# claim anything about the registry this script could not actually observe.
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
#   `deploy-drift-declaration` only checks the pin's SHAPE; the fleet attestation gate reports the
#   image as ABSENT, which is the CORRECT state for a first registration and cannot distinguish
#   "tag not built yet" from "repo does not exist"; and Kyverno blocks ArgoCD's dry-run diff for an
#   absent tag, so the app sits at sync status Unknown. The first real signal is a red build, after
#   the expensive part has already been paid for.
#
# WHY IT FAILS OPEN, AND WHY THAT IS THE WHOLE DESIGN (#3444 -> reverted by #3453 -> this)
#   The first version wrote both `describe` calls as `>/dev/null 2>&1`. That made `AccessDenied`
#   indistinguishable from `RepositoryNotFoundException`, so on a runner without the grant it read
#   *permission refused* as *repository absent*, announced it was creating one, and the create was
#   refused for the same reason — failing builds for repositories that existed and held images.
#
#   A false "does not exist" is the dangerous direction precisely because the remedy it suggests
#   (create it) is refused by the same missing permission that produced it.
#
#   So: only a POSITIVE RepositoryNotFoundException triggers a create. Anything else — AccessDenied,
#   a throttle, a network failure, an unparseable error — is reported once and the build continues
#   to the push, which is the real authority on whether the repository is usable. The consequence
#   is deliberate: on IAM that lacks the grant this script is inert rather than harmful, so it can
#   land BEFORE the Terraform apply instead of depending on it.
#
# IAM REQUIRED for it to do anything at all (arc-runners.tf, EcrPush statement):
#   ecr:DescribeRepositories and ecr:CreateRepository on repository/openbank-*.
#   The build role has neither today; CreateRepository exists only for repository/docker-hub/*.
#   platform-tofu.yml applies on workflow_dispatch only, so the grant is a human action.
#
# SETTINGS mirror the fleet (copied from openbank-campaign-service): scanOnPush, AES256, MUTABLE.
# Nothing else has to change for a new repo to be covered — `ecr-image-scanning.tf` is registry-
# level with an `openbank-*` wildcard, and says in place that this is so it covers repos that do
# not exist yet, "with no per-repo resource to forget".
#
# Usage:
#   ensure-ecr-repository.sh <repository-name> [region]
#   ensure-ecr-repository.sh --self-test
set -uo pipefail

# ── classification ────────────────────────────────────────────────────────────────────────────
# The three outcomes that matter, from the CLI's stderr. Deliberately matching AWS' own exception
# names rather than prose: `RepositoryNotFoundException` and `AccessDeniedException` are stable
# API identifiers, while the human sentence around them is not.
classify_describe() {  # stdin: stderr text; $1: exit code
  local rc="$1" err
  err="$(cat)"
  if [ "$rc" -eq 0 ]; then echo "present"; return; fi
  case "$err" in
    *RepositoryNotFoundException*)                echo "absent" ;;
    *AccessDenied*|*"not authorized"*|*UnrecognizedClientException*|*ExpiredToken*)
                                                  echo "denied" ;;
    *)                                            echo "unknown" ;;
  esac
}

# ── name validation ───────────────────────────────────────────────────────────────────────────
# The `openbank-` prefix is the whole security boundary of the IAM grant this script depends on
# (arc-runners.tf, EcrCreateServiceRepository, scoped to repository/openbank-*). IAM enforces it
# server-side, which is the right place for it — but the caller is not always the fleet list:
# auto-deploy.yml line ~180 fills CANDIDATES from `github.event.inputs.services`, so a
# workflow_dispatch supplies this argument directly. Checking here means a bad name is refused
# before it becomes an API call, with a message that says what is wrong, instead of an
# AccessDenied that reads like a broken IAM grant.
#
# Hard failure, not the fail-open the rest of this script uses. Fail-open is the correct answer
# when we cannot OBSERVE the registry — that is an outage, and the build should proceed. A name
# that is not a fleet service name is a caller bug, and continuing would push an image to a
# repository nobody meant to create.
validate_repo_name() {
  local repo="$1"
  case "$repo" in
    openbank-*) ;;
    *)
      echo "ERROR: refusing to touch ECR repository '${repo}': it does not start with 'openbank-'." >&2
      echo "       That prefix is the boundary of the IAM grant (arc-runners.tf)." >&2
      return 1 ;;
  esac
  # ECR's own rule, minus the paths and separators the fleet does not use: lowercase
  # alphanumerics and hyphens, starting and ending alphanumeric, at most 256 characters.
  if ! printf '%s' "$repo" | grep -qE '^openbank-[a-z0-9]([a-z0-9-]*[a-z0-9])?$'; then
    echo "ERROR: refusing to touch ECR repository '${repo}': not a valid fleet repository name." >&2
    echo "       Expected openbank-<lowercase alphanumerics and hyphens>, ending alphanumeric." >&2
    return 1
  fi
  if [ "${#repo}" -gt 256 ]; then
    echo "ERROR: refusing to touch ECR repository '${repo}': longer than ECR's 256-character limit." >&2
    return 1
  fi
  return 0
}

ensure() {
  local repo="$1" region="$2" err rc verdict
  validate_repo_name "$repo" || return 1
  err="$(aws ecr describe-repositories --repository-names "$repo" --region "$region" 2>&1 >/dev/null)"
  rc=$?
  verdict="$(printf '%s' "$err" | classify_describe "$rc")"

  case "$verdict" in
    present)
      return 0
      ;;
    denied)
      echo "NOTE: cannot check whether ECR repository ${repo} exists — the describe was denied." >&2
      echo "      Continuing to the push, which is the authority. This script only creates a" >&2
      echo "      repository it has positively observed to be missing (#3423/#3453)." >&2
      echo "      To enable it: ecr:DescribeRepositories + ecr:CreateRepository on" >&2
      echo "      repository/openbank-* for this role (arc-runners.tf, EcrPush)." >&2
      return 0
      ;;
    unknown)
      echo "NOTE: could not determine whether ECR repository ${repo} exists; continuing to the push." >&2
      echo "      describe said: ${err}" >&2
      return 0
      ;;
  esac

  # Positively absent — and only here.
  echo "==> ECR repository ${repo} does not exist — creating it (#3423)"
  if aws ecr create-repository \
      --repository-name "$repo" \
      --region "$region" \
      --image-tag-mutability MUTABLE \
      --image-scanning-configuration scanOnPush=true \
      --encryption-configuration encryptionType=AES256 >/dev/null 2>&1; then
    echo "==> created ${repo}"
    return 0
  fi

  # Lost a race with a concurrent build of the same new service? That is a success. Re-checked
  # with describe rather than by parsing the create's error, since RepositoryAlreadyExists and a
  # permissions denial are both just a non-zero exit.
  err="$(aws ecr describe-repositories --repository-names "$repo" --region "$region" 2>&1 >/dev/null)"
  rc=$?
  if [ "$rc" -eq 0 ]; then
    echo "==> ${repo} was created concurrently — continuing"
    return 0
  fi

  echo "ERROR: ECR repository ${repo} does not exist in ${region} and could not be created." >&2
  echo "       The push below will fail with 'name unknown: The repository ... does not exist'." >&2
  echo "       Most likely cause: no ecr:CreateRepository on repository/openbank-* for this role." >&2
  return 1
}

# ── self-test ─────────────────────────────────────────────────────────────────────────────────
selftest() {
  local tmp rc
  tmp="$(mktemp -d)"

  # A stub that can produce each REAL outcome, including the one the first version of this script
  # never modelled. That omission is the reusable lesson from #3453: a self-test built from the
  # author's own picture of the world cannot discover a case the author did not picture. The
  # denied case is here because the IAM was one grep away and nobody read it.
  cat > "${tmp}/aws" <<'STUB'
#!/usr/bin/env bash
echo "aws $*" >> "${STUB_CALLS}"
case "$1 $2" in
  "ecr describe-repositories")
    case "${DESCRIBE:-present}" in
      present) exit 0 ;;
      absent)  echo "An error occurred (RepositoryNotFoundException) when calling the DescribeRepositories operation: The repository with name 'x' does not exist" >&2; exit 254 ;;
      denied)  echo "An error occurred (AccessDeniedException) when calling the DescribeRepositories operation: User: arn:aws:sts::1:assumed-role/r is not authorized to perform: ecr:DescribeRepositories" >&2; exit 254 ;;
      weird)   echo "Could not connect to the endpoint URL" >&2; exit 255 ;;
    esac ;;
  "ecr create-repository") [ "${CREATE_OK:-1}" = "1" ] && exit 0 || { echo "AccessDeniedException" >&2; exit 254; } ;;
esac
exit 0
STUB
  chmod +x "${tmp}/aws"

  # Validate the stub FIRST. A stub that silently fell through to the real CLI would make every
  # case below pass while talking to a live registry.
  STUB_CALLS="${tmp}/probe" PATH="${tmp}:$PATH" DESCRIBE=denied aws ecr describe-repositories 2>/dev/null
  if [ $? -eq 0 ]; then echo "selftest FAIL: the stub does not model a denied describe"; rm -rf "$tmp"; return 1; fi
  if ! command grep -q "^aws ecr describe-repositories" "${tmp}/probe" 2>/dev/null; then
    if ! grep -q "^aws ecr describe-repositories" "${tmp}/probe"; then
      echo "selftest FAIL: the stub did not record its call — it is not the binary being run"; rm -rf "$tmp"; return 1
    fi
  fi

  run_case() {  # $1 DESCRIBE  $2 CREATE_OK
    : > "${tmp}/calls"
    STUB_CALLS="${tmp}/calls" PATH="${tmp}:$PATH" DESCRIBE="$1" CREATE_OK="$2" \
      bash -c 'set -uo pipefail; source "$0" --lib; ensure openbank-demo eu-north-1' "$SELF" >"${tmp}/out" 2>&1
    echo $?
  }

  # present: no create, exit 0
  rc="$(run_case present 1)"
  if [ "$rc" != "0" ] || grep -q "create-repository" "${tmp}/calls"; then
    echo "selftest FAIL: an existing repository was not left alone (rc=${rc})"; rm -rf "$tmp"; return 1
  fi

  # denied: MUST NOT create, MUST NOT claim absence, MUST exit 0 so the build continues
  rc="$(run_case denied 1)"
  if [ "$rc" != "0" ]; then
    echo "selftest FAIL: a denied describe failed the build — this is the #3453 regression"; rm -rf "$tmp"; return 1
  fi
  if grep -q "create-repository" "${tmp}/calls"; then
    echo "selftest FAIL: a denied describe triggered a create — permission refused read as absent"; rm -rf "$tmp"; return 1
  fi
  if grep -q "does not exist" "${tmp}/out"; then
    echo "selftest FAIL: a denied describe claimed the repository does not exist"; rm -rf "$tmp"; return 1
  fi

  # unknown (network): same fail-open contract
  rc="$(run_case weird 1)"
  if [ "$rc" != "0" ] || grep -q "create-repository" "${tmp}/calls"; then
    echo "selftest FAIL: an inconclusive describe did not fail open (rc=${rc})"; rm -rf "$tmp"; return 1
  fi

  # absent + create allowed: MUST create, exit 0
  rc="$(run_case absent 1)"
  if [ "$rc" != "0" ] || ! grep -q "create-repository" "${tmp}/calls"; then
    echo "selftest FAIL: a positively absent repository was not created (rc=${rc})"; rm -rf "$tmp"; return 1
  fi

  # absent + create denied: loud failure, because the push cannot succeed either
  rc="$(run_case absent 0)"
  if [ "$rc" = "0" ]; then
    echo "selftest FAIL: absent + un-creatable exited 0 — the build would die at the push instead"; rm -rf "$tmp"; return 1
  fi

  # ── name validation ─────────────────────────────────────────────────────────────────────────
  # The point of each rejection case is that it must happen BEFORE any AWS call: the prefix is
  # the IAM boundary, and an argument that reaches the API is one the boundary had to catch.
  run_name_case() {  # $1 candidate name -> "<rc> <calls>"
    : > "${tmp}/calls"
    STUB_CALLS="${tmp}/calls" PATH="${tmp}:$PATH" DESCRIBE=present CREATE_OK=1 \
      bash -c 'set -uo pipefail; source "$0" --lib; ensure "$1" eu-north-1' "$SELF" "$1" >"${tmp}/out" 2>&1
    echo "$? $(wc -l < "${tmp}/calls" | tr -d ' ')"
  }

  local bad
  for bad in "evil-repo" "docker-hub/nginx" "openbank-" "openbank-UPPER" "openbank-trailing-" \
             "openbank-svc; rm -rf /" "../openbank-escape" ""; do
    set -- $(run_name_case "$bad")
    if [ "$1" = "0" ]; then
      echo "selftest FAIL: name '${bad}' was accepted"; rm -rf "$tmp"; return 1
    fi
    if [ "${2:-0}" != "0" ]; then
      echo "selftest FAIL: name '${bad}' was rejected but only AFTER calling aws"; rm -rf "$tmp"; return 1
    fi
  done

  # ...and a legitimate name still goes through.
  set -- $(run_name_case "openbank-delegation-service")
  if [ "$1" != "0" ]; then
    echo "selftest FAIL: a valid fleet name was rejected (rc=$1)"; rm -rf "$tmp"; return 1
  fi

  rm -rf "$tmp"
  echo "selftest OK: present/denied/unknown/absent+ok/absent+denied all behave — denied and unknown"
  echo "             never create and never claim absence, which is the #3453 regression."
  echo "             Names outside openbank-* are refused before any AWS call is made."
  return 0
}

SELF="${BASH_SOURCE[0]}"
[ "${1:-}" = "--lib" ] && return 0 2>/dev/null

if [ "${1:-}" = "--self-test" ]; then selftest; exit $?; fi

REPO="${1:?usage: ensure-ecr-repository.sh <repository-name> [region]}"
REGION="${2:-${AWS_REGION:-eu-north-1}}"
ensure "$REPO" "$REGION"
exit $?
