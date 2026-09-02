#!/usr/bin/env python3
"""Enforce the shared producer/runtime/schema chain behind Test Intelligence."""

from __future__ import annotations

import argparse
import json
import re
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

import yaml

REQUIRED_SCHEMA = {"schemaVersion", "run", "component", "suites", "coverage", "testInfrastructure"}
TESTCONTAINERS_EVIDENCE_BASELINE = "openbank-libs/governance/testcontainers-evidence-baseline.txt"
CAPABILITY_REGISTER = "openbank-libs/governance/test-intelligence-capabilities.yaml"
CAPABILITY_STATES = {
    "implemented",
    "external-blocked",
    "ownership-blocked",
    "safety-blocked",
    "intentionally-deferred",
}
CAPABILITY_ID = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*\Z")


class DuplicateYamlKeyError(yaml.YAMLError):
    """A YAML mapping whose apparent source and effective value would diverge."""


class CapabilityLoader(yaml.SafeLoader):
    """Safe YAML loader that rejects duplicate keys instead of silently keeping the last."""


def construct_unique_mapping(loader: yaml.SafeLoader, node: yaml.MappingNode, deep: bool = False) -> dict:
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        try:
            duplicate = key in mapping
        except TypeError as exc:
            raise yaml.YAMLError(f"unhashable mapping key {key!r}") from exc
        if duplicate:
            raise DuplicateYamlKeyError(f"duplicate YAML key {key!r}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


CapabilityLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    construct_unique_mapping,
)


def text(path: Path) -> str:
    return path.read_text(errors="ignore") if path.exists() else ""


def normalized_heading(value: str) -> str:
    """Return a comparison form for GitHub-style Markdown heading fragments.

    GitHub's precise slug algorithm deliberately preserves some punctuation.  Evidence
    pointers need a stronger property than reproducing that implementation: the
    declared fragment must still name an actual heading after punctuation and spacing
    differences are ignored.  This catches a renamed or invented ADR section without
    coupling the governance gate to a renderer implementation.
    """
    return "-".join(re.findall(r"[a-z0-9]+", value.lower()))


def valid_capability_evidence(root: Path, evidence: str) -> bool:
    """Accept an HTTPS primary source or an existing local document/heading pointer."""
    if evidence.startswith("https://"):
        parsed = urlsplit(evidence)
        return (
            parsed.scheme == "https"
            and bool(parsed.netloc)
            and parsed.username is None
            and parsed.password is None
            and not parsed.query
            and not parsed.fragment
        )
    if evidence.startswith(("http://", "/")):
        return False

    relative, separator, fragment = evidence.partition("#")
    candidate = (root / relative).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        return False
    if not relative or not candidate.is_file():
        return False
    if not separator:
        return True

    if candidate.suffix.lower() not in {".md", ".markdown"}:
        return re.search(rf"(?<![a-zA-Z0-9_-]){re.escape(fragment)}(?![a-zA-Z0-9_-])", text(candidate)) is not None

    target = normalized_heading(fragment)
    return bool(target) and any(
        normalized_heading(heading) == target
        for heading in re.findall(r"(?m)^#{1,6}\s+(.+?)\s*#*\s*$", text(candidate))
    )


def capability_register_errors(root: Path) -> list[str]:
    """Validate the exact operator contract read by the Admin capability matrix.

    This must parse YAML rather than recognise its indentation.  The collector parses the
    same file, and ordinary YAML loaders silently retain a duplicate key's final value;
    accepting the earlier textual value here would let CI certify a different UI state.
    """
    register_path = root / CAPABILITY_REGISTER
    try:
        register = yaml.load(register_path.read_text(encoding="utf-8"), Loader=CapabilityLoader)
    except (OSError, yaml.YAMLError) as exc:
        return [f"test-intelligence capability register unavailable: {exc}"]
    if not isinstance(register, dict):
        return ["test-intelligence capability register must be a YAML mapping"]

    errors: list[str] = []
    if set(register) != {"version", "capabilities"}:
        errors.append("test-intelligence capability register has unsupported or missing top-level fields")
    if type(register.get("version")) is not int or register.get("version") != 1:
        errors.append("test-intelligence capability register must declare integer version 1")
    capabilities = register.get("capabilities")
    if not isinstance(capabilities, list) or not capabilities:
        return errors + ["test-intelligence capability register must declare a non-empty capability list"]

    seen_ids: set[str] = set()
    for index, capability in enumerate(capabilities, start=1):
        prefix = f"test-intelligence capability #{index}"
        if not isinstance(capability, dict):
            errors.append(f"{prefix} must be a mapping")
            continue
        identifier = capability.get("id")
        title = capability.get("title")
        state = capability.get("state")
        evidence = capability.get("evidence")
        blocker = capability.get("blocker")
        required = {"id", "title", "state", "evidence"}
        permitted = required | {"blocker"}
        if not required.issubset(capability) or not set(capability).issubset(permitted):
            errors.append(f"{prefix} has unsupported or missing fields")
        if not isinstance(identifier, str) or not CAPABILITY_ID.fullmatch(identifier):
            errors.append(f"{prefix} has an invalid id")
        elif identifier in seen_ids:
            errors.append(f"test-intelligence capability id is duplicated: {identifier}")
        else:
            seen_ids.add(identifier)
        if not isinstance(title, str) or not title.strip():
            errors.append(f"{prefix} has an empty or non-string title")
        if not isinstance(state, str) or state not in CAPABILITY_STATES:
            errors.append(f"{prefix} has an unsupported state")
        if not isinstance(evidence, str) or not valid_capability_evidence(root, evidence.strip()):
            errors.append(f"{prefix} has unresolvable evidence")
        if state == "implemented":
            if "blocker" in capability:
                errors.append(f"implemented test-intelligence capability has a blocker: {identifier}")
        elif not isinstance(blocker, str) or not blocker.strip():
            errors.append(f"blocked test-intelligence capability has no non-empty blocker: {identifier}")
    return errors


RECORD_CALL = re.compile(
    r"""TestInfrastructureEvidence\.record\s*\((?:[^()"']|\((?:[^()]*)\)|"[^"]*"|'[^']*')*?["'](started|stopped)["']\s*[,)]""",
    re.DOTALL,
)


def recorded_lifecycles(source: str) -> set[str]:
    """Lifecycle literals a file really passes to the shared recorder, ignoring comments.

    The mere PRESENCE of the identifier is not evidence of a lifecycle: an import line, a
    KDoc sentence, or a resource that records only `started` all contain it. #7246's
    acceptance is start AND stop, and a substring test cannot tell those apart — the same
    shape as scoring a service "contract tested" off a comment containing the word
    (check-pact-provider-replay.py's KNOWN_UNCOVERED note).
    """
    stripped = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    stripped = "\n".join(line.split("//")[0] for line in stripped.splitlines())
    return set(RECORD_CALL.findall(stripped))


def unrecorded_service_testcontainers_resources(root: Path) -> set[str]:
    """Find service-owned Testcontainers lifecycle managers without shared evidence."""
    resources = set()
    for path in root.glob("openbank-*/src/test/**/*.kt"):
        source = text(path)
        if ("QuarkusTestResourceLifecycleManager" in source
                and re.search(r"org\.testcontainers|PostgreSQLContainer|GenericContainer|KafkaContainer|RedpandaContainer", source)
                and recorded_lifecycles(source) != {"started", "stopped"}):
            resources.add(path.relative_to(root).as_posix())
    return resources


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
        runtime_observation = schema.get("$defs", {}).get("infrastructureObservation", {})
        runtime_properties = runtime_observation.get("properties", {})
        scope_pattern = runtime_properties.get("resourceScopeId", {}).get("pattern", "")
        if ("resourceScopeId" in runtime_observation.get("required", [])
                or not scope_pattern.startswith("^[0-9a-f]{8}-")):
            errors.append("run schema cannot safely represent optional opaque Testcontainers resource scopes")
        reprovisions = runtime_properties.get("reprovisions", {})
        if reprovisions.get("type") != "integer" or reprovisions.get("minimum") != 1:
            errors.append("run schema cannot safely represent positive logical-resource reprovision counts")
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
    for needle in (
        "collect-test-run-evidence.py",
        "build/test-intelligence/run.json",
        "if: always()",
        "docker events",
        "--filter type=container",
        "--filter event=start",
        "--filter event=die",
    ):
        if needle not in workflow:
            errors.append(f"service CI does not carry required run-envelope wiring: {needle}")
    if "timeout --kill-after=10s 600s ./gradlew --no-daemon :${{ inputs.service }}:koverXmlReport" not in workflow:
        errors.append("Kover evidence is not bounded with the money-path-safe timeout")
    immutable_envelope_artifact = workflow.partition("Retain immutable Test Intelligence run envelope")[2].partition(
        "Upload coverage to Codecov"
    )[0]
    if "build/test-intelligence/run.json" not in immutable_envelope_artifact or "runtime/" in immutable_envelope_artifact:
        errors.append("immutable Test Intelligence artifact must retain only the redacted run envelope, never raw runtime evidence")

    convention = text(root / "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts")
    if "OPENBANK_TEST_EVIDENCE_DIR" not in convention:
        errors.append("service test JVMs do not receive the runtime-evidence directory")
    if "project.delete(testIntelligenceRuntimeDir)" not in convention:
        errors.append("runtime evidence is not reset before each Test task and can mix local reruns")
    # Kover's agent otherwise transforms Testcontainers' shaded classes during Quarkus
    # integration tests.  That can leave the advisory report task green but no XML to
    # project, which is indistinguishable from absent coverage in the operator view.
    if 'excludedClasses.add("org.testcontainers.*")' not in convention:
        errors.append("Kover does not exclude Testcontainers from on-the-fly instrumentation")

    recorder = root / "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/evidence/TestInfrastructureEvidence.kt"
    if not recorder.exists():
        errors.append("openbank-libs-testing has no shared runtime evidence recorder")
    elif "resourceScopeId" not in text(recorder) or "reprovisions" not in text(recorder):
        errors.append("shared runtime evidence recorder cannot preserve opaque scopes and logical reprovisions")
    for name in ("PostgresBase.kt", "PostgresRedpandaTestResource.kt", "PostgresRedisTestResource.kt"):
        source = text(root / "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/containers" / name)
        if "TestInfrastructureEvidence.record" not in source:
            errors.append(f"shared Testcontainers resource emits no lifecycle proof: {name}")
        if "resourceScopeId" not in source:
            errors.append(f"shared Testcontainers resource lacks opaque lifecycle correlation: {name}")

    baseline_path = root / TESTCONTAINERS_EVIDENCE_BASELINE
    baseline = {
        line.strip() for line in text(baseline_path).splitlines()
        if line.strip() and not line.startswith("#")
    }
    if not baseline_path.exists():
        errors.append("service-owned Testcontainers lifecycle-evidence baseline is missing")
    else:
        unrecorded = unrecorded_service_testcontainers_resources(root)
        for path in sorted(unrecorded - baseline):
            errors.append(f"new service-owned Testcontainers resource lacks lifecycle evidence: {path}")
        for path in sorted(baseline - unrecorded):
            errors.append(f"Testcontainers evidence baseline is stale; remove migrated path: {path}")

    trace_contract = text(root / "openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/trace/TraceContract.kt")
    for needle in ("fun verifiedAs", "OPENBANK_TRACE_CONTRACT_V1:", "successfulAssertions > 0"):
        if needle not in trace_contract:
            errors.append(f"shared trace contract cannot emit assertion-backed evidence: {needle}")
    run_collector = text(root / ".github/scripts/collect-test-run-evidence.py")
    for needle in ('"trace"', "def trace_contract_evidence", "OPENBANK_TRACE_CONTRACT_V1:",
                   "specialized.extend(trace_contract_evidence(service))", "def parse_timestamp(",
                   "run_observed_at - datetime.now(timezone.utc) > MAX_FUTURE_SKEW",
                   "observed_at - run_observed_at > MAX_FUTURE_SKEW", "def public_runtime_image",
                   'item["image"] = public_runtime_image'):
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
        ("authorize-admin-ui-deploy-source.sh", "admin deployment bypasses its source-ancestry guard"),
        ("needs.deploy-source.outputs.proceed == 'true'", "privileged admin image build bypasses the deploy-source guard"),
        ("latest_main_artifact", "admin deployment cannot select main-only service evidence"),
        ("per_page=100&page=${page}",
         "admin deployment can stage a PR artifact as deployed-main evidence"),
        ("schedule:", "admin deployment has no scheduled Test Intelligence snapshot refresh"),
        ("cron: '17 3 * * *'", "admin deployment refresh cadence drifted from the governed daily schedule"),
        ("github.event_name }}\" = \"schedule\"", "scheduled snapshot refresh does not use a unique immutable image tag"),
        ("github.event_name }}\" = \"workflow_run\"", "event-driven snapshot refresh does not use a unique immutable image tag"),
        ("github.event.workflow_run.head_sha || 'eligible'", "workflow-run events can evict the current push/dispatch Admin UI deploy queue"),
        ("git ls-remote origin refs/heads/main", "admin deployment does not reject a source commit that is stale before privileged build"),
        ('git fetch --no-tags --depth=64 origin "$main_sha"',
         "admin deployment cannot inspect current main after checking out a waiting source"),
        ('"openbank-libs/governance/journeys.yaml"', "admin deployment does not rebuild the Test Intelligence snapshot when the journey catalog changes"),
        ('"perf/scenarios.yaml"', "admin deployment does not rebuild the Test Intelligence snapshot when the performance catalog changes"),
        ('"perf/k6/**"', "admin deployment does not rebuild the Test Intelligence snapshot when a performance definition changes"),
        ('"openbank-infra/gitops/components/observability/cronjob-journey-*.yaml"', "admin deployment does not rebuild the Test Intelligence snapshot when a synthetic runtime manifest changes"),
    ):
        if needle not in deploy:
            errors.append(message)
    deploy_source_guard = text(root / ".github/scripts/authorize-admin-ui-deploy-source.sh")
    for needle, message in (
        ('source_subject="$(git log -1 --format=%s "$SOURCE_SHA")"',
         "admin deployment does not inspect the source commit subject"),
        ('git merge-base --is-ancestor "$SOURCE_SHA" "$MAIN_SHA"',
         "admin deployment accepts a stale source outside main ancestry"),
        ('git rev-list --reverse "${SOURCE_SHA}..${MAIN_SHA}"',
         "admin deployment cannot inspect every commit that overtook a waiting source"),
        ('git diff-tree --first-parent --no-commit-id --name-only -r "$commit_sha"',
         "admin deployment trusts a deploy-looking commit without verifying its changed paths"),
        ('openbank-infra/gitops/components/admin-ui/admin-ui.yaml',
         "admin deployment does not restrict the harmless-advance exception to its image manifest"),
        ("Skipping stale source", "admin deployment does not make stale-source rejection observable"),
        ("echo false", "admin deployment cannot reject its own GitOps commit"),
    ):
        if needle not in deploy_source_guard:
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
    if "RequiredTestControl" not in ui_types or "Deterministic required controls" not in ui_page:
        errors.append("Admin UI does not expose required controls as a typed operator surface")
    for needle in ("function journeyCoverage(journeys)", "journeys.filter(item => item.status === 'active')",
                   "journeyCoverage: syntheticCoverage"):
        if needle not in collector:
            errors.append(f"admin projection loses the governed synthetic coverage denominator: {needle}")
    for needle in ("function requiredControls(", "mutationComponents()", "requiredControls: controls",
                   "requiredControlGaps: controls.filter"):
        if needle not in collector:
            errors.append(f"admin projection loses an independent required-control denominator: {needle}")
    errors.extend(capability_register_errors(root))
    for needle in ("function platformCapabilities()", "platformCapabilities: capabilities"):
        if needle not in collector:
            errors.append(f"admin projection loses operator-visible platform blockers: {needle}")
    agent_analysis = text(root / "openbank-flaky-test-hunter/src/main/kotlin/com/openbank/flakytest/application/usecase/FlakyTestHunterService.kt")
    if "private val EVIDENCE_KINDS" not in agent_analysis or '"trace",' not in agent_analysis:
        errors.append("flaky-test-hunter cannot consume the trace evidence emitted by the Admin BFF")
    for needle in ("openbank-app-test-intelligence-", ".get('head_branch') == 'main'", "client-test-evidence/openbank-app-${artifact_id}.json"):
        if needle not in deploy:
            errors.append(f"admin deployment lost trusted mobile evidence staging: {needle}")
    for workflow_name, required in {
        "perf-gate.yml": ("--performance-summary", "Build performance Test Intelligence envelope"),
        "perf-baseline.yml": ("--performance-summary", "test-intelligence-run-openbank-money-path"),
        "pitest.yml": ("--mutation-report", "--mutation-threshold 70", "Build mutation Test Intelligence envelope"),
    }.items():
        workflow = text(root / ".github/workflows" / workflow_name)
        for needle in required:
            if needle not in workflow:
                errors.append(f"{workflow_name} does not publish specialized evidence: {needle}")
    if "pitest.yml/runs?branch=main&status=completed&per_page=1" not in deploy:
        errors.append("mutation projection does not select the latest completed attempt regardless of verdict")
    if "pitest.yml/runs?branch=main&status=success" in deploy:
        errors.append("mutation projection hides failed attempts behind an older successful workflow")
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
    freshness = text(root / "openbank-admin-ui/src/lib/test-intelligence-freshness.ts")
    for needle in ("function enforceRuntimeFreshness", "MAX_FUTURE_SKEW_MS", "observed - Date.now() > MAX_FUTURE_SKEW_MS", "runtimeFreshnessState(item.state, item.observedAt)", "staleEvidence: evidence.filter"):
        if needle not in freshness:
            errors.append(f"running Admin UI can keep an expired successful snapshot green: {needle}")
    agent_route = text(root / "openbank-admin-ui/src/app/api/test-intelligence/agents/route.ts")
    if "runtimeFreshnessState(item.state as EvidenceState, item.observedAt)" not in agent_route:
        errors.append("AI agent can analyze an expired successful snapshot as current evidence")
    for needle in ("parsed.hostname === 'github.com'", "parts[1] === 'JiRaska'", "parts[2] === 'open-bank-oss'", "parts[3] === 'pull'", "!parsed.search", "!parsed.hash"):
        if needle not in agent_route:
            errors.append(f"AI proposal can render an untrusted outbound link: {needle}")
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
    for needle in ("freshnessAwareState", "freshnessAwareState(item.status, item.verifiedAt)", "specialized.state, performanceRun?.run?.observedAt", "specialized.state, mutationRun?.run?.observedAt", "freshnessAwareState(evidence.state, envelope.run.observedAt)"):
        if needle not in collector:
            errors.append(f"retained successful evidence can outlive the fleet freshness budget: {needle}")
    synthetic_workflow = text(root / ".github/workflows/synthetic-journeys.yml")
    for needle, message in (
        ("--extract public-edge", "synthetic CI does not execute the ConfigMap-mounted runtime artifact"),
        ('event_name }}-${{ github.ref }}', "synthetic PR and post-GitOps calls do not have isolated concurrency"),
        ('branches: [main]', "synthetic workflow has no post-GitOps main call site"),
        ('cronjob-journey-*.yaml', "synthetic workflow does not run when any synthetic runtime artifact changes"),
        ('--active-ids', "synthetic CI does not validate every active runtime artifact"),
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
        baseline = root / TESTCONTAINERS_EVIDENCE_BASELINE
        baseline.parent.mkdir(parents=True, exist_ok=True)
        baseline.write_text("")
        resource = root / "openbank-example/src/test/kotlin/com/openbank/example/PostgresTestResource.kt"
        resource.parent.mkdir(parents=True, exist_ok=True)
        resource.write_text(
            "import io.quarkus.test.common.QuarkusTestResourceLifecycleManager\n"
            "import org.testcontainers.containers.PostgreSQLContainer\n"
            "class PostgresTestResource : QuarkusTestResourceLifecycleManager"
        )
        new_resource = "openbank-example/src/test/kotlin/com/openbank/example/PostgresTestResource.kt"
        if not any(new_resource in failure for failure in check(root)):
            print("self-test failed: new unrecorded Testcontainers resource was accepted")
            return 1
        baseline.write_text(f"{new_resource}\n")
        if any(new_resource in failure for failure in check(root)):
            print("self-test failed: baseline did not account for existing migration debt")
            return 1
        # A half-migrated resource must not clear the ratchet. Recording only `started`
        # leaves teardown unobservable while the identifier is present, so a substring
        # test would accept it and its baseline entry would have to be deleted --
        # permanently declaring the migration done (#7246).
        baseline.write_text("")
        prefix = (
            "import com.openbank.libs.testing.evidence.TestInfrastructureEvidence\n"
            "import io.quarkus.test.common.QuarkusTestResourceLifecycleManager\n"
            "import org.testcontainers.containers.PostgreSQLContainer\n"
            "class PostgresTestResource : QuarkusTestResourceLifecycleManager {\n"
        )
        started_only = prefix + '  fun start() { TestInfrastructureEvidence.record("postgres", IMG, "started") }\n}\n'
        resource.write_text(started_only)
        if not any(new_resource in failure for failure in check(root)):
            print("self-test failed: start-only lifecycle evidence was accepted as migrated")
            return 1
        # A file that only NAMES the recorder in prose or an import is not evidence either.
        resource.write_text(
            prefix + '  // TestInfrastructureEvidence.record("postgres", IMG, "started") and "stopped"\n}\n'
        )
        if not any(new_resource in failure for failure in check(root)):
            print("self-test failed: commented-out lifecycle evidence was accepted as migrated")
            return 1
        # ...and the honest start+stop pair must PASS, or the check is red for everyone.
        resource.write_text(
            prefix
            + '  fun start() { TestInfrastructureEvidence.record("postgres", IMG.asCanonicalNameString(), "started") }\n'
            + '  fun stop() { TestInfrastructureEvidence.record("postgres", IMG.asCanonicalNameString(), "stopped") }\n}\n'
        )
        if any(new_resource in failure for failure in check(root)):
            print("self-test failed: a genuine start/stop lifecycle pair was rejected")
            return 1
        evidence_doc = root / "docs/adr/test-intelligence.md"
        evidence_doc.parent.mkdir(parents=True, exist_ok=True)
        evidence_doc.write_text("## D8 — Capability boundary\n")
        evidence_yaml = root / ".github/gates/gates.yaml"
        evidence_yaml.parent.mkdir(parents=True, exist_ok=True)
        evidence_yaml.write_text("test-intelligence-ecosystem:\n")
        if not valid_capability_evidence(root, "docs/adr/test-intelligence.md#d8--capability-boundary"):
            print("self-test failed: a real Markdown evidence anchor was rejected")
            return 1
        for invalid in (
            "docs/adr/test-intelligence.md#invented-boundary",
            ".github/gates/gates.yaml#invented-control",
            "../outside.md",
            "http://untrusted.example/evidence",
            "https://",
            "https://user:password@trusted.example/evidence",
        ):
            if valid_capability_evidence(root, invalid):
                print(f"self-test failed: invalid capability evidence was accepted: {invalid}")
                return 1
        register = root / CAPABILITY_REGISTER
        register.write_text(
            "version: 1\ncapabilities:\n"
            "  - id: verified-capability\n"
            "    title: Verified capability\n"
            "    state: implemented\n"
            "    evidence: docs/adr/test-intelligence.md#d8--capability-boundary\n"
        )
        if capability_register_errors(root):
            print("self-test failed: a valid capability register was rejected")
            return 1
        cases = {
            "duplicate YAML key": register.read_text().replace(
                "    state: implemented\n", "    state: implemented\n    state: external-blocked\n"
            ),
            "duplicate capability id": register.read_text() + (
                "  - id: verified-capability\n"
                "    title: Duplicate capability\n"
                "    state: implemented\n"
                "    evidence: docs/adr/test-intelligence.md#d8--capability-boundary\n"
            ),
            "mapping instead of list": "version: 1\ncapabilities: {}\n",
            "missing blocked capability reason": register.read_text().replace(
                "state: implemented", "state: safety-blocked"
            ),
            "non-string evidence": register.read_text().replace(
                "evidence: docs/adr/test-intelligence.md#d8--capability-boundary", "evidence: 42"
            ),
        }
        for label, candidate in cases.items():
            register.write_text(candidate)
            if not capability_register_errors(root):
                print(f"self-test failed: {label} was accepted")
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
