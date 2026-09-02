#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Runs schemathesis against each service in $SERVICES, two passes per service (auth ON, auth
# OFF), and reports what each pass actually exercised. Extracted from .github/workflows/api-fuzz.yml
# (issue #5884): the inline `run:` script had grown to 19973 chars, past GitHub's undocumented
# per-step limit (measured ~20 KiB, see check-workflow-run-step-size.py). GitHub does not report
# that as a size error — it makes the WHOLE workflow file unparseable, every run showing zero jobs
# with no error message, until the offending commit is reverted. Prose belongs here, in the
# script's own header, not scattered as workflow comments that count against that limit.
#
# Env vars expected (all set by the calling job's `env:` block):
#   SERVICES          space-separated list of service module paths to fuzz
#   POSTGRES_PASSWORD throwaway password for the job-local postgres container
#   MAX_EXAMPLES       hypothesis examples per endpoint
#
# ── Derivation, not a hand-kept table ──────────────────────────────────────────────────────────
# Port, DB name and datasource username are all derived from each service's own application.yaml
# rather than listed here, because a hand-kept table is exactly the thing this lane has repeatedly
# found rotted: the username is often a Quarkus config EXPRESSION
# (`${POSTGRES_USER:openbank}` / `"${QUARKUS_DATASOURCE_USERNAME:openbank_settlement}"`), and taken
# verbatim it was handed to `docker run -e POSTGRES_USER=` and then `pg_isready -U`, which can
# never succeed — both services died on "postgres never became ready" and had NEVER been fuzzed,
# silently, while the job list showed them as covered (#5714). Resolve the expression to its
# default here, which is exactly what the %dev profile resolves it to (none of these vars are set
# in this job).
#
# Redis is likewise DERIVED from whether the service's config references it (31 of ~50 do — the
# idempotency store and rate-limit counters) rather than listed here. The auth-ON pass never
# needed it: a 403 fires before the handler touches any dependency, so that pass has been
# accidentally immune to missing infrastructure for its whole life. The auth-OFF pass is NOT
# immune, and that is the single thing that decides whether it can be trusted. Measured while
# prototyping it: with Redis absent, `PATCH /api/v1/consents/approvals/{id}` answers 500
# (`AnnotatedConnectException: Connection refused: localhost/127.0.0.1:6379`), indistinguishable
# from a real finding at the schemathesis layer; with Redis up the same request answers 404,
# correctly. A missing dependency must never be able to masquerade as a server-error finding on
# the control that exists to surface real ones.
#
# The Temporal worker flag is likewise DERIVED from each registrar's own @ConfigProperty
# (`grep ...worker.enabled`) rather than listed here: six services register a Temporal worker at
# StartupEvent, there is no Temporal in this job, and the gRPC channel to temporal-frontend:7233 is
# refused — transaction-service failed outright while lending, sepa-payment and domestic-payment
# retried past the boot deadline. Four money-path services were once reported as fuzz failures for
# an absent dependency, which is not a finding about their HTTP surface, and it drowned the one
# real finding in the same run (consent-service, #5711). Every registrar already has the switch
# (because @QuarkusTest needs it too); what they do not share is a name for it —
# openbank.transaction.worker.enabled, openbank.domestic.worker.enabled,
# openbank.sepa.worker.enabled, openbank.campaign.worker.enabled,
# openbank.settlement.worker.enabled, and lending's `lending.origination.worker.enabled` (not even
# under `openbank.`).
#
# ── Two passes, both must pass ─────────────────────────────────────────────────────────────────
# Pass 1 (auth ON) is what this lane has always done: every endpoint behind @RolesAllowed must
# answer 401/403, so an endpoint that LOSES its annotation starts answering 2xx to anonymous
# traffic and shows up here.
#
# Pass 2 (auth OFF) exists because pass 1 barely tests anything. Measured across the full fleet on
# run 32348238171 — 26/26 green — by counting each job's own `Selected: N/M` against its
# `Authentication failed: N operations`: 306 operations selected, 302 auth-blocked, 4 ACTUALLY
# exercised (1.3%), 24 of 26 services with zero unauthenticated surface. Both real 5xx this lane
# has ever found came from the 3 non-blocked operations on consent-service — its entire yield to
# date came from 1.3% of the surface it reports on. A green was being read as "fuzzed" when it
# meant "authenticated" (#5769).
#
# `quarkus.security.auth.enabled-in-dev-mode` is a built-in dev-mode switch: no dependency, no
# service edit, no realm import. The alternative tried — basic auth with embedded users — is worse
# than useless: the config is accepted while no IdentityProvider is on the classpath, so EVERY
# request answers 500 (`BasicAuthenticationMechanism requires one or more IdentityProviders`).
# Making that work needs an elytron extension on ~26 money-path classpaths, a security-surface
# change rather than a harness one.
#
# `authz.enforce` goes off alongside authentication, because the two are one decision, not two:
# AuthorizeInterceptor fails CLOSED when the PDP is unreachable (ADR-0034), and no OPA sidecar runs
# in this job, so pass 2 without this override produced 66 of 83 "server errors" that were nothing
# of the kind (`[503] POLICY_DECISION_POINT_UNAVAILABLE: policy decision point unavailable: OPA
# call failed: null`) — correct behaviour, wrongly counted. Pass 1 never saw it because a 401/403
# fires before the interceptor is reached, the same accidental immunity that hid the Redis
# dependency, shown by a full-fleet run (32360573944). `%test` already makes exactly this override
# for exactly this reason ("OPA is not present in a test JVM"); %dev keeps the deployed default of
# AUTHZ_ENFORCE:true, so the fuzz JVM needs it explicitly.
#
# So pass 2 tests neither authentication nor authorization, by design — both are pass 1's job. What
# is left is the only thing pass 2 claims: does a handler answer 5xx to input it should reject with
# a 4xx.
#
# What pass 2 does NOT test is authorization by design; that property is pass 1's job, which is why
# both run and both must pass rather than one replacing the other.
#
# ── Dev services OFF ──────────────────────────────────────────────────────────────────────────
# Quarkus dev mode starts a container for anything it believes unconfigured, and on a cold hosted
# runner the PULL is what blows the readiness window. That is the whole of #6492: every scheduled
# run from 2026-08-04 to 2026-08-20 failed, 5-11 jobs each, in a different combination every time
# — and not one of those jobs had failed. They had not finished. Run 32336553304's sca-service was
# mid-`Extracting 583.8MB/638.7MB` of docker.io/grafana/otel-lgtm at the second the 180s loop gave
# up, having pulled testcontainers/ryuk and apache/kafka-native first. None of the three is
# reachable from the fuzzed HTTP surface. The shifting failure set was never a signal about any
# service; it was which jobs happened to lose the race to Docker Hub that morning.
#
# Everything this lane needs is provisioned explicitly above (postgres, redis) and already
# suppresses its own dev service by config — the boot log says so per component ("Not starting Dev
# Services for default datasource as it has explicit configuration"). What was left was an
# observability stack and a Kafka broker, pulled on every one of ~26 jobs, for nothing.
#
# The identical fix landed on the AUTHENTICATED lane on 2026-08-14 (#4703, api-fuzz-authenticated.yml)
# and was never carried across to this one; the two lanes had drifted for eighteen days with the
# unauthenticated one — the surface reachable without a credential — reporting nothing at all.
#
# ── The exception census ───────────────────────────────────────────────────────────────────────
# schemathesis reports "Server error: N" with a generic INTERNAL_ERROR body, so the count alone
# cannot distinguish a defect from a dependency this job does not run — three different
# dependencies have now masqueraded as findings here (Redis, OPA, an OIDC client fetching a token
# from an absent Keycloak). Two of those were first reported as defects and had to be withdrawn;
# the fix was reading the exception classes out of the boot log, done here so the next reader sees
# it in the failure output instead of downloading artifacts and correlating by hand.
#
# Deliberately NOT filtered or suppressed: an OidcClientException here does not prove the finding
# beside it is environmental (run 32364265056: balance-service logged 2 of those AND 4 genuine
# DataExceptions; ledger-service 2 alongside a real NullPointerException). The census informs
# triage; it does not perform it, and must never become an exclusion rule that quietly drops a real
# finding sharing a service with an absent dependency.
#
# The grep matches every ERROR-logged exception class, not only the literal string "Unhandled
# exception" printed by GenericExceptionMapper — the first version matched that phrase and went
# blind the moment an exception GAINED a mapper (run 32501086843: dispute/interest/sdd showed an
# empty census while 78 exceptions had in fact been thrown, mapped and ERROR-logged by new
# persistence mappers, #6240). ANSI colour is stripped BEFORE filtering on level, because the
# runner's log has escape codes interleaved and ` ERROR ` does not match literally before that. The
# trailing `:` in the exception pattern is what separates a real `FQCN: message` from a LOGGER NAME
# (`[co.op.li.ap.er.GenericExceptionMapper]` otherwise matches as `GenericException`).
#
# The census is built into a variable, then tested — not `if <pipeline>; then <same pipeline>`.
# That earlier shape failed SILENTLY under `set -uo pipefail`: the boot log is tens of megabytes,
# `grep -q` exits on its first match, closing the pipe; `sed` takes SIGPIPE and dies 141; pipefail
# makes 141 the pipeline's status; the `if` then read "no errors" for a log full of them (run
# 32503175988 printed `Selected: 19/19` for dispute-service and no census at all, while its
# artifact held 53 ERROR lines).
set -uo pipefail

# ── Boot-failure classification (see "Dev services OFF" above) ─────────────────────────────────
# Extracted so it can be exercised directly: `fuzz-services.sh --self-test` feeds it one log that
# MUST be called a crash and one that MUST NOT be, and checks what it prints. A diagnostic nobody
# has run against a known-positive is decoration.
classify_boot_failure () {
  local svc="$1" label="$2" BOOTLOG="$3" waited="$4"
  local CRASH
  CRASH="$(grep -aE 'Failed to start (application|quarkus)|FATAL: |BUILD FAILED' "${BOOTLOG}" 2>/dev/null | head -3 || true)"
  if [ -n "${CRASH}" ]; then
    echo "::error::[${svc}] CRASHED during boot (${label}) — the application failed to start; this is a finding about the service, not the harness"
    printf '%s\n' "${CRASH}"
    # The cause chain, not the last 50 lines: a boot log's tail is whatever it happened to be
    # doing, which for years was docker-pull progress.
    grep -aE 'Caused by:' "${BOOTLOG}" 2>/dev/null | head -5 || true
  else
    echo "::error::[${svc}] DID NOT FINISH booting within ${waited}s (${label}) — no terminal failure in the log: this is a TIMEOUT, not a crash, and nothing here is a finding about the service"
    # Name the environmental cause when the log shows one. An unfinished image pull is the
    # documented shape (#6492: docker.io/grafana/otel-lgtm, 638 MB, still extracting at the
    # second the loop gave up) and dev services are now off, so seeing this again means the
    # switch stopped working rather than that a service is slow.
    local PULLING
    PULLING="$(grep -a 'Pulling docker image' "${BOOTLOG}" 2>/dev/null | tail -3 || true)"
    if [ -n "${PULLING}" ]; then
      echo "::warning::[${svc}] the boot was pulling container images — dev services should be OFF in this job:"
      printf '%s\n' "${PULLING}"
    fi
    echo "      last lines of the boot log, image-pull progress stripped:"
    grep -av 'http-outgoing\|PullImageResultCallback\|ProgressDetail' "${BOOTLOG}" 2>/dev/null \
      | tail -15 | sed 's/^/      /' || true
  fi
}


# ── Falsifiability harness ────────────────────────────────────────────────────────────────────
# Two negative controls, because the fix is a CLASSIFICATION and a green run cannot show that a
# classification is right. Control 1 is a real crash (settlement-service's Flyway
# password-authentication chain, run 32289597634) and MUST be called a crash with its cause named.
# Control 2 is a real timeout (sca-service mid-pull of grafana/otel-lgtm, run 32336553304) and
# MUST NOT be called a crash — that misattribution is what #6492 was. Control 3 asserts the
# quarkusDev command line still carries the dev-services switch, since the classifier being
# correct about a pull is worth nothing if the pull is still happening.
if [ "${1:-}" = "--self-test" ]; then
  ST_TMP="$(mktemp -d)"; ST_RC=0
  cat > "${ST_TMP}/crash.log" <<'EOF'
05:51:56 ERROR [io.qu.ru.Application] Failed to start application: java.lang.RuntimeException: Failed to start quarkus
Caused by: org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database: FATAL: password authentication failed for user "openbank_settlement"
Caused by: org.postgresql.util.PSQLException: FATAL: password authentication failed for user "openbank_settlement"
EOF
  cat > "${ST_TMP}/timeout.log" <<'EOF'
05:49:09 INFO  [tc.do.io.24.0] Pulling docker image: docker.io/grafana/otel-lgtm:0.24.0. Please be patient; this may take some time but only needs to be done once.
05:49:26 DEBUG [co.gi.do.ap.co.PullImageResultCallback] ResponseItem(stream=null, status=Extracting, progressDetail=ResponseItem.ProgressDetail(current=583794688,total=638658233))
EOF
  out1="$(classify_boot_failure svc-crash "authz ON" "${ST_TMP}/crash.log" 180 2>&1)"
  case "${out1}" in
    *"CRASHED during boot"*) ;;
    *) echo "SELF-TEST FAIL 1: a real crash was not classified as a crash"; ST_RC=1 ;;
  esac
  case "${out1}" in
    *"password authentication failed"*) ;;
    *) echo "SELF-TEST FAIL 1b: the crash cause was not printed"; ST_RC=1 ;;
  esac
  out2="$(classify_boot_failure svc-timeout "authz OFF" "${ST_TMP}/timeout.log" 180 2>&1)"
  case "${out2}" in
    *"CRASHED"*) echo "SELF-TEST FAIL 2: an unfinished image pull was reported as a crash"; ST_RC=1 ;;
  esac
  case "${out2}" in
    *"DID NOT FINISH"*"TIMEOUT, not a crash"*) ;;
    *) echo "SELF-TEST FAIL 2b: a timeout was not named as one"; ST_RC=1 ;;
  esac
  case "${out2}" in
    *"dev services should be OFF"*) ;;
    *) echo "SELF-TEST FAIL 2c: the image pull was not named as the environmental cause"; ST_RC=1 ;;
  esac
  # Anchored to the CONTINUED command-line form, not the bare string: the string also occurs in
  # this file's header and in this very check, so an unanchored grep matches itself and the
  # control can never fail. (It could not, until it was falsified — measured, not assumed.)
  if ! grep -qE '^[[:space:]]+-Dquarkus\.devservices\.enabled=false \\$' "$0"; then
    echo "SELF-TEST FAIL 3: quarkusDev no longer disables dev services"; ST_RC=1
  fi
  rm -rf "${ST_TMP}"
  [ "${ST_RC}" = 0 ] && echo "self-test OK (3 controls)" || echo "self-test FAILED"
  exit "${ST_RC}"
fi

mkdir -p fuzz-reports
OVERALL=0

for svc in $SERVICES; do
  APP_YAML="${svc}/src/main/resources/application.yaml"
  SPEC="${svc}/src/main/resources/openapi.yaml"
  if [ ! -f "$APP_YAML" ] || [ ! -f "$SPEC" ]; then
    echo "::error::[${svc}] missing application.yaml or openapi.yaml — skip"
    OVERALL=1; continue
  fi

  # Derive the HTTP port and database name from the service's own config — no per-service table
  # to rot.
  PORT="$(grep -m1 -A3 '^  http:' "$APP_YAML" | grep -m1 'port:' | grep -oE '[0-9]+')"
  DB="$(grep -m1 -E 'postgresql://' "$APP_YAML" | sed -E 's|.*/([A-Za-z0-9_]+).*|\1|')"
  DBUSER="$(grep -m1 '^    username:' "$APP_YAML" | awk '{print $2}')"
  DBUSER="$(printf '%s' "$DBUSER" | tr -d "\"'" | sed -E 's/^\$\{[A-Za-z_][A-Za-z0-9_]*:?-?([^}]*)\}$/\1/')"
  if [ -z "$DBUSER" ] || printf '%s' "$DBUSER" | grep -q '[^A-Za-z0-9_]'; then
    echo "::error::[${svc}] could not resolve a usable datasource username from ${APP_YAML} (got '${DBUSER}') — skip"
    OVERALL=1; continue
  fi
  echo "==> [${svc}] port=${PORT} db=${DB} user=${DBUSER}"

  # Job-local postgres on the localhost:5432 the %dev profile expects. NB: the batch pool's
  # chart-managed dind has no registry-mirror (unlike the build pool) — this pull hits Docker Hub
  # directly; weekly cadence keeps it under the anon rate limit.
  docker rm -f fuzz-pg >/dev/null 2>&1 || true
  docker run -d --name fuzz-pg -p 5432:5432 \
    -e POSTGRES_USER="${DBUSER}" \
    -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
    -e POSTGRES_DB="${DB}" \
    postgres:16.3-alpine >/dev/null
  PG_UP=0
  for _ in $(seq 1 30); do
    docker exec fuzz-pg pg_isready -U "${DBUSER}" >/dev/null 2>&1 && { PG_UP=1; break; }
    sleep 2
  done

  # Redis, derived from the service's own config (see header) rather than a list kept here.
  if grep -qi "redis" "$APP_YAML"; then
    echo "==> [${svc}] config references redis — starting one"
    docker rm -f fuzz-redis >/dev/null 2>&1 || true
    docker run -d --name fuzz-redis -p 6379:6379 redis:7-alpine >/dev/null
    for _ in $(seq 1 30); do
      docker exec fuzz-redis redis-cli ping >/dev/null 2>&1 && break
      sleep 1
    done
  fi
  if [ "$PG_UP" != "1" ]; then
    echo "::error::[${svc}] postgres never became ready — skip"
    OVERALL=1
    docker rm -f fuzz-pg >/dev/null 2>&1 || true
    continue
  fi

  # Quarkus dev mode: %dev disables OIDC; Flyway migrates at start. Kafka producers are lazy — no
  # broker needed for the HTTP surface (the transactional outbox keeps brokers out of the request
  # path). Temporal worker OFF for the fuzz run (see header for why and how it's derived).
  WORKER_FLAGS=()
  WORKER_PROPS="$(grep -rhoE '"[A-Za-z0-9._-]*worker\.enabled"' "${svc}/src/main" 2>/dev/null | tr -d '"' | sort -u || true)"
  for prop in ${WORKER_PROPS}; do
    WORKER_FLAGS+=("-D${prop}=false")
    echo "==> [${svc}] disabling Temporal worker via ${prop}=false"
  done

  # Compile BEFORE the readiness clock starts. The 180s budget below was being spent on the
  # Gradle build, not on the Quarkus boot: measured on run 32288156614, `starting quarkusDev` was
  # echoed at 18:43:10 and quarkusDev's first log line landed at 18:45:38 — 148 of 180 seconds
  # gone to configuration and compilation, leaving the application ~32s before teardown killed it.
  # Doing the compile as its own step makes the wait measure what it claims to measure.
  # The compile status is READ, not discarded. `|| true` here reproduced exactly the
  # misattribution this lane exists to remove: a compile failure leaves no classes to boot, the
  # readiness wait then times out, and the job reports "failed to boot" about a COMPILE error.
  # Fail fast instead, and name the real cause. Its own log file, because the boot log is what
  # the readiness failure tails and appending the build output there would have it truncated away.
  #
  # Arrived on main as #5763's fourth commit while this branch was open; kept here rather than in
  # the workflow because the inline `run:` step is extracted (see the header) and re-inlining it
  # would push the step back past GitHub's ~20 KiB limit.
  echo "==> [${svc}] compiling (outside the readiness budget)"
  BUILD_START=$SECONDS
  BUILD_LOG="fuzz-reports/${svc}-build.log"
  ./gradlew ":${svc}:quarkusGenerateCode" ":${svc}:classes" \
    --console=plain --quiet > "${BUILD_LOG}" 2>&1
  BUILD_RC=$?
  echo "==> [${svc}] compiled in $((SECONDS - BUILD_START))s (gradle exit ${BUILD_RC})"
  if [ "$BUILD_RC" != "0" ]; then
    echo "::error::[${svc}] COMPILE FAILED (gradle exit ${BUILD_RC}) — this is a build error, NOT a boot failure; see ${svc}-build.log artifact"
    tail -50 "${BUILD_LOG}" || true
    OVERALL=1
    docker rm -f fuzz-pg >/dev/null 2>&1 || true
    docker rm -f fuzz-redis >/dev/null 2>&1 || true
    continue
  fi

  run_pass () {
    local label="$1"; shift
    local logsuffix="$1"; shift

    echo "==> [${svc}] starting quarkusDev (${label})"
    # FORCE the credentials this job actually created the container with, rather than trusting
    # the service to pick them up from an env var. The fleet convention is
    # `password: ${POSTGRES_PASSWORD:CHANGE_ME_LOCAL_DEV_ONLY}`, and the job env supplies
    # POSTGRES_PASSWORD — but openbank-settlement-service names its expressions
    # QUARKUS_DATASOURCE_USERNAME/PASSWORD instead, so POSTGRES_PASSWORD never reached it and
    # Flyway died on `FATAL: password authentication failed for user "openbank_settlement"` after
    # a 237s compile (run 32289597634). System properties sit above application.yaml in SmallRye's
    # ordinal chain, so they win over any `${...:default}` expression whatever it is spelled.
    ./gradlew ":${svc}:quarkusDev" \
      -Dquarkus.datasource.jdbc.url="jdbc:postgresql://localhost:5432/${DB}" \
      -Dquarkus.datasource.username="${DBUSER}" \
      -Dquarkus.datasource.password="${POSTGRES_PASSWORD}" \
      "${WORKER_FLAGS[@]}" "$@" \
      -Dquarkus.devservices.enabled=false \
      -Dquarkus.console.basic=true \
      --console=plain --quiet \
      > "fuzz-reports/${svc}-boot${logsuffix}.log" 2>&1 &
    DEV_PID=$!

    # Readiness = the main HTTP socket answers anything (000 = conn refused). The wait
    # deliberately does NOT use $DEV_PID as a liveness proxy: `./gradlew` is a launcher that hands
    # the build to the Gradle daemon and can exit while quarkusDev keeps running under it —
    # `kill -0 $DEV_PID || break` aborted the wait on a service booting perfectly well (run
    # 32284304038: openbank-domestic-payment logged `Listening on: http://localhost:8116` ~34s
    # into a 180s budget and the job still reported a boot failure).
    local UP=0 WAITED=0
    for _ in $(seq 1 90); do
      code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${PORT}/q/health" || true)"
      if [ "$code" != "000" ]; then UP=1; break; fi
      WAITED=$((WAITED + 2))
      sleep 2
    done

    if [ "$UP" != "1" ]; then
      # "failed to boot" is TWO states with opposite fixes, and printing one message for both is
      # how this lane spent eighteen days reporting a broken harness (#6492). A CRASH leaves a
      # terminal failure in the boot log and needs a code or config fix; a TIMEOUT leaves a JVM
      # still working — its log ends mid-progress, its ERROR grep prints nothing, and the fix is
      # time or one less container. Classify from the LOG, never from $DEV_PID (see the wait
      # comment above for why the PID is not a liveness proxy).
      classify_boot_failure "${svc}" "${label}" "fuzz-reports/${svc}-boot${logsuffix}.log" "${WAITED}"
      OVERALL=1
    else
      echo "==> [${svc}] answered after ${WAITED}s (${label}); fuzzing http://localhost:${PORT}"
      # schemathesis 4.x CLI (bumped from 3.39.16 to close 6 Dependabot alerts on transitive
      # starlette/pytest — 3.x hard-caps pytest<9 and starlette<1):
      #   --base-url -> --url; --hypothesis-max-examples -> --max-examples;
      #   --hypothesis-deadline removed (no equivalent); --request-timeout unit ms -> seconds;
      #   --experimental=openapi-3.1 removed (default now); --junit-xml -> --report junit
      #   --report-junit-path
      schemathesis run "$SPEC" \
        --url "http://localhost:${PORT}" \
        --checks not_a_server_error \
        --max-examples "${MAX_EXAMPLES}" \
        --request-timeout 5 \
        --report junit --report-junit-path "fuzz-reports/${svc}-junit${logsuffix}.xml" \
        | tee "fuzz-reports/${svc}-fuzz${logsuffix}.log" || OVERALL=1

      # The number that says what the green is WORTH. `Selected` is how many operations
      # schemathesis drove; `Authentication failed` is how many of them only ever answered
      # 401/403 and so tested no handler logic at all.
      local sel blocked
      sel="$(grep -aoE 'Selected: [0-9]+/[0-9]+' "fuzz-reports/${svc}-fuzz${logsuffix}.log" | head -1 || true)"
      blocked="$(grep -aoE 'Authentication failed: [0-9]+ operation' "fuzz-reports/${svc}-fuzz${logsuffix}.log" | grep -oE '[0-9]+' | head -1 || true)"
      echo "==> [${svc}] ${label}: ${sel:-Selected: ?} auth-blocked=${blocked:-0}"

      # A census of causes, printed next to the count (triage aid, not a verdict — see header).
      local census
      census="$(sed 's/\x1b\[[0-9;]*m//g' "fuzz-reports/${svc}-boot${logsuffix}.log" 2>/dev/null \
        | grep -a " ERROR " \
        | sed 's/\[[a-z][a-z.]*\.[A-Za-z]*\]//g' \
        | grep -oE '[a-zA-Z_]+(\.[a-zA-Z_]+)+\.[A-Za-z]+(Exception|Error):' \
        | sed 's/.*\.//;s/:$//' \
        | sort | uniq -c | sort -rn | sed 's/^/      /' || true)"
      if [ -n "${census}" ]; then
        echo "==> [${svc}] ${label}: exception classes seen (triage aid, NOT a verdict):"
        echo "${census}"
      fi
    fi

    # Stop this pass's JVM before the next one re-binds the dev port.
    kill "$DEV_PID" 2>/dev/null || true
    pkill -f "quarkusDev" 2>/dev/null || true
    sleep 3
  }

  run_pass "authz ON" ""
  run_pass "authz OFF" "-authz-off" \
    -Dquarkus.security.auth.enabled-in-dev-mode=false \
    -Dauthz.enforce=false

  # Containers only — run_pass already stopped each pass's JVM.
  docker rm -f fuzz-pg >/dev/null 2>&1 || true
  docker rm -f fuzz-redis >/dev/null 2>&1 || true
done

exit "$OVERALL"
