#!/usr/bin/env python3
"""Build one provenance-complete, secret-free Test Intelligence run envelope."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
import xml.etree.ElementTree as ET
import tempfile
from urllib.parse import urlparse


SUITE_KINDS = {"unit", "integration", "contract", "e2e", "simulation"}
SUITE_STATES = {"passed", "failed", "skipped", "not-run"}
SPECIALIZED_KINDS = {"performance", "mutation", "synthetic", "trace"}
SPECIALIZED_STATES = SUITE_STATES | {"blocked", "unknown"}
INFRASTRUCTURE = {"postgres", "redpanda", "valkey"}
DIAGNOSTIC_KINDS = {"playwright-report"}
MAX_FUTURE_SKEW = timedelta(minutes=5)
# Shared resources write their readiness-aware lifecycle directly while the CI
# Docker event stream sees the same container at daemon start/stop time. Keep
# one observation when the two sources describe that same lifecycle, without
# collapsing genuinely separate container cycles.
RUNTIME_OBSERVATION_DUPLICATE_WINDOW = timedelta(seconds=5)


def parse_timestamp(value: object, field: str) -> datetime:
    """Require an absolute ISO-8601 timestamp before publishing evidence."""
    if not isinstance(value, str):
        raise ValueError(f"{field} must be an absolute ISO-8601 timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"{field} must be an absolute ISO-8601 timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError(f"{field} must include a timezone")
    return parsed.astimezone(timezone.utc)


def trusted_run_url(url: str, run_id: str) -> bool:
    """Accept only a canonical GitHub Actions run URL for this envelope id."""
    if not url:
        return True  # local/offline envelopes have no outbound link
    parsed = urlparse(url)
    return (parsed.scheme == "https" and parsed.hostname == "github.com"
            and not parsed.params and not parsed.query and not parsed.fragment
            and re.fullmatch(rf"/[^/]+/[^/]+/actions/runs/{re.escape(str(run_id))}", parsed.path) is not None)


def validate_envelope(envelope: dict) -> None:
    """Fail closed before CI publishes an envelope that violates the v1 contract."""
    required = {"schemaVersion", "run", "component", "suites", "coverage", "testInfrastructure"}
    if set(envelope) - (required | {"specializedEvidence", "testCases", "diagnostics", "testImpact"}) or not required.issubset(envelope):
        raise ValueError("run envelope has missing or unknown top-level fields")
    if envelope["schemaVersion"] != 1 or not str(envelope["component"]).startswith("openbank-"):
        raise ValueError("run envelope schemaVersion/component is invalid")
    run = envelope["run"]
    if set(run) != {"id", "attempt", "commit", "branch", "workflow", "url", "observedAt"}:
        raise ValueError("run provenance fields are incomplete")
    if (not run["id"] or run["attempt"] < 1 or len(run["commit"]) < 7 or not run["branch"]
            or not run["workflow"] or not trusted_run_url(str(run["url"]), str(run["id"]))):
        raise ValueError("run provenance values are invalid")
    run_observed_at = parse_timestamp(run["observedAt"], "run.observedAt")
    if run_observed_at - datetime.now(timezone.utc) > MAX_FUTURE_SKEW:
        raise ValueError("run.observedAt exceeds the allowed clock skew")
    for suite in envelope["suites"]:
        if suite["kind"] not in SUITE_KINDS or suite["state"] not in SUITE_STATES:
            raise ValueError("suite kind/state is invalid")
        counts = [suite[key] for key in ("discovered", "executed", "passed", "failed", "skipped", "errors", "durationMs")]
        if any(not isinstance(value, int) or value < 0 for value in counts):
            raise ValueError("suite counts must be non-negative integers")
        if suite["executed"] + suite["skipped"] != suite["discovered"]:
            raise ValueError("suite discovered count does not equal executed + skipped")
        if suite["passed"] + suite["failed"] + suite["errors"] != suite["executed"]:
            raise ValueError("suite executed count does not equal passed + failed + errors")
    coverage_value = envelope["coverage"]
    if coverage_value is not None:
        if set(coverage_value) != {"source", "lines", "branches"} or not coverage_value["source"]:
            raise ValueError("coverage observation fields are invalid")
        for counter in (coverage_value["lines"], coverage_value["branches"]):
            if set(counter) != {"covered", "missed", "percentage"}:
                raise ValueError("coverage counter fields are invalid")
            if any(not isinstance(counter[key], int) or counter[key] < 0 for key in ("covered", "missed")):
                raise ValueError("coverage counts must be non-negative integers")
            percentage = counter["percentage"]
            if percentage is not None and (not isinstance(percentage, (int, float)) or not 0 <= percentage <= 100):
                raise ValueError("coverage percentage must be null or between 0 and 100")
    infrastructure = envelope["testInfrastructure"]
    if set(infrastructure) != {"declared", "observed"} or not set(infrastructure["declared"]).issubset(INFRASTRUCTURE):
        raise ValueError("declared test infrastructure is invalid")
    for item in infrastructure["observed"]:
        if set(item) != {"resource", "image", "lifecycle", "observedAt"}:
            raise ValueError("runtime observation contains unsafe or incomplete fields")
        if item["resource"] not in INFRASTRUCTURE or item["lifecycle"] not in {"started", "stopped"} or not item["image"]:
            raise ValueError("runtime observation values are invalid")
        observed_at = parse_timestamp(item["observedAt"], "testInfrastructure.observed.observedAt")
        if observed_at - run_observed_at > MAX_FUTURE_SKEW:
            raise ValueError("runtime observation occurs after its run beyond the allowed clock skew")
    for item in envelope.get("specializedEvidence", []):
        if set(item) - {"kind", "state", "source", "detail"} or not {"kind", "state", "source"}.issubset(item):
            raise ValueError("specialized evidence fields are invalid")
        if item["kind"] not in SPECIALIZED_KINDS or item["state"] not in SPECIALIZED_STATES or not item["source"]:
            raise ValueError("specialized evidence values are invalid")
    for item in envelope.get("testCases", []):
        required_case_fields = {"fingerprint", "kind", "classname", "name", "state", "durationMs"}
        if set(item) - (required_case_fields | {"testDefinitionPath"}) or not required_case_fields.issubset(item):
            raise ValueError("test case fields are invalid")
        if (not re.fullmatch(r"[0-9a-f]{24}", item["fingerprint"]) or item["kind"] not in SUITE_KINDS
                or item["state"] not in {"passed", "failed", "skipped"}
                or not item["classname"] or not item["name"] or item["durationMs"] < 0):
            raise ValueError("test case values are invalid")
        path = item.get("testDefinitionPath")
        if (path is not None and (
                not re.fullmatch(r"src/test/(?:kotlin|java)/[A-Za-z0-9_./-]+\.(?:kt|java)", path)
                or any(segment in {".", ".."} for segment in path.split("/"))
        )):
            raise ValueError("test case definition path is invalid")
    impact = envelope.get("testImpact")
    if impact is not None and impact != {
        "schemaVersion": 1,
        "mode": "shadow",
        "mappingState": "unknown",
        "selectionState": "unavailable",
    }:
        raise ValueError("test impact evidence must remain the explicit v1 unknown/shadow state")
    for item in envelope.get("diagnostics", []):
        if set(item) != {"kind", "suiteKind", "name", "url", "retentionDays", "access", "mayContainSensitiveData"}:
            raise ValueError("diagnostic artifact fields are invalid")
        if (item["kind"] not in DIAGNOSTIC_KINDS or item["suiteKind"] != "e2e"
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", item["name"])
                or item["url"] != f'{envelope["run"]["url"]}#artifacts' or item["retentionDays"] != 7
                or item["access"] != "github-run-authenticated" or item["mayContainSensitiveData"] is not True):
            raise ValueError("diagnostic artifact values are invalid")


def browser_diagnostics(report_dir: str | None, run_id: str, attempt: int, run_url: str) -> list[dict]:
    """Describe a retained Playwright report without copying its potentially sensitive contents."""
    if not report_dir:
        return []
    report = Path(report_dir)
    if not report.is_dir() or not any(item.is_file() for item in report.rglob("*")) or not run_url.startswith("https://"):
        return []
    return [{
        "kind": "playwright-report",
        "suiteKind": "e2e",
        "name": f"playwright-report-{run_id}-a{attempt}",
        "url": f"{run_url}#artifacts",
        "retentionDays": 7,
        "access": "github-run-authenticated",
        "mayContainSensitiveData": True,
    }]


def classify(name: str, classname: str, task: str, component: str) -> str:
    identity = f"{name} {classname} {task}"
    if re.search(r"integration|inttest", identity, re.I) or re.search(r"IT(?:$|[.$\s])", identity):
        return "integration"
    if re.search(r"pact|contract", identity, re.I):
        return "contract"
    if re.search(r"e2e|playwright", identity, re.I):
        return "e2e"
    if component == "openbank-simulation":
        return "simulation"
    return "unit"


def junit_suites(root: Path):
    """Yield (task, testsuite element) for every JUnit report under build/test-results.

    Gradle writes ``TEST-<class>.xml`` with a ``<testsuite>`` root; vitest and Playwright
    write an arbitrarily named file with a ``<testsuites>`` wrapper. Globbing the Gradle
    filename convention silently produced an empty Admin UI envelope, so match any XML and
    discriminate on the parsed root tag instead. An unparsable report is skipped rather than
    failing the producing build: a missing observation must never gate the suite it observes.
    """
    if not root.exists():
        return
    for report in sorted(root.glob("**/*.xml")):
        try:
            tree = ET.parse(report).getroot()
        except ET.ParseError:
            print(f"::warning::test-intelligence: unparsable JUnit report skipped: {report}")
            continue
        if tree.tag not in ("testsuite", "testsuites"):
            continue
        task = report.relative_to(root).parts[0]
        if tree.tag == "testsuite":
            yield task, tree
            continue
        children = tree.findall("testsuite")
        for child in children or [tree]:
            yield task, child


def suites(component: str, service: Path) -> list[dict]:
    totals: dict[str, dict] = {}
    root = service / "build" / "test-results"
    for task, tree in junit_suites(root):
        cases = tree.findall(".//testcase")
        sample = cases[0] if cases else None
        kind = classify(tree.attrib.get("name", ""), sample.attrib.get("classname", "") if sample is not None else "", task, component)
        row = totals.setdefault(kind, {"kind": kind, "discovered": 0, "executed": 0, "passed": 0, "failed": 0, "skipped": 0, "errors": 0, "durationMs": 0})
        discovered = int(tree.attrib.get("tests", len(cases)))
        failed = int(tree.attrib.get("failures", 0))
        errors = int(tree.attrib.get("errors", 0))
        skipped = int(tree.attrib.get("skipped", 0))
        executed = discovered - skipped
        row.update(discovered=row["discovered"] + discovered, executed=row["executed"] + executed,
                   failed=row["failed"] + failed, errors=row["errors"] + errors,
                   skipped=row["skipped"] + skipped, passed=row["passed"] + max(0, executed - failed - errors),
                   durationMs=row["durationMs"] + round(float(tree.attrib.get("time", 0)) * 1000))
    for row in totals.values():
        # Derive the totals the v1 contract requires to be consistent. A report whose
        # failures+errors exceed tests-minus-skipped (a killed JVM, a partial write) must not
        # make validate_envelope reject the envelope and fail an otherwise green build.
        row["executed"] = row["passed"] + row["failed"] + row["errors"]
        row["discovered"] = row["executed"] + row["skipped"]
        row["state"] = "failed" if row["failed"] + row["errors"] else "skipped" if row["executed"] == 0 else "passed"
    return sorted(totals.values(), key=lambda row: row["kind"])


def test_definition_path(service: Path, classname: str) -> str | None:
    """Return a directly verified test source path, never an inferred production dependency.

    JUnit's classname normally names the compiled Kotlin/Java test class. Nested classes map to
    their enclosing source file. Non-JVM reporters (for example Playwright) deliberately return
    no path: guessing would turn missing provenance into false impact-analysis evidence.
    """
    top_level = classname.split("$", 1)[0]
    if not re.fullmatch(r"(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*", top_level):
        return None
    relative = Path(*top_level.split("."))
    for language, suffix in (("kotlin", ".kt"), ("java", ".java")):
        candidate = service / "src" / "test" / language / relative.with_suffix(suffix)
        if candidate.is_file():
            return candidate.relative_to(service).as_posix()
    return None


def test_cases(component: str, service: Path) -> list[dict]:
    """Emit secret-free observations with stable identity and verified test-source provenance."""
    result = []
    root = service / "build" / "test-results"
    for task, tree in junit_suites(root):
        for case in tree.findall(".//testcase"):
            name = case.attrib.get("name", "unknown")
            classname = case.attrib.get("classname", tree.attrib.get("name", "unknown"))
            kind = classify(name, classname, task, component)
            definition = re.sub(r"\s*(?:\[[^]]*]|\([^)]*\))\s*$", "", name).strip() or name
            identity = f"{component}\0{kind}\0{classname}\0{definition}"
            state = "skipped" if case.find("skipped") is not None else "failed" if (
                case.find("failure") is not None or case.find("error") is not None
            ) else "passed"
            item = {
                "fingerprint": hashlib.sha256(identity.encode()).hexdigest()[:24],
                "kind": kind, "classname": classname, "name": definition, "state": state,
                "durationMs": max(0, round(float(case.attrib.get("time", 0)) * 1000)),
            }
            source_path = test_definition_path(service, classname)
            if source_path is not None:
                item["testDefinitionPath"] = source_path
            result.append(item)
    return sorted(result, key=lambda item: (item["fingerprint"], item["state"]))


def coverage(service: Path) -> dict | None:
    report = service / "build" / "reports" / "kover" / "report.xml"
    if not report.exists():
        return None
    root = ET.parse(report).getroot()
    counters = {node.attrib["type"].lower(): node.attrib for node in root.findall("counter")}
    def value(kind: str) -> dict:
        item = counters.get(kind, {})
        covered, missed = int(item.get("covered", 0)), int(item.get("missed", 0))
        return {"covered": covered, "missed": missed, "percentage": round(covered * 100 / (covered + missed), 2) if covered + missed else None}
    return {"source": str(report), "lines": value("line"), "branches": value("branch")}


def declared_infrastructure(service: Path) -> list[str]:
    text = "\n".join(path.read_text(errors="ignore") for path in service.glob("src/test/**/*.kt"))
    build_file = service / "build.gradle.kts"
    build = build_file.read_text(errors="ignore") if build_file.exists() else ""
    values = []
    if "PostgreSQLContainer" in text or "testcontainers.postgresql" in build: values.append("postgres")
    if "RedpandaContainer" in text or "testcontainers.redpanda" in build: values.append("redpanda")
    if re.search(r"valkey|redis", text, re.I) and "GenericContainer" in text: values.append("valkey")
    return values


def runtime_image_identity(image: str) -> str:
    """Collapse Docker's canonical-library spelling for lifecycle deduplication only.

    Testcontainers records the image requested by the test (normally ``postgres:tag``),
    while Docker's event stream expands the same reference to
    ``docker.io/library/postgres:tag``.  These are one container, not two observations.
    Keep the original image in the published event for provenance; only the comparison key
    is normalized, and do not rewrite arbitrary registry names.
    """
    for prefix in ("docker.io/library/", "index.docker.io/library/"):
        if image.startswith(prefix):
            return image[len(prefix):]
    return image


def observations(service: Path) -> list[dict]:
    result = []
    for file in (service / "build" / "test-intelligence" / "runtime").glob("*.jsonl"):
        for line in file.read_text().splitlines():
            try:
                item = json.loads(line)
                item.pop("schemaVersion", None)
                if "observedAtUnix" in item:
                    image = str(item.get("image", ""))
                    resource = "postgres" if "postgres" in image else "redpanda" if "redpanda" in image else "valkey" if re.search(r"valkey|redis", image, re.I) else None
                    lifecycle = {"start": "started", "die": "stopped"}.get(item.get("lifecycle"))
                    if resource and lifecycle:
                        result.append((1, {"resource": resource, "image": image, "lifecycle": lifecycle,
                                          "observedAt": datetime.fromtimestamp(int(item["observedAtUnix"]), timezone.utc).isoformat().replace("+00:00", "Z")}))
                else:
                    # A resource's own recorder marks readiness and carries the
                    # stronger lifecycle observation than Docker's raw daemon
                    # stream. Keep that provenance only while deduplicating;
                    # the published schema deliberately contains no host data.
                    result.append((0 if file.name == "testcontainers.jsonl" else 1, item))
            except json.JSONDecodeError:
                continue
    normalized = []
    for priority, item in result:
        try:
            observed_at = parse_timestamp(item["observedAt"], "testInfrastructure.observed.observedAt")
        except (KeyError, ValueError):
            continue
        normalized.append((priority, observed_at, item))
    normalized.sort(key=lambda entry: (entry[0], entry[1]))

    deduplicated = []
    for _, observed_at, item in normalized:
        duplicate = any(
            item["resource"] == previous["resource"]
            and runtime_image_identity(item["image"]) == runtime_image_identity(previous["image"])
            and item["lifecycle"] == previous["lifecycle"]
            and abs(observed_at - previous_at) <= RUNTIME_OBSERVATION_DUPLICATE_WINDOW
            for previous_at, previous in deduplicated
        )
        if not duplicate:
            deduplicated.append((observed_at, item))
    return [item for _, item in sorted(deduplicated, key=lambda entry: entry[0])]


def trace_contract_evidence(service: Path) -> list[dict]:
    """Project only markers emitted by an executed, successfully asserted TraceContract."""
    marker = re.compile(r"(?m)^OPENBANK_TRACE_CONTRACT_V1:([a-z0-9][a-z0-9._-]{0,63})$")
    results: dict[str, dict] = {}
    root = service / "build" / "test-results"
    for _, tree in junit_suites(root):
        failed = int(tree.attrib.get("failures", 0)) + int(tree.attrib.get("errors", 0)) > 0
        output = "\n".join(node.text or "" for node in tree.findall(".//system-out"))
        for contract_id in marker.findall(output):
            row = results.setdefault(contract_id, {"failed": False, "markers": 0})
            row["failed"] = row["failed"] or failed
            row["markers"] += 1
    return [{
        "kind": "trace", "state": "failed" if row["failed"] else "passed",
        "source": f"trace-contract:{contract_id}",
        "detail": f"{row['markers']} executed marker(s); JUnit suite {'failed' if row['failed'] else 'passed'}",
    } for contract_id, row in sorted(results.items())]


def specialized_evidence(
    performance_summary: str | None,
    mutation_report: str | None,
    performance_not_run_detail: str | None = None,
    synthetic_summary: str | None = None,
    synthetic_journey: str | None = None,
) -> list[dict]:
    specialized = []
    if performance_summary:
        summary_file = Path(performance_summary)
        summary = json.loads(summary_file.read_text()) if summary_file.exists() else None
        thresholds = [value for metric in (summary or {}).get("metrics", {}).values()
                      for value in (metric.get("thresholds") or {}).values()]
        # k6 summary-export encodes a crossed threshold as bare True (and a passing
        # threshold as False). Accept the older object fixture form too.
        failed = sum(1 for value in thresholds if value is True or (isinstance(value, dict) and value.get("ok") is False))
        detail = (performance_not_run_detail or "performance summary absent") if summary is None \
            else f"{len(thresholds)} threshold result(s), {failed} breached"
        specialized.append({"kind": "performance", "state": "not-run" if summary is None else "failed" if failed else "passed",
                            "source": str(summary_file), "detail": detail})
    if mutation_report:
        mutation_file = Path(mutation_report)
        if mutation_file.exists():
            root = ET.parse(mutation_file).getroot()
            mutations = root.findall(".//mutation")
            killed = sum(1 for item in mutations if item.attrib.get("status") == "KILLED")
            score = round(killed * 100 / len(mutations), 2) if mutations else None
            specialized.append({"kind": "mutation", "state": "passed" if mutations else "skipped",
                                "source": str(mutation_file), "detail": f"{killed}/{len(mutations)} killed ({score if score is not None else 'n/a'}%)"})
        else:
            specialized.append({"kind": "mutation", "state": "not-run", "source": str(mutation_file), "detail": "mutation report absent"})
    if synthetic_summary:
        if not synthetic_journey or not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", synthetic_journey):
            raise ValueError("--synthetic-summary requires a valid --synthetic-journey id")
        summary_file = Path(synthetic_summary)
        summary = json.loads(summary_file.read_text()) if summary_file.exists() else None
        thresholds = [value for metric in (summary or {}).get("metrics", {}).values()
                      for value in (metric.get("thresholds") or {}).values()]
        failed = sum(1 for value in thresholds if value is True or
                     (isinstance(value, dict) and value.get("ok") is False))
        specialized.append({
            "kind": "synthetic",
            "state": "not-run" if summary is None else "failed" if failed else "passed",
            "source": f"journey:{synthetic_journey}",
            "detail": "synthetic summary absent" if summary is None else
                      f"{len(thresholds)} threshold result(s), {failed} breached",
        })
    return specialized


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--service")
    parser.add_argument("--out")
    parser.add_argument("--component")
    parser.add_argument("--performance-summary")
    parser.add_argument("--performance-not-run-detail")
    parser.add_argument("--mutation-report")
    parser.add_argument("--synthetic-summary")
    parser.add_argument("--synthetic-journey")
    parser.add_argument("--browser-report-dir")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        with tempfile.TemporaryDirectory() as directory:
            service = Path(directory)
            runtime = service / "build/test-intelligence/runtime"
            runtime.mkdir(parents=True)
            (runtime / "docker-events.jsonl").write_text(
                '{"image":"docker.io/library/postgres:16.3-alpine","lifecycle":"start","observedAtUnix":1787433000}\n'
                '{"image":"docker.io/library/postgres:16.3-alpine","lifecycle":"die","observedAtUnix":1787433060}\n'
            )
            # The shared recorder and daemon event stream observe the same
            # lifecycle at slightly different instants. Keep one event per
            # lifecycle rather than inflating the UI's runtime evidence count.
            (runtime / "testcontainers.jsonl").write_text(
                '{"schemaVersion":1,"resource":"postgres","image":"postgres:16.3-alpine","lifecycle":"started","observedAt":"2026-08-22T21:10:01Z"}\n'
                '{"schemaVersion":1,"resource":"postgres","image":"postgres:16.3-alpine","lifecycle":"stopped","observedAt":"2026-08-22T21:11:01Z"}\n'
                '{"schemaVersion":1,"resource":"postgres","image":"postgres:16.3-alpine","lifecycle":"started","observedAt":"2026-08-22T21:10:10Z"}\n'
            )
            performance = service / "perf.json"
            # This is k6's actual summary-export form: true means the threshold was crossed.
            performance.write_text('{"metrics":{"http_req_duration":{"thresholds":{"p(95)<500":true}}}}')
            mutation = service / "mutations.xml"
            mutation.write_text('<mutations><mutation status="KILLED"/><mutation status="SURVIVED"/></mutations>')
            # Negative control for the report discovery itself: a vitest/Playwright
            # `<testsuites>` file is not named TEST-*.xml, and globbing that Gradle
            # convention reported an empty Admin UI envelope while its suites passed.
            for task, payload in (
                ("test", '<testsuites name="vitest" tests="2" failures="1" errors="0" skipped="0" time="1.5">'
                          '<testsuite name="unit" tests="2" failures="1" errors="0" skipped="0" time="1.5">'
                          '<testcase classname="com.openbank.GuardTest" name="allows" time="0.5"/>'
                          '<testcase classname="com.openbank.GuardTest" name="denies" time="1.0"><failure/></testcase>'
                          '<system-out>OPENBANK_TRACE_CONTRACT_V1:failed-contract\n'
                          'OPENBANK_TRACE_CONTRACT_V1:customer supplied trace id</system-out>'
                          '</testsuite></testsuites>'),
                ("e2e", '<testsuites name="playwright" tests="1" failures="0" errors="0" skipped="0" time="2">'
                        '<testsuite name="nav" tests="1" failures="0" errors="0" skipped="0" time="2">'
                        '<testcase classname="nav.spec.ts" name="loads" time="2"/>'
                        '<system-out>OPENBANK_TRACE_CONTRACT_V1:public-edge</system-out>'
                        '</testsuite></testsuites>'),
            ):
                directory = service / "build/test-results" / task
                directory.mkdir(parents=True, exist_ok=True)
                (directory / f"{task}.xml").write_text(payload)
            source = service / "src/test/kotlin/com/openbank/GuardTest.kt"
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("package com.openbank\nclass GuardTest\n")
            discovered = {row["kind"]: row for row in suites("openbank-admin-ui", service)}
            assert set(discovered) == {"unit", "e2e"}, discovered
            assert (discovered["unit"]["discovered"], discovered["unit"]["failed"]) == (2, 1), discovered
            assert discovered["unit"]["state"] == "failed" and discovered["e2e"]["state"] == "passed"
            cases = test_cases("openbank-admin-ui", service)
            assert len(cases) == 3
            assert {item.get("testDefinitionPath") for item in cases} == {"src/test/kotlin/com/openbank/GuardTest.kt", None}
            assert trace_contract_evidence(service) == [
                {"kind": "trace", "state": "failed", "source": "trace-contract:failed-contract",
                 "detail": "1 executed marker(s); JUnit suite failed"},
                {"kind": "trace", "state": "passed", "source": "trace-contract:public-edge",
                 "detail": "1 executed marker(s); JUnit suite passed"},
            ]
            # An unparsable report is observed as absent, never as a build failure.
            (service / "build/test-results/test/broken.xml").write_text("<testsuite")
            assert len(suites("openbank-admin-ui", service)) == 2
            # A report whose failures exceed its executed count still yields a valid envelope.
            (service / "build/test-results/test/TEST-Truncated.xml").write_text(
                '<testsuite name="Truncated" tests="1" failures="3" errors="0" skipped="0" time="0"/>')
            validate_envelope({
                "schemaVersion": 1,
                "run": {"id": "1", "attempt": 1, "commit": "1234567", "branch": "main",
                        "workflow": "CI", "url": "", "observedAt": "2026-08-22T00:00:00Z"},
                "component": "openbank-admin-ui", "suites": suites("openbank-admin-ui", service),
                "coverage": None, "testInfrastructure": {"declared": [], "observed": []},
            })
            assert classify("PaymentApiIT", "com.openbank.PaymentApiIT", "test", "openbank-x") == "integration"
            assert classify("UnitTest", "com.openbank.UnitTest", "test", "openbank-x") == "unit"
            observed = observations(service)
            assert [item["lifecycle"] for item in observed] == ["started", "started", "stopped"]
            assert [item["observedAt"] for item in observed] == [
                "2026-08-22T21:10:01Z", "2026-08-22T21:10:10Z", "2026-08-22T21:11:01Z",
            ]
            specialized = specialized_evidence(str(performance), str(mutation))
            assert [(item["kind"], item["state"]) for item in specialized] == [("performance", "failed"), ("mutation", "passed")]
            absent = specialized_evidence(str(service / "missing-summary.json"), None, "no safe target configured")
            assert absent == [{"kind": "performance", "state": "not-run", "source": str(service / "missing-summary.json"), "detail": "no safe target configured"}]
            synthetic = specialized_evidence(None, None, synthetic_summary=str(performance), synthetic_journey="public-edge")
            assert synthetic == [{"kind": "synthetic", "state": "failed", "source": "journey:public-edge", "detail": "1 threshold result(s), 1 breached"}]
            valid = {
                "schemaVersion": 1,
                "run": {"id": "1", "attempt": 1, "commit": "1234567", "branch": "main", "workflow": "CI", "url": "https://github.com/JiRaska/open-bank-oss/actions/runs/1", "observedAt": "2026-08-22T21:12:00Z"},
                "component": "openbank-x",
                "suites": [{"kind": "integration", "state": "passed", "discovered": 1, "executed": 1, "passed": 1, "failed": 0, "skipped": 0, "errors": 0, "durationMs": 1}],
                "coverage": None,
                "testInfrastructure": {"declared": ["postgres"], "observed": observations(service)},
                "specializedEvidence": [{"kind": "performance", "state": "passed", "source": "summary.json"}],
                "testCases": [],
                "testImpact": {"schemaVersion": 1, "mode": "shadow", "mappingState": "unknown", "selectionState": "unavailable"},
            }
            traversal = {**valid, "testCases": [{
                "fingerprint": "0123456789abcdef01234567", "kind": "integration",
                "classname": "com.openbank.GuardTest", "name": "guards", "state": "passed",
                "durationMs": 1, "testDefinitionPath": "src/test/kotlin/../../outside.kt",
            }]}
            try:
                validate_envelope(traversal)
                raise AssertionError("a traversing test definition path was accepted")
            except ValueError:
                pass
            invented_impact = json.loads(json.dumps(valid))
            invented_impact["testImpact"]["mappingState"] = "mapped"
            try:
                validate_envelope(invented_impact)
                raise AssertionError("unverified test impact mapping was accepted")
            except ValueError:
                pass
            (service / "playwright-report").mkdir()
            (service / "playwright-report/index.html").write_text("diagnostic")
            valid["diagnostics"] = browser_diagnostics(str(service / "playwright-report"), "1", 1, "https://github.com/JiRaska/open-bank-oss/actions/runs/1")
            assert valid["diagnostics"] == [{
                "kind": "playwright-report", "suiteKind": "e2e", "name": "playwright-report-1-a1",
                "url": "https://github.com/JiRaska/open-bank-oss/actions/runs/1#artifacts", "retentionDays": 7,
                "access": "github-run-authenticated", "mayContainSensitiveData": True,
            }]
            validate_envelope(valid)
            untrusted_run = json.loads(json.dumps(valid))
            untrusted_run["run"]["url"] = "https://attacker.example/actions/runs/1"
            try:
                validate_envelope(untrusted_run)
                raise AssertionError("an untrusted run URL must be rejected")
            except ValueError:
                pass
            untrusted_diagnostic = json.loads(json.dumps(valid))
            untrusted_diagnostic["diagnostics"][0]["url"] = "https://attacker.example/report"
            try:
                validate_envelope(untrusted_diagnostic)
                raise AssertionError("an off-run diagnostic URL must be rejected")
            except ValueError:
                pass
            invalid = json.loads(json.dumps(valid))
            invalid["suites"][0]["executed"] = 0
            try:
                validate_envelope(invalid)
                raise AssertionError("invalid suite arithmetic was accepted")
            except ValueError:
                pass
            future_run = json.loads(json.dumps(valid))
            future_run["run"]["observedAt"] = "2999-01-01T00:00:00Z"
            try:
                validate_envelope(future_run)
                raise AssertionError("a future-dated run was accepted")
            except ValueError:
                pass
            naive_run = json.loads(json.dumps(valid))
            naive_run["run"]["observedAt"] = "2026-08-22T00:00:00"
            try:
                validate_envelope(naive_run)
                raise AssertionError("a timezone-less run timestamp was accepted")
            except ValueError:
                pass
            future_runtime = json.loads(json.dumps(valid))
            future_runtime["testInfrastructure"]["observed"][0]["observedAt"] = "2999-01-01T00:00:00Z"
            try:
                validate_envelope(future_runtime)
                raise AssertionError("a runtime observation after its run was accepted")
            except ValueError:
                pass
        print("test-run evidence collector self-test: classification and runtime red/green paths proven")
        return
    if not args.service or not args.out:
        parser.error("--service and --out are required unless --self-test is used")
    service = Path(args.service)
    component = args.component or service.name
    server = os.getenv("GITHUB_SERVER_URL", "")
    repository = os.getenv("GITHUB_REPOSITORY", "")
    run_id = os.getenv("GITHUB_RUN_ID", "local")
    run_attempt = int(os.getenv("GITHUB_RUN_ATTEMPT", "1"))
    run_url = f"{server}/{repository}/actions/runs/{run_id}" if server and repository else ""
    specialized = specialized_evidence(
        args.performance_summary,
        args.mutation_report,
        args.performance_not_run_detail,
        args.synthetic_summary,
        args.synthetic_journey,
    )
    specialized.extend(trace_contract_evidence(service))
    envelope = {
        "schemaVersion": 1,
        "run": {
            "id": run_id, "attempt": run_attempt,
            "commit": os.getenv("GITHUB_SHA", "local000"),
            "branch": os.getenv("GITHUB_HEAD_REF") or os.getenv("GITHUB_REF_NAME", "local"),
            "workflow": os.getenv("GITHUB_WORKFLOW", "local"),
            "url": run_url,
            "observedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        },
        "component": component, "suites": suites(component, service), "testCases": test_cases(component, service), "coverage": coverage(service),
        # v1 intentionally records absence rather than guessing a test-to-production mapping.
        # A future producer may only advance this after emitting versioned, verified coverage or
        # dependency edges and measuring recommendations against the preserved full suite (#7207).
        "testImpact": {"schemaVersion": 1, "mode": "shadow", "mappingState": "unknown", "selectionState": "unavailable"},
        "testInfrastructure": {"declared": declared_infrastructure(service), "observed": observations(service)},
        "specializedEvidence": specialized,
        "diagnostics": browser_diagnostics(args.browser_report_dir, run_id, run_attempt, run_url),
    }
    validate_envelope(envelope)
    output = Path(args.out)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(envelope, indent=2) + "\n")
    print(f"test-intelligence: {component}: {len(envelope['suites'])} suite kinds, {len(envelope['testCases'])} test observations, {len(envelope['testInfrastructure']['observed'])} runtime events -> {output}")


if __name__ == "__main__":
    main()
