#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Capture image/lifecycle/time plus a transient daemon id from this job's Docker
# stream. The collector uses the id only to resolve one-to-one duplicate
# observations and strips it before schema validation/publication; it is never
# exported in the run envelope, artifact, UI or AI input. Without this local key,
# two concurrent containers of the same image could be collapsed as one lifecycle.
EVIDENCE_DIR="${SERVICE}/build/test-intelligence/runtime"
mkdir -p "${EVIDENCE_DIR}"
# Restrict the daemon stream to container objects so Testcontainers exec wait-strategy
# events never enter the retained envelope. This is intentionally not a container-leak
# verdict: redacted aggregate lifecycle counts still need correlation before they can
# establish cleanup or a leak (rendered as unknown by the Test Intelligence UI).
docker events --filter type=container --filter event=start --filter event=die \
  --format '{"image":"{{.Actor.Attributes.image}}","containerId":"{{.Actor.ID}}","lifecycle":"{{.Action}}","observedAtUnix":{{.Time}}}' \
  > "${EVIDENCE_DIR}/docker-events.jsonl" &
docker_events_pid=$!
cleanup_test_evidence() {
  kill "${docker_events_pid}" 2>/dev/null || true
  wait "${docker_events_pid}" 2>/dev/null || true
}
trap cleanup_test_evidence EXIT
# Local task durations for the two observed slow builds; no second build
# or Build Scan upload. Parallel task times are not a critical-path sum.
GRADLE_PROFILE_ARGS=()
case "$SERVICE" in
  openbank-lending-service|openbank-pension-service) GRADLE_PROFILE_ARGS=(--profile) ;;
esac
# The pilot uses deployment's native runner and augmentation properties. Other service
# builds retain their existing recipe. SBOM and application come from one Gradle graph.
deploy_tasks=()
deploy_options=()
if [ "${DEPLOY_ARTIFACT_PILOT:-false}" = true ]; then
  [ "$SERVICE" = openbank-context-service ] || { echo 'Invalid deployment artifact pilot' >&2; exit 1; }
  export ANALYTICS_SINK_TYPE=clickhouse AGENT_SVID_NONCE_STORE=redis
  deploy_tasks+=(":${SERVICE}:verifyPackagedLibrarySbom")
  deploy_options+=(-Dquarkus.package.jar.type=fast-jar)
fi
# Cache healing replays this command only if verification fails before
# the first Gradle task; tests and build outputs are never replayed.
bash .github/scripts/retry-testcontainers-ebusy.sh -- \
  bash .github/scripts/retry-gradle-verification-cache.sh -- \
  ./gradlew "${GRADLE_PROFILE_ARGS[@]}" --configure-on-demand :"${SERVICE}":build "${deploy_tasks[@]}" --build-cache --console=plain --stacktrace \
  "${deploy_options[@]}" \
  -Dquarkus.smallrye-openapi.store-schema-directory="build/openapi-generated"
