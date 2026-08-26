#!/usr/bin/env python3
"""Enforce the shared producer/runtime/schema chain behind Test Intelligence."""

from __future__ import annotations

import argparse
import json
import re
import tempfile
from pathlib import Path


REQUIRED_SCHEMA = {"schemaVersion", "run", "component", "suites", "coverage", "testInfrastructure"}


def text(path: Path) -> str:
    return path.read_text(errors="ignore") if path.exists() else ""


def check(root: Path) -> list[str]:
    errors: list[str] = []
    schema_file = root / "openbank-libs/governance/test-intelligence-run.schema.json"
    try:
        schema = json.loads(schema_file.read_text())
        required = set(schema.get("required", []))
        if required != REQUIRED_SCHEMA:
            errors.append(f"run schema required fields drifted: {sorted(required)}")
        specialized_kinds = set(schema.get("$defs", {}).get("specializedEvidence", {})
                                .get("properties", {}).get("kind", {}).get("enum", []))
        if "trace" not in specialized_kinds:
            errors.append("run schema cannot represent executed trace-contract evidence")
        diagnostic = schema.get("properties", {}).get("diagnostics", {})
        if diagnostic.get("items", {}).get("$ref") != "#/$defs/diagnosticArtifact":
            errors.append("run schema has no typed diagnostic artifact collection")
        run_url_pattern = schema.get("properties", {}).get("run", {}).get("properties", {}).get("url", {}).get("pattern", "")
        diagnostic_url_pattern = schema.get("$defs", {}).get("diagnosticArtifact", {}).get("properties", {}).get("url", {}).get("pattern", "")
        trusted_run = "https://github.com/JiRaska/open-bank-oss/actions/runs/42"
        hostile_run = "https://github.com.attacker.example/JiRaska/open-bank-oss/actions/runs/42"
        trusted_diagnostic = f"{trusted_run}#artifacts"
        if (not run_url_pattern or re.fullmatch(run_url_pattern, trusted_run) is None
                or re.fullmatch(run_url_pattern, hostile_run) is not None):
            errors.append("run schema permits outbound provenance outside canonical GitHub Actions URLs")
        if (not diagnostic_url_pattern or re.fullmatch(diagnostic_url_pattern, trusted_diagnostic) is None
                or re.fullmatch(diagnostic_url_pattern, f"{hostile_run}#artifacts") is not None):
            errors.append("run schema permits diagnostic links outside canonical GitHub run artifacts")
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"run schema unavailable: {exc}")

    workflow = text(root / ".github/workflows/_service-ci.yml")
    for needle in ("collect-test-run-evidence.py", "build/test-intelligence/run.json", "if: always()", "docker events", "--filter event=start", "--filter event=die"):
        if needle not in workflow:
            errors.append(f"service CI does not carry required run-envelope wiring: {needle}")
    if "timeout --kill-after=10s 600s ./gradlew --no-daemon :${{ inputs.service }}:koverXmlReport" not in workflow:
        errors.append("Kover evidence is not bounded with the money-path-safe timeout")

    convention = text(root / "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts")
    if "OPENBANK_TEST_EVIDENCE_DIR" not in convention:
        errors.append("service test JVMs do not receive the runtime-evidence directory")
    # Kover's agent otherwise transforms Testcontainers' shaded classes during Quarkus
    # integration tests.  That can leave the advisory report task green but no XML to
    # project, which is indistinguishable from absent coverage in the operator view.
    if 'excludedClasses.add("org.testcontainers.*")' not in convention:
        errors.append("Kover does not exclude Testcontainers from on-the-fly instrumentation")

    recorder = root / "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/evidence/TestInfrastructureEvidence.kt"
    if not recorder.exists():
        errors.append("openbank-libs-testing has no shared runtime evidence recorder")
    for name in ("PostgresBase.kt", "PostgresRedpandaTestResource.kt", "PostgresRedisTestResource.kt"):
        source = text(root / "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/containers" / name)
        if "TestInfrastructureEvidence.record" not in source:
            errors.append(f"shared Testcontainers resource emits no lifecycle proof: {name}")

    trace_contract = text(root / "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/trace/TraceContract.kt")
    for needle in ("fun verifiedAs", "OPENBANK_TRACE_CONTRACT_V1:", "successfulAssertions > 0"):
        if needle not in trace_contract:
            errors.append(f"shared trace contract cannot emit assertion-backed evidence: {needle}")
    run_collector = text(root / ".github/scripts/collect-test-run-evidence.py")
    for needle in ('"trace"', "def trace_contract_evidence", "OPENBANK_TRACE_CONTRACT_V1:",
                   "specialized.extend(trace_contract_evidence(service))"):
        if needle not in run_collector:
            errors.append(f"run-envelope collector loses executed trace evidence: {needle}")
    tracing_pilot = text(root / "openbank-agent-service/src/test/kotlin/com/openbank/agent/application/AgentChatServiceTracingTest.kt")
    if '.verifiedAs("agent-run")' not in tracing_pilot:
        errors.append("fleet has no executed trace-contract evidence pilot")

    deploy = text(root / ".github/workflows/admin-ui-deploy.yml")
    for needle, message in (
        ("build/test-intelligence/run.json", "admin deployment does not stage the versioned run envelope"),
        ("-name 'run.json'", "admin deployment does not accept the root run-envelope artifact layout"),
        ("workflow_run:", "admin deployment does not refresh Test Intelligence after successful Services CI"),
        ("workflows: [\"Services CI\", \"CI\"]", "admin deployment is not subscribed to both fleet and Admin UI CI evidence workflows"),
        ("workflow_run.conclusion == 'success'", "admin deployment accepts unsuccessful Services CI evidence"),
        ("workflow_run.head_branch == 'main'", "admin deployment accepts non-main Services CI evidence"),
        ("github.event.workflow_run.head_sha", "admin deployment cannot inspect the exact workflow-run source commit"),
        ('subject="$(git log -1 --format=%s)"', "admin deployment does not inspect the workflow-run commit subject"),
        ('proceed=false', "admin deployment cannot reject its own GitOps commit"),
        ("needs.deploy-source.outputs.proceed == 'true'", "privileged admin image build bypasses the deploy-source guard"),
        ("latest_main_artifact", "admin deployment cannot select main-only service evidence"),
        ("per_page=100&page=${page}",
         "admin deployment can stage a PR artifact as deployed-main evidence"),
        ("schedule:", "admin deployment has no scheduled Test Intelligence snapshot refresh"),
        ("cron: '17 3 * * *'", "admin deployment refresh cadence drifted from the governed daily schedule"),
        ("github.event_name }}\" = \"schedule\"", "scheduled snapshot refresh does not use a unique immutable image tag"),
        ("github.event_name }}\" = \"workflow_run\"", "event-driven snapshot refresh does not use a unique immutable image tag"),
        ("github.event.workflow_run.conclusion != 'success'", "ineligible workflow-run events can evict a valid pending Admin UI deploy"),
        ("github.run_id || 'eligible'", "ineligible Admin UI deploy events do not use an isolated concurrency group"),
    ):
        if needle not in deploy:
            errors.append(message)
    history_stage = deploy.partition("Stage immutable per-attempt Test Intelligence history")[2].partition(
        "Stage pitest mutation results"
    )[0]
    for needle, message in (
        ("for page in 1 2 3 4 5; do", "immutable run history is not paginated"),
        ("per_page=100&page=${page}", "immutable run history does not request later artifact pages"),
        ("head -\"${MAX_ENVELOPES}\"", "immutable run history is not bounded before artifact download"),
    ):
        if needle not in history_stage:
            errors.append(message)
    # A staged Pact file is not a provider-verification verdict. The deploy collector
    # can query the existing read-only Broker credentials and must receive them only
    # in its build/collection step; without this wiring the UI bakes every Pact as
    # `unknown` even though main CI has published authoritative results.
    for needle in ("PACT_BROKER_URL: ${{ vars.PACT_BROKER_URL }}",
                   "PACT_BROKER_USERNAME: ${{ vars.PACT_BROKER_USERNAME }}",
                   "PACT_BROKER_PASSWORD: ${{ secrets.PACT_BROKER_PASSWORD }}"):
        if needle not in deploy:
            errors.append(f"admin deployment cannot project Pact Broker verification evidence: {needle}")
    if "[ ! -d openbank-simulation ] || printf '%s\\n' openbank-simulation" not in deploy:
        errors.append("admin deployment omits the unreleased deterministic-simulation envelope")
    collector = text(root / "openbank-admin-ui/scripts/collect-test-intelligence.mjs")
    for needle in ("const tooling = exists(path.join(repo, simulation)) ? [simulation] : []",
                   "const envelope = runEnvelope(simulation)"):
        if needle not in collector:
            errors.append(f"admin projection ignores the simulation run envelope: {needle}")
    for needle in ("run.specializedEvidence ?? []", "source: item.source", "detail: item.detail"):
        if needle not in collector:
            errors.append(f"admin projection loses specialized trace evidence: {needle}")
    ui_types = text(root / "openbank-admin-ui/src/lib/types/test-intelligence.ts")
    ui_page = text(root / "openbank-admin-ui/src/app/system/tests/page.tsx")
    if "| 'trace'" not in ui_types or "'trace', 'mutation'" not in ui_page:
        errors.append("Admin UI does not expose trace-contract evidence in fleet posture")
    for needle in ("function journeyCoverage(journeys)", "journeys.filter(item => item.status === 'active')",
                   "journeyCoverage: syntheticCoverage"):
        if needle not in collector:
            errors.append(f"admin projection loses the governed synthetic coverage denominator: {needle}")
    agent_analysis = text(root / "openbank-flaky-test-hunter/src/main/kotlin/com/openbank/flakytest/application/usecase/FlakyTestHunterService.kt")
    if "private val EVIDENCE_KINDS" not in agent_analysis or '"trace",' not in agent_analysis:
        errors.append("flaky-test-hunter cannot consume the trace evidence emitted by the Admin BFF")
    for needle in ("openbank-app-test-intelligence-", ".get('head_branch') == 'main'", "client-test-evidence/openbank-app-${artifact_id}.json"):
        if needle not in deploy:
            errors.append(f"admin deployment lost trusted mobile evidence staging: {needle}")
    for workflow_name, required in {
        "perf-gate.yml": ("--performance-summary", "Build performance Test Intelligence envelope"),
        "perf-baseline.yml": ("--performance-summary", "test-intelligence-run-openbank-money-path"),
        "pitest.yml": ("--mutation-report", "Build mutation Test Intelligence envelope"),
    }.items():
        workflow = text(root / ".github/workflows" / workflow_name)
        for needle in required:
            if needle not in workflow:
                errors.append(f"{workflow_name} does not publish specialized evidence: {needle}")
    perf_gate = text(root / ".github/workflows/perf-gate.yml")
    perf_baseline = text(root / ".github/workflows/perf-baseline.yml")
    pinned_k6 = "ghcr.io/grafana/k6:0.54.0@sha256:32000aaa40b848add83425ed7cc77535c343ca473498b0bd29464d00fdca6c79"
    for workflow_name, workflow in (("performance gate", perf_gate), ("performance baseline", perf_baseline)):
        if pinned_k6 not in workflow:
            errors.append(f"{workflow_name} does not execute its k6 runtime by immutable official digest")
        if "github.com/grafana/k6/releases/download" in workflow or "curl -fsSL" in workflow:
            errors.append(f"{workflow_name} still executes an unauthenticated downloaded k6 archive")
        if "grafana/k6-action@" in workflow:
            errors.append(f"{workflow_name} still depends on the archived legacy k6 action")
    for needle in ('--network host', '--user "$(id -u):$(id -g)"', '--volume "$PWD:/work"'):
        if needle not in perf_gate:
            errors.append(f"performance gate pinned container cannot safely reach/write its subject: {needle}")
    performance_catalog = text(root / "perf/scenarios.yaml")
    for scenario in ("money-path-smoke", "money-path-write-benchmark"):
        definition = root / "perf/k6" / f"{scenario}.js"
        if definition.exists() and f"id: {scenario}" not in performance_catalog:
            errors.append(f"performance scenario has no governed execution plan: {scenario}")
    for needle in ("execution_mode:", "safety_boundary:"):
        if needle not in performance_catalog:
            errors.append(f"performance scenario catalog is incomplete: {needle}")
    for needle in ("'scenarios.yaml'", "executionMode: plan.execution_mode"):
        if needle not in collector:
            errors.append(f"admin projection ignores governed performance plans: {needle}")
    ui_workflow = text(root / ".github/workflows/ci.yml")
    for needle in ("test-intelligence-run-openbank-admin-ui", "PLAYWRIGHT_JUNIT_OUTPUT_FILE", "outputFile.junit",
                   "--browser-report-dir", "playwright-report-${{ github.run_id }}-a${{ github.run_attempt }}"):
        if needle not in ui_workflow:
            errors.append(f"Admin UI test producer is incomplete: {needle}")
    deploy_workflow = text(root / ".github/workflows/admin-ui-deploy.yml")
    for needle in ('snapshot_count}" -lt 30', "admin-ui-deploy.yml/runs?branch=main&status=success&per_page=100", "runs/${deploy_run_id}/artifacts?per_page=100", "awk '!seen[$0]++'"):
        if needle not in deploy_workflow:
            errors.append(f"Test Intelligence history cannot reach its 30-snapshot contract: {needle}")
    producer = text(root / ".github/scripts/collect-test-run-evidence.py")
    for needle in ("def browser_diagnostics(", "def trusted_run_url(", '"mayContainSensitiveData": True', '"github-run-authenticated"'):
        if needle not in producer:
            errors.append(f"test producer loses the browser diagnostic privacy contract: {needle}")
    for needle in ("const trustedRunUrl =", "const safeRun =", "diagnostics: (run.diagnostics ?? [])", "mayContainSensitiveData: item.mayContainSensitiveData"):
        if needle not in collector:
            errors.append(f"Admin projection loses browser diagnostic metadata: {needle}")
    tests_page = text(root / "openbank-admin-ui/src/app/system/tests/page.tsx")
    types = text(root / "openbank-admin-ui/src/lib/types/test-intelligence.ts")
    if "'playwright-report'" not in types:
        errors.append("Admin UI has no typed Playwright diagnostic artifact")
    if "may contain sensitive browser data" not in tests_page:
        errors.append("Admin UI does not expose the diagnostic privacy warning")
    synthetic_route = text(root / "openbank-admin-ui/src/app/api/test-intelligence/route.ts")
    for needle in ("function enforceRuntimeFreshness", "runtimeFreshnessState(item.state, item.observedAt)", "staleEvidence: evidence.filter"):
        if needle not in synthetic_route:
            errors.append(f"running Admin UI can keep an expired successful snapshot green: {needle}")
    for needle in ("kube_cronjob_status_last_schedule_time", "kube_cronjob_status_last_successful_time", "kube_job_status_failed"):
        if needle not in synthetic_route:
            errors.append(f"synthetic runtime projection lost its verified Kubernetes signal: {needle}")
    for needle in ('queryTempoMobileTraces', 'service.name="openbank-app"', 'http://tempo:3200'):
        if needle not in synthetic_route:
            errors.append(f"mobile RUM projection lost its live Tempo trace signal: {needle}")
    if 'traces_spanmetrics_calls_total{service=~"openbank-app.*"}' not in synthetic_route:
        errors.append("mobile RUM projection lost its Prometheus error/fallback signal")
    testing_page = text(root / "openbank-admin-ui/src/app/system/tests/page.tsx")
    for needle in ("report.totals.unknownEvidence", "report.totals.unresolvedEvidence", "point.unresolvedEvidence", "Unresolved evidence"):
        if needle not in testing_page:
            errors.append(f"Admin UI can render unknown evidence as healthy: {needle}")
    for needle in ("unknownEvidence", "unresolvedEvidence", "['unknown', 'not-run', 'blocked']"):
        if needle not in collector:
            errors.append(f"collector can aggregate unresolved evidence as green: {needle}")
    for needle in ("freshnessAwareState", "specialized.state, performanceRun?.run?.observedAt", "specialized.state, mutationRun?.run?.observedAt", "freshnessAwareState(evidence.state, envelope.run.observedAt)"):
        if needle not in collector:
            errors.append(f"retained successful evidence can outlive the fleet freshness budget: {needle}")
    synthetic_workflow = text(root / ".github/workflows/synthetic-journeys.yml")
    for needle, message in (
        ("--extract public-edge", "synthetic CI does not execute the ConfigMap-mounted runtime artifact"),
        ('event_name }}-${{ github.ref }}', "synthetic PR and post-GitOps calls do not have isolated concurrency"),
        ('branches: [main]', "synthetic workflow has no post-GitOps main call site"),
        ('cronjob-journey-public-edge.yaml', "synthetic workflow does not run when the runtime artifact changes"),
        ('--synthetic-summary', "synthetic workflow does not publish compatible Test Intelligence evidence"),
        ('test-intelligence-run-openbank-platform-', "synthetic run envelope is not retained in immutable history"),
        ('grafana/k6:1.2.0@sha256:', "synthetic CI image is not pinned to the runtime k6 digest"),
    ):
        if needle not in synthetic_workflow:
            errors.append(message)
    collector = text(root / "openbank-admin-ui/scripts/collect-test-intelligence.mjs")
    for needle, message in (
        ("evidence.kind !== 'synthetic'", "Admin projection ignores retained synthetic run evidence"),
        ("latestCi.set", "Admin projection cannot select the latest synthetic CI verdict"),
        ("ci: latestCi.get", "Admin journey rows do not expose the synthetic CI verdict"),
    ):
        if needle not in collector:
            errors.append(message)
    if not (root / "openbank-libs/governance/journeys.yaml").exists():
        errors.append("synthetic journey inventory is missing")
    return errors


def self_test() -> int:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        for path in [
            "openbank-libs/governance/test-intelligence-run.schema.json",
            ".github/workflows/_service-ci.yml",
            "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts",
            "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/evidence/TestInfrastructureEvidence.kt",
            ".github/workflows/admin-ui-deploy.yml",
        ]:
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("{}")
        container_dir = root / "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/containers"
        container_dir.mkdir(parents=True)
        for name in ("PostgresBase.kt", "PostgresRedpandaTestResource.kt", "PostgresRedisTestResource.kt"):
            (container_dir / name).write_text("")
        failures = check(root)
        if len(failures) < 8:
            print(f"self-test failed: broken fixture produced only {len(failures)} findings")
            return 1
    print("test-intelligence ecosystem self-test: red path proven")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    errors = check(Path(args.root))
    for error in errors:
        print(f"::error title=Test Intelligence ecosystem::{error}")
    if errors:
        return 1
    subjects = sum(1 for file in Path(args.root).glob("openbank-*/version.txt") if file.is_file())
    print("Test Intelligence ecosystem: schema -> every service CI -> runtime proof -> deployment projection")
    print(f"SUBJECTS={subjects}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
