#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Stage trusted main performance artifacts in memory, without writing a checkout.

The caller owns authenticated GET callbacks, archive safety and envelope validation.
Missing latest evidence is returned explicitly; API/archive/identity failures raise.
No absent producer artifact is replaced with a synthetic run envelope.
"""
from __future__ import annotations

import importlib.util
import io
import json
import math
from datetime import timedelta
from pathlib import Path
import re
import sys
from urllib.parse import urlencode
import zipfile

API = "https://api.github.com"
MAX_PAGES = 100
MAX_ARCHIVE_BYTES = 200 * 1024 * 1024
MAX_JSON_BYTES = 4 * 1024 * 1024
MAX_PAYLOAD_BYTES = 32 * 1024 * 1024
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
COMPONENT = re.compile(r"openbank-[a-z0-9]+(?:-[a-z0-9]+)*\Z")


class StageError(ValueError):
    pass


def _require(condition, message):
    if not condition:
        raise StageError(message)


def _load(repo_root, filename, module_name):
    spec = importlib.util.spec_from_file_location(module_name, repo_root / ".github/scripts" / filename)
    _require(spec is not None and spec.loader is not None, "performance helper is unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


def _date(selector, value):
    try:
        parsed = selector.instant(value)
        _require(parsed.tzinfo is not None and parsed.utcoffset() is not None, "performance timestamp needs timezone")
        return parsed
    except (ValueError, TypeError) as error:
        raise StageError("performance timestamp is invalid") from error


def _not_after_reported_time(observed, raw_timestamp, selector):
    # Whole-second GitHub API values describe [second, second + 1 second).
    # A fractional API value keeps its exact upper bound.
    reported = _date(selector, raw_timestamp)
    whole_second = re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})", raw_timestamp)
    return observed < reported + timedelta(seconds=1) if whole_second else observed <= reported


def _trusted_run(run, repo_slug, workflow, selector):
    _require(isinstance(run, dict), "performance run metadata is not an object")
    repository = run.get("head_repository")
    repository = repository.get("full_name") if isinstance(repository, dict) else repository
    _require(repository == repo_slug and run.get("head_branch") == "main"
             and run.get("path") == ".github/workflows/" + workflow
             and run.get("event") in {"push", "schedule", "workflow_dispatch"}
             and run.get("status") == "completed"
             and run.get("name") == ("Performance gate" if workflow == "perf-gate.yml" else "Perf baseline")
             and isinstance(run.get("head_sha"), str)
             and re.fullmatch(r"[a-f0-9]{40}", run["head_sha"]) is not None,
             "performance producer is not a trusted completed main workflow")
    _require(type(run.get("id")) is int and run["id"] > 0
             and type(run.get("run_attempt")) is int and run["run_attempt"] > 0,
             "performance run identity is invalid")
    _require(_date(selector, run.get("updated_at")) >= _date(selector, run.get("run_started_at")),
             "performance run timestamps are inconsistent")


def _pages(fetch_json, url, field):
    pages = []
    for number in range(1, MAX_PAGES + 1):
        page = fetch_json(url + ("&" if "?" in url else "?") + urlencode({"per_page": 100, "page": number}))
        _require(isinstance(page, dict) and isinstance(page.get(field), list)
                 and all(isinstance(row, dict) for row in page[field]), "performance API page is malformed")
        pages.append(page)
        if len(page[field]) < 100:
            return pages
    raise StageError("performance API pagination exceeded its complete-history budget")


def _baseline_specs(repo_root, warnings):
    candidates = []
    for file in sorted(repo_root.glob("openbank-*/src/test/k6/*-read-baseline.js")):
        component = file.relative_to(repo_root).parts[0]
        scenario = file.stem
        identifier = scenario.removesuffix("-read-baseline")
        if COMPONENT.fullmatch(component) and re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", identifier):
            candidates.append({"component": component, "scenario_id": component + "-" + scenario,
                               "artifact": "k6-" + scenario + "-summary",
                               "job": "k6 module read baseline (" + identifier + ")",
                               "summary": identifier + "-summary.json", "envelope": identifier + "-perf-run.json"})
    for spec in candidates:
        if (sum(other["component"] == spec["component"] for other in candidates) != 1
                or sum(other["artifact"] == spec["artifact"] for other in candidates) != 1):
            spec["ambiguous"] = True
            warnings.append("Ambiguous baseline definition was not attributed: " + spec["scenario_id"])
            # Keep the declaration authoritative so an old aggregate pass cannot
            # stand in for a baseline whose current producer cannot be identified.
    return [{"component": "openbank-money-path", "scenario_id": "money-path-smoke",
             "artifact": "k6-money-path-summary", "job": "k6 money-path read baseline",
             "summary": "perf-summary.json", "envelope": "perf-run.json"}, *candidates]


def _gate_specs(selector):
    return [{"component": artifact.removeprefix("perf-reports-"),
             "scenario_id": artifact.removeprefix("perf-reports-") + "-"
                 + artifact.removeprefix("perf-reports-openbank-") + "-smoke",
             "artifact": artifact, "job": job,
             "summary": artifact.removeprefix("perf-reports-") + "-summary.json",
             "envelope": artifact.removeprefix("perf-reports-") + "-run.json"}
            for artifact, job in selector.EXPECTED_GATE]


def _baseline_policies(jobs, start, specs, selector):
    policies = {}
    for spec in specs:
        if spec.get("ambiguous"):
            continue
        matches = [job for job in jobs if job.get("name") == spec["job"]]
        if len(matches) != 1 or not matches[0].get("started_at"):
            continue
        job = matches[0]
        job_start = _date(selector, job["started_at"])
        if job_start >= start:
            policies[spec["artifact"]] = selector.ArtifactWindow(job_start)
        elif job.get("conclusion") == "success":
            policies[spec["artifact"]] = selector.ArtifactWindow(job_start, start)
    return policies


def _json(raw):
    _require(isinstance(raw, bytes) and len(raw) <= MAX_JSON_BYTES, "performance JSON exceeds size limit")
    def pairs(items):
        result = {}
        for key, value in items:
            _require(key not in result, "performance JSON contains duplicate keys")
            result[key] = value
        return result
    def invalid_constant(_value):
        raise StageError("performance JSON contains a non-finite number")
    try:
        parsed = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (ValueError, UnicodeError) as error:
        raise StageError("performance JSON is malformed") from error
    _require(isinstance(parsed, dict), "performance JSON must be an object")
    return parsed


def _bundle(raw, spec, safe_archive_members):
    _require(isinstance(raw, bytes) and len(raw) <= MAX_ARCHIVE_BYTES, "performance archive exceeds size limit")
    try:
        entries = safe_archive_members(raw)
        with zipfile.ZipFile(io.BytesIO(raw)) as zipped:
            selected = {}
            for kind in ("summary", "envelope"):
                matches = [info for info, path in entries if path.name == spec[kind]]
                _require(len(matches) <= 1, "performance archive has ambiguous JSON basenames")
                if matches:
                    _require(matches[0].file_size <= MAX_JSON_BYTES, "performance JSON exceeds size limit")
                    selected[kind] = zipped.read(matches[0])
            return selected
    except (RuntimeError, ValueError, zipfile.BadZipFile, OSError) as error:
        raise StageError("unsafe or malformed performance archive") from error


def _validate_summary(summary, performance):
    metrics = summary.get("metrics")
    _require(isinstance(metrics, dict) and all(isinstance(value, dict) for value in metrics.values()),
             "performance summary has invalid metrics")
    duration = metrics.get("http_req_duration", {})
    requests = metrics.get("http_reqs", {})
    duration_values = duration.get("values", {})
    request_values = requests.get("values", {})
    _require(isinstance(duration_values, dict) and isinstance(request_values, dict),
             "performance summary metric values are malformed")
    p95 = duration.get("p(95)", duration_values.get("p(95)"))
    count = requests.get("count", request_values.get("count"))
    _require(type(p95) in (int, float) and math.isfinite(p95) and p95 >= 0
             and type(count) in (int, float) and math.isfinite(count) and count > 0,
             "performance summary has no finite HTTP latency/request observations")
    outcomes = []
    for metric in metrics.values():
        thresholds = metric.get("thresholds", {})
        _require(isinstance(thresholds, dict), "performance thresholds are malformed")
        for value in thresholds.values():
            if type(value) is bool:
                outcomes.append(value)  # k6 summary-export: true means breached.
            else:
                _require(isinstance(value, dict) and type(value.get("ok")) is bool,
                         "performance threshold outcome is invalid")
                outcomes.append(not value["ok"])
    expected = {"evaluated": len(outcomes), "breached": sum(outcomes)}
    if performance.get("thresholdResults") is not None:
        _require(performance["thresholdResults"] == expected, "performance envelope/summary thresholds disagree")
    if performance.get("state") == "passed":
        _require(bool(outcomes) and not any(outcomes), "performance pass has no passing threshold denominator")
    elif performance.get("state") == "failed":
        _require(any(outcomes), "performance failure disagrees with raw threshold outcomes")
    _require(performance.get("state") != "not-run", "not-run envelope contradicts a retained summary")


def _validate_identity(raw, artifact, metadata, spec, repo_slug, workflow, selector, fetch_json, validate_envelope):
    envelope = _json(raw)
    try:
        validate_envelope(raw)
    except (ValueError, TypeError, KeyError) as error:
        raise StageError("performance envelope schema is invalid") from error
    evidence = envelope.get("run", {})
    attempt = evidence.get("attempt")
    _require(type(attempt) is int and 0 < attempt <= metadata["run_attempt"], "performance envelope attempt is invalid")
    producer = metadata if attempt == metadata["run_attempt"] else fetch_json(
        f"{API}/repos/{repo_slug}/actions/runs/{metadata['id']}/attempts/{attempt}")
    _trusted_run(producer, repo_slug, workflow, selector)
    _require(producer["id"] == metadata["id"] and producer["run_attempt"] == attempt
             and producer["head_sha"] == metadata["head_sha"]
             and envelope.get("component") == spec["component"]
             and evidence.get("id") == str(producer["id"])
             and evidence.get("commit") == producer["head_sha"]
             and evidence.get("branch") == "main" and evidence.get("workflow") == producer["name"]
             and evidence.get("url") == f"https://github.com/{repo_slug}/actions/runs/{producer['id']}",
             "performance envelope producer/component identity mismatch")
    observed = _date(selector, evidence.get("observedAt"))
    created = _date(selector, artifact.get("created_at"))
    started, finished = _date(selector, producer["run_started_at"]), _date(selector, producer["updated_at"])
    _require(started <= observed and started <= created <= finished
             and _not_after_reported_time(observed, artifact["created_at"], selector)
             and _not_after_reported_time(observed, producer["updated_at"], selector),
             "performance envelope/artifact attempt timestamps are inconsistent")
    specialized = [row for row in envelope.get("specializedEvidence", []) if row.get("kind") == "performance"]
    _require(len(specialized) == 1, "performance envelope must have one performance observation")
    return specialized[0]


def stage_performance(repo_root, repo_slug, fetch_json, fetch_bytes, *, safe_archive_members,
                      validate_envelope=None, fetch_log=None, selector=None):
    """Return payloads + authoritative per-workflow sources; all payload paths are relative.

    fetch_json(url) and fetch_bytes(url) perform authenticated GETs. fetch_log(job_id),
    when supplied, returns only a trusted derive-scope job's decoded log. Missing
    evidence stays in source['missing']; source['scope']=='barrier' blocks older
    runs. Present malformed evidence or API failures raise instead of looking absent.
    """
    repo_root = Path(repo_root)
    _require(REPOSITORY.fullmatch(repo_slug) is not None, "repository identity is invalid")
    selector = selector or _load(repo_root, "select-performance-evidence.py", "admin_ui_performance_selector")
    if validate_envelope is None:
        validate_envelope = _load(repo_root, "extract-test-intelligence-envelope.py", "admin_ui_performance_envelope").validate
    result = {"payloads": {}, "sources": [], "warnings": []}
    sources = [
        ("perf-gate.yml", "smoke", _gate_specs(selector)),
        ("perf-baseline.yml", "baseline", _baseline_specs(repo_root, result["warnings"])),
        ("perf-gate.yml", "security-abuse", [{"component": "openbank-security-abuse", "scenario_id": "security-abuse-smoke",
            "artifact": "perf-reports-security-abuse", "job": "k6 security abuse (product-catalog, auth on)",
            "summary": "security-abuse-summary.json", "envelope": "security-abuse-run.json"}]),
    ]
    for workflow, lane, specs in sources:
        pages = _pages(fetch_json, f"{API}/repos/{repo_slug}/actions/workflows/{workflow}/runs?branch=main&status=completed", "workflow_runs")
        for page in pages:
            for metadata in page["workflow_runs"]:
                _trusted_run(metadata, repo_slug, workflow, selector)
        candidates = selector.ordered_runs(pages)
        source = {"workflow": workflow, "lane": lane, "run_id": None, "attempt": None, "scope": "absent",
                  "expected": [{"scenario_id": spec["scenario_id"], "component": spec["component"],
                                **({"ambiguous": True} if spec.get("ambiguous") else {})} for spec in specs],
                  "selected": [], "missing": []}
        result["sources"].append(source)
        for metadata in candidates:
            run_id = metadata["id"]
            jobs_pages = _pages(fetch_json, f"{API}/repos/{repo_slug}/actions/runs/{run_id}/jobs?filter=latest", "jobs")
            jobs = [job for page in jobs_pages for job in page["jobs"]]
            start = _date(selector, metadata["run_started_at"])
            if lane == "smoke":
                log = ""
                names = {job.get("name") for job in jobs if str(job.get("name", "")).startswith("k6 smoke (")}
                expected_names = {name for _, name in selector.EXPECTED_GATE}
                if names != expected_names and metadata["event"] == "workflow_dispatch" and fetch_log:
                    prepare_id = selector.prepare_job_identifier({"jobs": jobs})
                    if prepare_id is not None:
                        log = fetch_log(prepare_id)
                        _require(isinstance(log, str) and len(log.encode()) <= MAX_JSON_BYTES, "performance scope log is invalid")
                scope, policies = selector.gate_policies({"jobs": jobs}, metadata["run_started_at"], metadata["event"], log)
                if scope == "narrowed":
                    result["warnings"].append(f"Skipped positively-proven narrow perf-gate dispatch {run_id}.")
                    continue
            else:
                scope = "full"
                policies = _baseline_policies(jobs, start, specs, selector)
            source.update({"run_id": run_id, "attempt": metadata["run_attempt"], "scope": scope if scope == "full" else "barrier",
                           "commit": metadata["head_sha"], "observed_at": metadata["run_started_at"]})
            if scope != "full":
                source["missing"] = [{**entry, "reason": "latest completed run scope is unproven"} for entry in source["expected"]]
                break
            artifact_pages = _pages(fetch_json, f"{API}/repos/{repo_slug}/actions/runs/{run_id}/artifacts", "artifacts")
            selected = selector.newest_artifacts(artifact_pages, policies)
            selected = {artifact["name"]: artifact for artifact in selected}
            for spec in specs:
                absent = {"scenario_id": spec["scenario_id"], "component": spec["component"]}
                if spec.get("ambiguous"):
                    source["missing"].append({**absent, "reason": "ambiguous baseline producer mapping"})
                    continue
                artifact = selected.get(spec["artifact"])
                if artifact is None:
                    source["missing"].append({**absent, "reason": "no attributable latest-job artifact"})
                    continue
                producer = artifact.get("workflow_run")
                _require(isinstance(producer, dict) and producer.get("id") == run_id
                         and producer.get("head_sha") == metadata["head_sha"] and producer.get("head_branch") == "main",
                         "performance artifact producer identity mismatch")
                bundle = _bundle(fetch_bytes(f"{API}/repos/{repo_slug}/actions/artifacts/{artifact['id']}/zip"), spec, safe_archive_members)
                if "envelope" not in bundle:
                    source["missing"].append({**absent, "reason": "producer envelope absent"})
                    continue
                performance = _validate_identity(bundle["envelope"], artifact, metadata, spec, repo_slug,
                                                 workflow, selector, fetch_json, validate_envelope)
                if "summary" not in bundle and performance.get("state") != "not-run":
                    source["missing"].append({**absent, "reason": "measured producer summary absent"})
                    continue
                if "summary" in bundle:
                    _validate_summary(_json(bundle["summary"]), performance)
                key = spec["scenario_id"] + "-summary.json"
                payloads = {"openbank-admin-ui/perf-artifacts/" + key + ".run.json": bundle["envelope"]}
                if "summary" in bundle:
                    payloads["openbank-admin-ui/perf-artifacts/" + key] = bundle["summary"]
                _require(not result["payloads"].keys() & payloads.keys(), "performance payload destination collision")
                result["payloads"].update(payloads)
                _require(sum(map(len, result["payloads"].values())) <= MAX_PAYLOAD_BYTES, "performance payload set exceeds size limit")
                source["selected"].append({**absent, "artifact_id": artifact["id"], "state": performance["state"],
                                           "artifact_created_at": artifact["created_at"]})
            break  # The newest non-narrow attempt is authoritative, even with missing evidence.
        if source["scope"] == "absent":
            source["missing"] = [{**entry, "reason": "ambiguous baseline producer mapping" if entry.get("ambiguous")
                                  else "no completed authoritative main run"} for entry in source["expected"]]
        if source["missing"]:
            result["warnings"].append(f"{workflow}: {len(source['missing'])} performance scenario(s) lack attributable current evidence.")
    return result
