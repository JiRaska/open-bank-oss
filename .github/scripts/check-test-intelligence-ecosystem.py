#!/usr/bin/env python3
"""Enforce the shared producer/runtime/schema chain behind Test Intelligence."""

from __future__ import annotations

import argparse
import ast
import json
import re
import subprocess
import sys
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


def performance_projection_errors(deploy: str) -> list[str]:
    """Keep performance projection delegated to the executable rerun selector."""
    errors: list[str] = []
    completed_pages = (
        "actions/workflows/${workflow}/runs?branch=main&status=completed&per_page=100&page=${page}"
    )
    if completed_pages not in deploy:
        errors.append("performance projection does not paginate the completed workflow-run history")
    for workflow in ("perf-gate.yml", "perf-baseline.yml"):
        if f"fetch_run_pages {workflow}" not in deploy:
            errors.append(f"performance projection does not inspect completed {workflow} runs")
    run_queries = re.findall(r"actions/workflows/[^\s\"']+/runs\?[^\s\"']+", deploy)
    if any("status=success" in query for query in run_queries):
        errors.append("performance projection hides failed attempts behind success-only run selection")

    flow = (
        "SELECTOR=.github/scripts/select-performance-evidence.py",
        "fetch_artifact_pages()",
        "actions/runs/${run_id}/artifacts?per_page=100&page=${page}",
        'python3 "${SELECTOR}" artifact-page-size',
        "fetch_run_pages()",
        completed_pages,
        'python3 "${SELECTOR}" run-page-size',
        "fetch_run_pages perf-gate.yml",
        'python3 "${SELECTOR}" runs "${RUN_ARGS[@]}"',
        "while read -r candidate candidate_started_at candidate_attempt candidate_event; do",
        "jobs?filter=latest&per_page=100",
        'python3 "${SELECTOR}" prepare-job',
        "actions/jobs/${candidate_prepare_job}/logs",
        'candidate_scope_args=(--scope-log "${candidate_scope_log}")',
        'python3 "${SELECTOR}" gate',
        '--event "${candidate_event}"',
        "--scope-only",
        'if [ "${candidate_status}" -eq 3 ]; then',
        'RUN_ID="${candidate}"',
        'fetch_artifact_pages "${candidate}"',
        '"${ARTIFACT_ARGS[@]}"',
        "break",
        "fetch_run_pages perf-baseline.yml",
        'python3 "${SELECTOR}" runs --latest "${RUN_ARGS[@]}"',
        'read -r BASELINE_RUN_ID BASELINE_STARTED_AT BASELINE_ATTEMPT BASELINE_EVENT <<< "${BASELINE_RUN}"',
        'fetch_artifact_pages "${BASELINE_RUN_ID}"',
        'python3 "${SELECTOR}" baseline',
        '--attempt-start "${BASELINE_STARTED_AT}"',
        '"${ARTIFACT_ARGS[@]}"',
    )
    cursor = 0
    for needle in flow:
        found = deploy.find(needle, cursor)
        if found < 0:
            errors.append("performance projection bypasses authoritative completed-attempt selection")
            break
        cursor = found + len(needle)
    if "exit 0" in deploy.partition("fetch_run_pages perf-baseline.yml")[0]:
        errors.append("missing performance-gate evidence can suppress independent baseline staging")
    unknown_barrier = re.compile(
        r'Could not prove perf-gate run \$\{candidate\} scope; refusing older evidence\."\s*\n\s*break'
    )
    if unknown_barrier.search(deploy) is None:
        errors.append("unknown perf-gate scope can fall through to older green evidence")

    return errors


def javascript_function(source: str, name: str) -> str:
    """Return one named function through the next declaration, without matching comments."""
    declarations = list(re.finditer(
        r"(?m)^(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(",
        source,
    ))
    for index, declaration in enumerate(declarations):
        if declaration.group(1) == name:
            end = declarations[index + 1].start() if index + 1 < len(declarations) else len(source)
            return source[declaration.start():end]
    return ""


def shell_code(source: str) -> str:
    """Drop shell comments so prose cannot satisfy an executable mutation invariant."""
    return "\n".join(
        re.sub(r"\s+#.*$", "", line)
        for line in source.splitlines()
        if not line.lstrip().startswith("#")
    )


def shell_variable_write_count(source: str, variable: str) -> int:
    """Count ordinary and arithmetic writes to one shell variable."""
    return len(re.findall(
        rf"(?<![A-Za-z0-9_])(?:\+\+\s*|--\s*)?{re.escape(variable)}\s*"
        r"(?:\+\+|--|(?:<<|>>|[+\-*/%&^|])?=(?!=)|:=)",
        source,
    ))


def producer_mutation_semantics_errors(producer: str) -> list[str]:
    """Prove the Python producer carries both detected statuses into its score."""
    try:
        tree = ast.parse(producer)
    except SyntaxError as exc:
        return [f"run-envelope collector cannot be parsed: {exc}"]
    function = next(
        (node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "specialized_evidence"),
        None,
    )
    if function is None:
        return ["run-envelope collector has no specialized-evidence implementation"]

    assignments = {
        target.id: node.value
        for node in ast.walk(function) if isinstance(node, ast.Assign)
        for target in node.targets if isinstance(target, ast.Name)
    }

    detected = assignments.get("detected")
    status_sets = [
        {constant.value for constant in ast.walk(comparison.comparators[0])
         if isinstance(constant, ast.Constant) and isinstance(constant.value, str)}
        for comparison in ast.walk(detected) if isinstance(comparison, ast.Compare)
        and any(isinstance(operator, ast.In) for operator in comparison.ops)
        and comparison.comparators
    ] if detected is not None else []
    if not any({"KILLED", "TIMED_OUT"}.issubset(statuses) for statuses in status_sets):
        return ["run-envelope mutation producer does not count killed and timed-out mutants as detected"]

    score = assignments.get("score")
    score_names = {node.id for node in ast.walk(score) if isinstance(node, ast.Name)} if score else set()
    if not {"detected", "mutations"}.issubset(score_names):
        return ["run-envelope mutation producer disconnects detected mutants from its score"]
    return []


def mutation_projection_errors(
    deploy: str,
    pitest: str,
    producer: str,
    collector: str,
    quality_collector: str,
) -> list[str]:
    """Keep fixed Pitest lanes attached to their report owner.

    Matrix artifact names already carry the released component. A fixed lane may use a
    scope name instead, so derive its owner from the literal mutation-report path and
    require both the deployment compatibility mapping and collector denominator parser.
    """
    errors: list[str] = []
    try:
        workflow = yaml.load(pitest, Loader=CapabilityLoader)
    except yaml.YAMLError as exc:
        return [f"pitest workflow cannot be inspected for fixed mutation lanes: {exc}"]

    jobs = workflow.get("jobs") if isinstance(workflow, dict) else None
    if not isinstance(jobs, dict):
        return ["pitest workflow has no inspectable jobs mapping"]

    fixed_lanes: list[tuple[str, str]] = []
    report_pattern = re.compile(
        r"--mutation-report\s+[\"']?(openbank-[a-z0-9-]+)/build/reports/pitest/mutations\.xml[\"']?"
    )
    out_pattern = re.compile(r"--out\s+(?:[\"']([^\"']+)[\"']|([^\s\\]+))")
    for job in jobs.values():
        if not isinstance(job, dict) or not isinstance(job.get("steps"), list):
            continue
        owners: set[str] = set()
        artifacts: set[tuple[str, str]] = set()
        for step in job["steps"]:
            if not isinstance(step, dict):
                continue
            run = step.get("run")
            if isinstance(run, str):
                executable = shell_code(run)
                run_owners = set(report_pattern.findall(executable))
                owners.update(run_owners)
                outputs = {next(value for value in match.groups() if value is not None)
                           for match in out_pattern.finditer(executable)}
                for owner in run_owners:
                    expected = f"{owner}/build/reports/pitest/test-intelligence-run.json"
                    if outputs != {expected}:
                        errors.append(f"mutation envelope for {owner} is not retained beside its XML report")
            config = step.get("with")
            artifact = config.get("name") if isinstance(config, dict) else None
            upload_path = config.get("path") if isinstance(config, dict) else None
            upload = str(step.get("uses", "")).startswith("actions/upload-artifact@")
            if upload and isinstance(artifact, str) and artifact.startswith("pitest-") and "${{" not in artifact:
                artifacts.add((artifact, upload_path if isinstance(upload_path, str) else ""))
        if owners:
            if len(owners) != 1 or len(artifacts) != 1:
                errors.append("a fixed Pitest lane does not have one literal report owner and artifact")
                continue
            owner = next(iter(owners))
            artifact, upload_path = next(iter(artifacts))
            if upload_path.rstrip("/") != f"{owner}/build/reports/pitest":
                errors.append(f"mutation artifact {artifact} does not upload its report-owner directory")
            fixed_lanes.append((artifact, owner))

        if any(isinstance(step, dict) and "--mutation-report" in str(step.get("run", ""))
               for step in job["steps"]):
            scoring = [str(step.get("run")) for step in job["steps"] if isinstance(step, dict)
                       and re.search(r"(?m)^\s*SCORE\s*=", str(step.get("run", "")))]
            valid_scoring = bool(scoring)
            for script in scoring:
                code = shell_code(script)
                detected_variables = []
                for line in code.splitlines():
                    match = re.match(r"\s*([A-Z][A-Z0-9_]*)\s*=\s*\$\((.*status=.*)\)\s*$", line)
                    if match and re.search(
                        r"\(\s*(?:KILLED\s*\|\s*TIMED_OUT|TIMED_OUT\s*\|\s*KILLED)\s*\)",
                        match.group(2),
                    ):
                        detected_variables.append(match.group(1))
                score_lines = [line for line in code.splitlines()
                               if re.match(r"\s*SCORE\s*=\s*\$\(\(", line)]
                score_line = score_lines[0] if len(score_lines) == 1 else ""
                # A canonical line left above a later assignment is dead evidence. Count
                # every ordinary/arithmetic write in the executable shell, not only lines
                # whose right-hand side happens to look like our expected expression.
                score_is_detected_half_up = len(detected_variables) == 1 and any(
                    re.fullmatch(
                        rf"\s*SCORE\s*=\s*\$\(\(\s*\(\s*{re.escape(variable)}\s*\*\s*100\s*"
                        r"\+\s*TOTAL\s*/\s*2\s*\)\s*/\s*TOTAL\s*\)\)\s*;?\s*",
                        score_line,
                    )
                    and shell_variable_write_count(code, variable) == 1
                    and shell_variable_write_count(code, "SCORE") == 1
                    for variable in detected_variables
                )
                valid_scoring = valid_scoring and score_is_detected_half_up
            if not valid_scoring:
                errors.append("Pitest workflow scoring does not carry killed and timed-out mutants into SCORE")

    if ("pitest-authz", "openbank-libs-runtime") not in fixed_lanes:
        errors.append("enforced pitest-authz lane does not publish the openbank-libs-runtime mutation report")

    pitest_stage = deploy.partition("Stage pitest mutation results")[2].partition(
        "Stage performance evidence"
    )[0]
    pitest_stage = "\n".join(
        line for line in pitest_stage.splitlines() if not line.lstrip().startswith("#")
    )
    artifact_selector = "if a['name'].startswith('pitest-') and not a['expired']]"
    if artifact_selector not in pitest_stage:
        errors.append("mutation staging does not select every retained non-expired Pitest artifact")
    fallback = pitest_stage.find('*) svc="${art_name#pitest-}" ;;')
    destination = pitest_stage.find('dest="${svc}/build/reports/pitest"')
    for artifact, owner in fixed_lanes:
        if artifact == f"pitest-{owner}":
            continue
        mapping = re.compile(
            rf"(?m)^\s*{re.escape(artifact)}\)\s+svc=[\"']{re.escape(owner)}[\"']\s*;;\s*$"
        ).search(pitest_stage)
        case_end = pitest_stage.find("esac", fallback)
        overwritten = case_end >= 0 and re.search(
            r"(?m)^\s*svc=", pitest_stage[case_end + len("esac"):destination]
        )
        if mapping is None or not (mapping.start() < fallback < case_end < destination) or overwritten:
            errors.append(f"mutation artifact {artifact} is not staged under report owner {owner}")

    mutation_components = collector.partition("function mutationComponents()")[2].partition(
        "function platformCapabilities()"
    )[0]
    fixed_capture = re.search(
        r"(?s)const\s+([A-Za-z_$][\w$]*)\s*=\s*\[\.\.\.workflow\.matchAll\(\s*"
        r"/--mutation-report\\s\+\[\"'\]\?\(openbank-\[a-z0-9-\]\+\)\\/"
        r"build\\/reports\\/pitest\\/mutations\\\.xml\[\"'\]\?/g\s*,?\s*\)"
        r"\]\.map\(match\s*=>\s*match\[1\]\)",
        mutation_components,
    )
    fixed_union = fixed_capture and re.search(
        rf"return\s+new Set\(\[\.\.\.matrixComponents,\s*\.\.\.{re.escape(fixed_capture.group(1))}\]\)",
        mutation_components,
    )
    if not fixed_union:
        errors.append("admin projection omits fixed Pitest report owners from its required-control denominator")

    projection_flow = (
        "const governedMutationComponents = mutationComponents()",
        "const internalMutationComponents = [...governedMutationComponents]",
        ".filter(component => !names.includes(component) && !tooling.includes(component))",
        "for (const component of internalMutationComponents)",
        "component, released: false",
        "const mutationEvidence = await mutations([...names, ...internalMutationComponents])",
    )
    cursor = 0
    for needle in projection_flow:
        found = collector.find(needle, cursor)
        if found < 0:
            errors.append("admin projection does not collect fixed Pitest report owners as internal components")
            break
        cursor = found + len(needle)

    required_controls = collector.partition("function requiredControls(")[2].partition(
        "function performance()"
    )[0]
    if ("for (const component of components)" not in required_controls
            or "if (mutationParticipants.has(component.component))" not in required_controls
            or "components.filter(item => item.released)" in required_controls):
        errors.append("fixed Pitest report owners cannot enter the required-control output")

    errors.extend(producer_mutation_semantics_errors(producer))

    mutation_reader = re.sub(
        r"/\*.*?\*/|^\s*//.*$",
        "",
        javascript_function(collector, "mutations"),
        flags=re.DOTALL | re.MULTILINE,
    )
    timed_out_declarations = re.findall(r"\b(?:const|let)\s+timedOut\s*=", mutation_reader)
    timed_out_binding = re.search(
        r"(?m)^\s*(?:const|let)\s+timedOut\s*=\s*status\s*\(\s*['\"]TIMED_OUT['\"]\s*\)\s*;?\s*$",
        mutation_reader,
    )
    detected_score = r"pitestMutationScore\s*\(\s*killed\s*\+\s*timedOut\s*,\s*items\.length\s*\)"
    state_start = re.search(r"\b(?:const|let)\s+specializedState\s*=", mutation_reader)
    legacy_state = mutation_reader[state_start.end():].partition("result.push")[0] if state_start else ""
    projected_score = re.search(rf"\bscore\s*:\s*{detected_score}", mutation_reader)
    if (len(timed_out_declarations) != 1 or timed_out_binding is None or state_start is None
            or timed_out_binding.start() > state_start.start()
            or re.search(detected_score, legacy_state) is None or projected_score is None):
        errors.append("admin mutation projection does not carry timed-out mutants into state and score")

    quality_reader = re.sub(
        r"/\*.*?\*/|^\s*//.*$",
        "",
        javascript_function(quality_collector, "collectMutation"),
        flags=re.DOTALL | re.MULTILINE,
    )
    mutation_loop = re.search(r"for\s*\([^)]*\bof\s+mutations\s*\)\s*\{(.*?)\n\s*\}", quality_reader, re.DOTALL)
    loop = mutation_loop.group(1) if mutation_loop else ""
    quality_return = quality_reader.partition("return {")[2]
    quality_counts_timeout = re.search(
        r"else\s+if\s*\(\s*status\s*===\s*['\"]TIMED_OUT['\"]\s*\)\s*timedOut\+\+",
        loop,
    )
    quality_score = re.search(
        r"\bscore\s*:\s*[^,]*\(\s*killed\s*\+\s*timedOut\s*\)[^,]*,",
        quality_return,
        re.DOTALL,
    )
    if quality_counts_timeout is None or quality_score is None:
        errors.append("quality-report mutation scoring disagrees with PIT detected-mutant semantics")
    return errors


def performance_selector_self_test_error(selector_path: Path) -> str | None:
    if not selector_path.is_file():
        return "performance evidence selector is missing"
    try:
        result = subprocess.run(
            [sys.executable, str(selector_path.resolve()), "--self-test"],
            text=True,
            capture_output=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"performance evidence selector self-test could not run: {exc}"
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        return f"performance evidence selector self-test failed: {detail or result.returncode}"
    return None


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
        test_case = schema.get("$defs", {}).get("testCase", {})
        test_case_properties = test_case.get("properties", {})
        if set(test_case_properties.get("state", {}).get("enum", [])) != {"passed", "failed", "skipped"}:
            errors.append("retry-flaky evidence changed the authoritative testcase state vocabulary")
        retry_contract = {
            "retryFlaky": {"const": True},
            "failedAttemptCount": {"type": "integer", "minimum": 1, "maximum": 9007199254740991},
            "failedAttemptDurationMs": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
        }
        for field, expected in retry_contract.items():
            actual = test_case_properties.get(field, {})
            if any(actual.get(key) != value for key, value in expected.items()):
                errors.append(f"run schema cannot represent safe optional retry-flaky metadata: {field}")
            if field in test_case.get("required", []):
                errors.append(f"legacy testcase envelopes now require additive retry metadata: {field}")
        retry_fields = set(retry_contract)
        dependencies = test_case.get("dependentRequired", {})
        if any(set(dependencies.get(field, [])) != retry_fields - {field} for field in retry_fields):
            errors.append("retry-flaky testcase metadata is not an all-or-nothing optional group")
        retry_state_rule = {
            "if": {"properties": {"retryFlaky": {"const": True}}, "required": ["retryFlaky"]},
            "then": {"properties": {"state": {"const": "passed"}}},
        }
        if retry_state_rule not in test_case.get("allOf", []):
            errors.append("run schema allows retry-flaky metadata to change a testcase verdict")
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
        ("github.event.workflow_run.head_sha || github.sha", "automatic Admin UI deploy sources for one SHA do not share a serialization boundary"),
        ('deploy_branch="chore/admin-ui-deploy-${source_sha}"', "admin deployment cannot identify an existing deploy branch for the source SHA"),
        ('git ls-remote --exit-code --heads origin "refs/heads/${deploy_branch}"', "admin deployment does not reject an already-open automatic deploy source"),
        ('gh pr list --repo "$GITHUB_REPOSITORY" --state open', "admin deployment confuses a stale deploy branch with an open deploy PR"),
        ('"$source_sha" "$main_sha" "$EVENT_NAME" "$open_deploy_pr_exists"', "admin deployment does not pass duplicate-source evidence to its authorization guard"),
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
        ('[ "$EVENT_NAME" != "workflow_dispatch" ] && [ "$OPEN_DEPLOY_PR_EXISTS" = "true" ]',
         "manual Admin UI deploy refreshes cannot override automatic source deduplication"),
        ("Skipping duplicate automatic deploy", "admin deployment does not make duplicate-source rejection observable"),
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
    pitest_workflow = text(root / ".github/workflows/pitest.yml")
    quality_collector = text(root / "openbank-admin-ui/scripts/collect-quality-report.mjs")
    errors.extend(mutation_projection_errors(
        deploy, pitest_workflow, run_collector, collector, quality_collector
    ))
    performance_stage = deploy.partition("Stage performance evidence from latest complete k6 run")[2].partition(
        "Collect production-readiness scorecard"
    )[0]
    performance_selector_path = root / ".github/scripts/select-performance-evidence.py"
    errors.extend(performance_projection_errors(performance_stage))
    selector_self_test_error = performance_selector_self_test_error(performance_selector_path)
    if selector_self_test_error:
        errors.append(selector_self_test_error)
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
    playwright_config = text(root / "openbank-admin-ui/playwright.config.ts")
    if "includeRetries: true" not in playwright_config:
        errors.append("Playwright JUnit omits intra-run retry failures from Test Intelligence evidence")
    deploy_workflow = text(root / ".github/workflows/admin-ui-deploy.yml")
    for needle in ('snapshot_count}" -lt 30', "admin-ui-deploy.yml/runs?branch=main&status=success&per_page=100", "runs/${deploy_run_id}/artifacts?per_page=100", "awk '!seen[$0]++'"):
        if needle not in deploy_workflow:
            errors.append(f"Test Intelligence history cannot reach its 30-snapshot contract: {needle}")
    producer = text(root / ".github/scripts/collect-test-run-evidence.py")
    for needle in ("def browser_diagnostics(", "def trusted_run_url(", '"mayContainSensitiveData": True', '"github-run-authenticated"'):
        if needle not in producer:
            errors.append(f"test producer loses the browser diagnostic privacy contract: {needle}")
    for needle in ("flakyFailure", "flakyError", "rerunFailure", "rerunError", "def junit_duration_ms(",
                   "JSON_SAFE_INTEGER_MAX", "def safe_sum_duration_ms(", "total_duration_ms",
                   '"retryFlaky": True', '"failedAttemptCount"',
                   '"failedAttemptDurationMs"'):
        if needle not in producer:
            errors.append(f"test producer loses bounded Playwright retry-flaky evidence: {needle}")
    for needle in ("const trustedRunUrl =", "const safeRun =", "diagnostics: (run.diagnostics ?? [])", "mayContainSensitiveData: item.mayContainSensitiveData"):
        if needle not in collector:
            errors.append(f"Admin projection loses browser diagnostic metadata: {needle}")
    tests_page = text(root / "openbank-admin-ui/src/app/system/tests/page.tsx")
    types = text(root / "openbank-admin-ui/src/lib/types/test-intelligence.ts")
    if "'playwright-report'" not in types:
        errors.append("Admin UI has no typed Playwright diagnostic artifact")
    if "may contain sensitive browser data" not in tests_page:
        errors.append("Admin UI does not expose the diagnostic privacy warning")
    for needle in ("function retryFlakyMetadata(item)", "function safeAddNonNegativeIntegers(left, right)",
                   "function mergeSameRunTestCase(previous, next)", "delete merged.retryFlaky",
                   "mergedDurationMs !== null", "const latestRunRows =", "const lastState =",
                   "retryFlakyRows.length > 0",
                   "failedAttemptCount", "failedAttemptDurationMs", "retryRun"):
        if needle not in collector:
            errors.append(f"Admin projection loses direct retry-flaky history: {needle}")
    if "state: sameCommitTransitions > 0 ? 'flaky' : lastState === 'failed' ? 'failing'" not in collector:
        errors.append("Admin projection loses deterministic same-commit flake and latest-run failure precedence")
    for needle in ('status="flaky"', "failed retry attempt(s)", "retryRun"):
        if needle not in tests_page:
            errors.append(f"Admin UI does not render direct retry-flaky provenance: {needle}")
    if "retryRun?:" not in types:
        errors.append("Admin UI has no typed retry-flaky run provenance")
    if "item.state === 'skipped' ? 'skipped' : 'stale'" in tests_page:
        errors.append("Admin UI still mislabels an observed flaky testcase as stale evidence")
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
    for needle in ("freshnessAwareState", "freshnessAwareState(item.status, item.verifiedAt)", "specialized.state, performanceRun?.run?.observedAt", "specializedState, mutationRun?.run?.observedAt", "freshnessAwareState(evidence.state, envelope.run.observedAt)"):
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
        valid_performance_projection = """
SELECTOR=.github/scripts/select-performance-evidence.py
fetch_artifact_pages()
actions/runs/${run_id}/artifacts?per_page=100&page=${page}
python3 "${SELECTOR}" artifact-page-size
fetch_run_pages()
actions/workflows/${workflow}/runs?branch=main&status=completed&per_page=100&page=${page}
python3 "${SELECTOR}" run-page-size
fetch_run_pages perf-gate.yml
python3 "${SELECTOR}" runs "${RUN_ARGS[@]}"
while read -r candidate candidate_started_at candidate_attempt candidate_event; do
jobs?filter=latest&per_page=100
python3 "${SELECTOR}" prepare-job
actions/jobs/${candidate_prepare_job}/logs
candidate_scope_args=(--scope-log "${candidate_scope_log}")
python3 "${SELECTOR}" gate
--event "${candidate_event}"
--scope-only
if [ "${candidate_status}" -eq 3 ]; then
echo "::warning::Could not prove perf-gate run ${candidate} scope; refusing older evidence."
break
RUN_ID="${candidate}"
fetch_artifact_pages "${candidate}"
"${ARTIFACT_ARGS[@]}"
break
fetch_run_pages perf-baseline.yml
python3 "${SELECTOR}" runs --latest "${RUN_ARGS[@]}"
read -r BASELINE_RUN_ID BASELINE_STARTED_AT BASELINE_ATTEMPT BASELINE_EVENT <<< "${BASELINE_RUN}"
fetch_artifact_pages "${BASELINE_RUN_ID}"
python3 "${SELECTOR}" baseline
--attempt-start "${BASELINE_STARTED_AT}"
"${ARTIFACT_ARGS[@]}"
"""
        if performance_projection_errors(valid_performance_projection):
            print("self-test failed: valid completed performance projection was rejected")
            return 1
        broken_performance_projections = {
            "success-only run selection": valid_performance_projection.replace(
                "status=completed", "status=success"
            ),
            "run history under-fetches reruns": valid_performance_projection.replace(
                "status=completed&per_page=100", "status=completed&per_page=20"
            ),
            "gate suppresses independent baseline": valid_performance_projection.replace(
                "fetch_run_pages perf-baseline.yml", "exit 0\nfetch_run_pages perf-baseline.yml"
            ),
            "gate bypasses latest-view jobs": valid_performance_projection.replace(
                "jobs?filter=latest&per_page=100", "jobs?per_page=100"
            ),
            "gate skips positive override proof": valid_performance_projection.replace(
                'candidate_scope_args=(--scope-log "${candidate_scope_log}")', "candidate_scope_args=()"
            ),
            "gate drops workflow event context": valid_performance_projection.replace(
                '--event "${candidate_event}"', ""
            ),
            "artifacts do not paginate": valid_performance_projection.replace(
                "actions/runs/${run_id}/artifacts?per_page=100&page=${page}",
                "actions/runs/${run_id}/artifacts?per_page=100",
            ),
            "workflow runs do not paginate": valid_performance_projection.replace(
                "status=completed&per_page=100&page=${page}",
                "status=completed&per_page=100",
            ),
            "unknown scope scans older green": valid_performance_projection.replace(
                'Could not prove perf-gate run ${candidate} scope; refusing older evidence."\nbreak',
                'Could not prove perf-gate run ${candidate} scope; refusing older evidence."\ncontinue',
            ),
        }
        for label, candidate in broken_performance_projections.items():
            if not performance_projection_errors(candidate):
                print(f"self-test failed: {label} was accepted")
                return 1
        valid_mutation_deploy = """
Stage pitest mutation results
if a['name'].startswith('pitest-') and not a['expired']]
case "${art_name}" in
  pitest-authz) svc="openbank-libs-runtime" ;;
  *) svc="${art_name#pitest-}" ;;
esac
dest="${svc}/build/reports/pitest"
Stage performance evidence
"""
        valid_fixed_pitest = """
jobs:
  pitest-authz:
    steps:
      - run: |
          python collector.py \\
            --mutation-report openbank-libs-runtime/build/reports/pitest/mutations.xml \\
            --out openbank-libs-runtime/build/reports/pitest/test-intelligence-run.json
      - run: |
          XML=openbank-libs-runtime/build/reports/pitest/mutations.xml
          TOTAL=$(grep -o '<mutation ' "$XML" | wc -l)
          DETECTED=$(grep -oE "status=['\"](KILLED|TIMED_OUT)['\"]" "$XML" | wc -l)
          SCORE=$(( (DETECTED * 100 + TOTAL / 2) / TOTAL ))
      - uses: actions/upload-artifact@sha
        with:
          name: pitest-authz
          path: openbank-libs-runtime/build/reports/pitest/
"""
        valid_mutation_producer = '''
def pitest_mutation_score(detected, total):
    return (detected * 100 + total // 2) // total if total else None

def specialized_evidence(mutation_report, mutation_threshold):
    mutations = []
    detected = sum(1 for item in mutations if item.attrib.get("status") in {"KILLED", "TIMED_OUT"})
    score = pitest_mutation_score(detected, len(mutations))
    return score
'''
        valid_mutation_collector = r"""
function pitestMutationScore(detected, total) {
  return total ? Math.floor((detected * 100 + Math.floor(total / 2)) / total) : null
}
async function mutations(components) {
  const items = []
  const status = name => items.filter(item => item.$?.status === name).length
  const killed = status('KILLED')
  const timedOut = status('TIMED_OUT')
  const legacyThreshold = 63
  const specializedState = pitestMutationScore(killed + timedOut, items.length) < legacyThreshold ? 'failed' : 'passed'
  const result = []
  result.push({ timedOut, score: pitestMutationScore(killed + timedOut, items.length) })
  return result
}
function mutationComponents() {
  const matrixComponents = []
  const fixedComponents = [...workflow.matchAll(
    /--mutation-report\s+["']?(openbank-[a-z0-9-]+)\/build\/reports\/pitest\/mutations\.xml["']?/g,
  )].map(match => match[1])
  return new Set([...matrixComponents, ...fixedComponents])
}
function platformCapabilities() {
}
function requiredControls(components) {
  const mutationParticipants = mutationComponents()
  for (const component of components) {
    if (component.released) {
    }
    if (mutationParticipants.has(component.component)) {
    }
  }
}
function performance() {
}
async function main() {
  const names = releasedComponents()
  const governedMutationComponents = mutationComponents()
  const tooling = []
  const internalMutationComponents = [...governedMutationComponents]
    .filter(component => !names.includes(component) && !tooling.includes(component))
  const components = []
  for (const component of internalMutationComponents) {
    components.push({ component, released: false })
  }
  const mutationEvidence = await mutations([...names, ...internalMutationComponents])
  return mutationEvidence
}
"""
        valid_quality_collector = """
export async function collectMutation(service) {
  const mutations = []
  let killed = 0, timedOut = 0, total = 0
  for (const mutation of mutations) {
    total++
    const status = mutation.$.status
    if (status === 'KILLED') killed++
    else if (status === 'TIMED_OUT') timedOut++
  }
  return {
    service,
    killed,
    timedOut,
    score: total > 0 ? Math.floor(((killed + timedOut) * 100 + Math.floor(total / 2)) / total) : null,
  }
}
function collectContracts() {}
"""
        valid_mutation_inputs = (
            valid_mutation_deploy, valid_fixed_pitest, valid_mutation_producer,
            valid_mutation_collector, valid_quality_collector,
        )
        if mutation_projection_errors(*valid_mutation_inputs):
            print("self-test failed: valid fixed Pitest mutation projection was rejected")
            return 1
        broken_mutation_projections = {
            "fixed mutation artifact loses its report owner": (0, lambda value: value.replace(
                'pitest-authz) svc="openbank-libs-runtime" ;;\n', "")),
            "fixed mutation artifact is excluded from staging": (0, lambda value: value.replace(
                "if a['name'].startswith('pitest-')",
                "if a['name'] != 'pitest-authz' and a['name'].startswith('pitest-')")),
            "fixed mutation upload leaves its report owner": (1, lambda value: value.replace(
                "path: openbank-libs-runtime/build/reports/pitest/", "path: authz/build/reports/pitest/")),
            "fixed mutation owner is overwritten after mapping": (0, lambda value: value.replace(
                "esac\ndest=", 'esac\nsvc="openbank-authz"\ndest=')),
            "fixed mutation sidecar leaves its report owner": (1, lambda value: value.replace(
                "--out openbank-libs-runtime/build/reports/pitest/test-intelligence-run.json",
                "--out /tmp/test-intelligence-run.json")),
            "Pitest workflow counts killed mutants only": (1, lambda value: value.replace(
                "(KILLED|TIMED_OUT)", "KILLED")),
            "Pitest workflow floors instead of rounding half up": (1, lambda value: value.replace(
                " + TOTAL / 2", "")),
            "Pitest workflow disconnects detected mutants from the score": (1, lambda value: value.replace(
                "DETECTED * 100", "DETECTED * 0")),
            "Pitest workflow overwrites detected count before scoring": (1, lambda value: value.replace(
                "          SCORE=$(( (DETECTED * 100 + TOTAL / 2) / TOTAL ))",
                "          DETECTED=0\n          SCORE=$(( (DETECTED * 100 + TOTAL / 2) / TOTAL ))")),
            "Pitest workflow overwrites score after correct arithmetic": (1, lambda value: value.replace(
                "          SCORE=$(( (DETECTED * 100 + TOTAL / 2) / TOTAL ))",
                "          SCORE=$(( (DETECTED * 100 + TOTAL / 2) / TOTAL ))\n"
                '          SCORE=$(expr "${DETECTED}" \\* 100 / "${TOTAL}")')),
            "run-envelope producer counts killed mutants only": (2, lambda value: value.replace(
                '{"KILLED", "TIMED_OUT"}', '{"KILLED"}')),
            "admin mutation collector counts killed mutants only": (3, lambda value: value.replace(
                "const timedOut = status('TIMED_OUT')", "const timedOut = 0")),
            "quality score ignores timeout while a dead expression names it": (4, lambda value:
                value.replace("((killed + timedOut) * 100", "(killed * 100")
                + "\nconst unusedDetected = killed + timedOut\n"),
            "fixed mutation capture is not projected": (3, lambda value: value.replace(
                ")].map(match => match[1])", ")]")),
            "fixed mutation lane leaves the denominator": (3, lambda value: value.replace(
                "return new Set([...matrixComponents, ...fixedComponents])", "return new Set(matrixComponents)")),
            "enforced fixed lane disappears": (1, lambda value: value.replace(
                "--mutation-report", "--ignored-report")),
            "fixed mutation report owner is not collected": (3, lambda value: value.replace(
                "const internalMutationComponents = [...governedMutationComponents]",
                "const internalMutationComponents = []")),
            "internal mutation component is presented as released": (3, lambda value: value.replace(
                "component, released: false", "component, released: true")),
            "fixed mutation report is not parsed": (3, lambda value: value.replace(
                "mutations([...names, ...internalMutationComponents])", "mutations(names)")),
            "fixed mutation control stays behind the released filter": (3, lambda value: value.replace(
                "for (const component of components)",
                "for (const component of components.filter(item => item.released))")),
            "quality report ignores timed-out mutants": (4, lambda value: value.replace(
                "else if (status === 'TIMED_OUT') timedOut++", "")),
        }
        for label, (input_index, break_input) in broken_mutation_projections.items():
            candidate = list(valid_mutation_inputs)
            candidate[input_index] = break_input(candidate[input_index])
            if not mutation_projection_errors(*candidate):
                print(f"self-test failed: {label} was accepted")
                return 1
    selector_error = performance_selector_self_test_error(
        Path(__file__).resolve().with_name("select-performance-evidence.py")
    )
    if selector_error:
        print(f"self-test failed: {selector_error}")
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
