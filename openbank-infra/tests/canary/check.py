#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise shipped canary PromQL with promtool and Argo's pinned result evaluator."""
from __future__ import annotations

import atexit
import copy
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / ".github/scripts"))
import gatelib

TEMPLATE = "openbank-money-path-canary"
STRICT_TEMPLATE = "openbank-money-path-canary-strict"


def consumers() -> list[dict]:
    found = []
    for path in gatelib.rglob(ROOT / "openbank-infra/gitops/components", "*.yaml"):
        for doc in gatelib.load_yaml_all(path):
            if not isinstance(doc, dict) or doc.get("kind") != "Rollout":
                continue
            analysis = doc.get("spec", {}).get("strategy", {}).get("canary", {}).get("analysis", {})
            if any(t.get("templateName") in (TEMPLATE, STRICT_TEMPLATE) for t in analysis.get("templates", [])):
                canary = doc["spec"]["strategy"]["canary"]
                found.append({"path": str(path.relative_to(ROOT)), "name": doc["metadata"]["name"], "analysis": analysis,
                              "stableService": canary.get("stableService"), "trafficRouting": canary.get("trafficRouting")})
    return found


def validate_consumer(consumer: dict) -> None:
    analysis = consumer["analysis"]
    assert not any(t.get("templateName") == STRICT_TEMPLATE for t in analysis.get("templates", [])), "strict activation awaits reviewed real-traffic and abort/recovery proof (#16630)"
    assert not analysis.get("dryRun"), f"legacy analysis must stay active: {consumer['name']}"
    args = {arg["name"]: arg for arg in analysis.get("args", [])}
    assert args.get("canary-hash") == {"name": "canary-hash", "valueFrom": {"podTemplateHashValue": "Canary"}}, consumer
    assert args.get("namespace") == {"name": "namespace", "valueFrom": {"fieldRef": {"fieldPath": "metadata.namespace"}}}, consumer
    assert any(t.get("templateName") == TEMPLATE and t.get("clusterScope") is True for t in analysis["templates"]), consumer


def legacy_baseline() -> dict:
    data = gatelib.load_yaml(HERE / "legacy-analysis-baseline.yaml")
    assert data.get("issue") == 16630, "baseline must name the real-traffic migration"
    return data


def analysis_fingerprint(analysis: dict) -> str:
    return hashlib.sha256(json.dumps(analysis, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def validate_coverage(actual: list[dict], baseline: dict) -> None:
    """This first slice changes no active consumer; strict activation needs a separate reviewed migration."""
    expected = {(r["path"], r["name"]): r["analysis_sha256"] for r in baseline["rollouts"]}
    assert len(expected) == len(baseline["rollouts"]), "duplicate legacy baseline row"
    keys = {(c["path"], c["name"]) for c in actual}
    assert keys == set(expected), "legacy analysis inventory changed; explicit migration review required"
    legacy = ROOT / "openbank-infra/gitops/components/argo-rollouts/analysis-template.yaml"
    assert hashlib.sha256(legacy.read_bytes()).hexdigest() == baseline["legacy_template_sha256"], "legacy template changed"
    for c in actual:
        validate_consumer(c)
        assert analysis_fingerprint(c["analysis"]) == expected[(c["path"], c["name"])], f"legacy active analysis changed: {c['name']}"


def metrics() -> list[dict]:
    template = gatelib.load_yaml(ROOT / "openbank-infra/gitops/components/argo-rollouts/strict-analysis-template.yaml")
    return template["spec"]["metrics"]


def query(metric: dict, hash_value: str) -> str:
    return metric["provider"]["prometheus"]["query"].replace("{{ args.namespace }}", "test-a").replace("{{ args.canary-hash }}", hash_value)


def series(name: str, labels: str, values: str) -> dict:
    return {"series": f"{name}{{{labels}}}", "values": values}


def traffic(hash_value: str | None = "new", *, namespace: str = "test-a", errors: bool = False, slow: bool = False, idle: bool = False, one_sample: bool = False) -> list[dict]:
    labels = f'namespace="{namespace}"'
    if hash_value is not None:
        labels += f',rollouts_pod_template_hash="{hash_value}"'
    value = "0x4" if idle else "_ _ _ _ 100" if one_sample else "0+100x4"
    counts = [series("http_server_requests_seconds_count", labels + ',status="200"', value)]
    if errors:
        counts = [series("http_server_requests_seconds_count", labels + ',status="200"', "0+94x4"), series("http_server_requests_seconds_count", labels + ',status="500"', "0+6x4")]
    bucket = "2" if slow else "0.1"
    return counts + [series("http_server_requests_seconds_bucket", labels + f',le="{bucket}"', value), series("http_server_requests_seconds_bucket", labels + ',le="+Inf"', value)]


def fixtures() -> list[tuple]:
    healthy = traffic()
    return [
        ("no-series", "new", [], [[], []], ["Inconclusive", "Inconclusive"]),
        ("empty-hash-with-busy-unlabeled-sibling", "", traffic(None), [[], []], ["Inconclusive", "Inconclusive"]),
        ("idle-canary", "new", traffic(idle=True), [[], [math.nan]], ["Inconclusive", "Inconclusive"]),
        ("insufficient-samples", "new", traffic(one_sample=True), [[], []], ["Inconclusive", "Inconclusive"]),
        ("healthy-without-5xx-family", "new", healthy, [[0.0], [0.095]], ["Successful", "Successful"]),
        ("healthy-canary-failing-stable", "new", healthy + traffic("old", errors=True, slow=True), [[0.0], [0.095]], ["Successful", "Successful"]),
        ("healthy-canary-failing-unlabeled-sibling", "new", healthy + traffic(None, errors=True, slow=True), [[0.0], [0.095]], ["Successful", "Successful"]),
        ("healthy-canary-failing-other-namespace", "new", healthy + traffic(namespace="test-b", errors=True, slow=True), [[0.0], [0.095]], ["Successful", "Successful"]),
        ("failing-canary-healthy-stable", "new", traffic(errors=True, slow=True) + traffic("old"), [[0.06], [1.9]], ["Failed", "Failed"]),
        ("active-count-missing-histogram", "new", healthy[:1], [[0.0], []], ["Successful", "Inconclusive"]),
        ("active-histogram-missing-count", "new", healthy[1:], [[], [0.095]], ["Inconclusive", "Successful"]),
    ]


def expression_case(metric: dict, name: str, values: list[float], want: str) -> dict:
    return {"Name": metric["name"] + "/" + name, "Success": metric["successCondition"], "Failure": metric["failureCondition"], "Values": [str(v) for v in values], "Want": want}


_EVALUATOR_COMMAND: list[str] | None = None


def evaluator_command() -> list[str]:
    """Build the unchanged pinned evaluator once; each proof still executes every result case."""
    global _EVALUATOR_COMMAND
    if _EVALUATOR_COMMAND is None:
        directory = tempfile.TemporaryDirectory(prefix="openbank-canary-evaluator-")
        binary = Path(directory.name) / "evaluate"
        # The temporary evaluator does not use VCS metadata. Disable stamping so Go
        # does not consult repository-wide Git attributes and ignore rules.
        run = subprocess.run(["go", "build", "-mod=readonly", "-buildvcs=false", "-o", str(binary), "."],
                             cwd=HERE, capture_output=True, text=True)
        if run.returncode:
            directory.cleanup()
            raise AssertionError(run.stdout + run.stderr)
        atexit.register(directory.cleanup)
        _EVALUATOR_COMMAND = [str(binary)]
    return list(_EVALUATOR_COMMAND)


def prove(shipped: list[dict], *, quiet: bool = False) -> None:
    """Expected query values are first checked by the real engine, then evaluated by Argo."""
    assert [m["name"] for m in shipped] == ["error-rate", "p95-latency"]
    tests, expressions = [], []
    for name, hash_value, inputs, expected, phases in fixtures():
        promql = []
        for metric, values, phase in zip(shipped, expected, phases):
            expr = query(metric, hash_value)
            # promtool compares NaN unequal to itself. Assert its IEEE property instead.
            if values and math.isnan(values[0]):
                promql.append({"expr": f"count(({expr}) != ({expr}))", "eval_time": "4m", "exp_samples": [{"labels": "{}", "value": 1}]})
            else:
                promql.append({"expr": expr, "eval_time": "4m", "exp_samples": [{"labels": "{}", "value": value} for value in values]})
            expressions.append(expression_case(metric, name, values, phase))
        tests.append({"name": name, "interval": "1m", "input_series": inputs, "promql_expr_test": promql})
    # Boundary bands remain neutral; coverage loss is not an observed SLO breach.
    for metric, success, failure in zip(shipped, [0.01, 0.5], [0.05, 1.0]):
        for name, values, phase in [
            ("empty", [], "Inconclusive"), ("nan", [math.nan], "Inconclusive"),
            ("negative-infinity", [-math.inf], "Inconclusive"), ("infinity", [math.inf], "Failed"),
            ("below-success", [success * 0.99], "Successful"), ("at-success", [success], "Inconclusive"),
            ("neutral-band", [(success + failure) / 2], "Inconclusive"), ("at-failure", [failure], "Failed"),
            ("above-failure", [failure * 1.01], "Failed"), ("ambiguous-vector", [0.0, 0.0], "Inconclusive"),
        ]:
            expressions.append(expression_case(metric, name, values, phase))
    with tempfile.TemporaryDirectory(prefix="openbank-canary-") as tmp:
        path = Path(tmp) / "canary_test.yaml"
        path.write_text(yaml.safe_dump({"evaluation_interval": "1m", "tests": tests}, sort_keys=False))
        run = subprocess.run(["promtool", "test", "rules", str(path)], capture_output=True, text=True)
        assert run.returncode == 0, run.stdout + run.stderr
    legacy = gatelib.load_yaml(ROOT / "openbank-infra/gitops/components/argo-rollouts/analysis-template.yaml")["spec"]["metrics"]
    for metric, threshold in zip(legacy, [0.05, 1.0]):
        for name, values, phase in [("legacy-at-failure", [threshold], "Failed"),
                                    ("legacy-above-failure", [threshold * 1.2], "Failed"),
                                    ("legacy-infinity-failure", [math.inf], "Failed"),
                                    ("legacy-empty-not-health-proof", [], "Successful"),
                                    ("legacy-nan-not-health-proof", [math.nan], "Successful")]:
            expressions.append(expression_case(metric, name, values, phase))
    command = evaluator_command()
    run = subprocess.run(command, cwd=HERE, input=json.dumps(expressions), capture_output=True, text=True)
    assert run.returncode == 0, run.stdout + run.stderr
    if not quiet:
        print(f"PASS: {len(tests)} actual-query cases, {len(expressions)} pinned Argo result cases")


def self_test(shipped: list[dict], actual_consumers: list[dict]) -> None:
    # Mutate every real parsed consumer, not a handcrafted valid example.
    for consumer in actual_consumers:
        for bad in ["Latest", "Stable", "", None]:
            mutant = copy.deepcopy(consumer)
            next(a for a in mutant["analysis"]["args"] if a["name"] == "canary-hash")["valueFrom"]["podTemplateHashValue"] = bad
            try:
                validate_consumer(mutant)
            except AssertionError:
                pass
            else:
                raise AssertionError(f"consumer guard accepted {bad!r}: {consumer['path']}")
    print(f"SELF-TEST PASS: {4 * len(actual_consumers)} parsed-consumer mutations rejected")
    baseline = legacy_baseline()
    first = actual_consumers[0]
    coverage_mutations = {
        "legacy-wildcard-dryRun": [dict(first, analysis={**first["analysis"], "dryRun": [{"metricName": "*"}]})] + actual_consumers[1:],
        "legacy-partial-dryRun": [dict(first, analysis={**first["analysis"], "dryRun": [{"metricName": "error-rate"}]})] + actual_consumers[1:],
        "strict-before-traffic-proof": [dict(first, analysis={**first["analysis"], "templates": [{"templateName": STRICT_TEMPLATE, "clusterScope": True}]})] + actual_consumers[1:],
        "missing-legacy-consumer": actual_consumers[1:],
        "changed-legacy-starting-step": [dict(first, analysis={**first["analysis"], "startingStep": 99})] + actual_consumers[1:],
    }
    for name, consumers_ in coverage_mutations.items():
        try:
            validate_coverage(consumers_, baseline)
        except AssertionError:
            print(f"SELF-TEST PASS: rejected {name}")
        else:
            raise AssertionError(f"preservation guard accepted {name}")
    mutations = {
        "allow-empty": lambda m: m.update(successCondition="len(result) == 0 || (" + m["successCondition"] + ")"),
        "allow-nan": lambda m: m.update(successCondition=m["successCondition"].replace("!isNaN(result[0]) && !isInf(result[0]) && result[0] >= 0 && result[0] <", "isNaN(result[0]) || result[0] <")),
        "drop-nonempty-selector": lambda m: m["provider"]["prometheus"].update(query=m["provider"]["prometheus"]["query"].replace(', rollouts_pod_template_hash!=""', "").replace(',\n                    rollouts_pod_template_hash!=""', "")),
        "drop-hash-selector": lambda m: m["provider"]["prometheus"].update(query=m["provider"]["prometheus"]["query"].replace('rollouts_pod_template_hash="{{ args.canary-hash }}",', "")),
        "drop-namespace-selector": lambda m: m["provider"]["prometheus"].update(query=m["provider"]["prometheus"]["query"].replace('namespace="{{ args.namespace }}",', "")),
        "unconditional-zero-fill": lambda m: m["provider"]["prometheus"].update(query="(" + m["provider"]["prometheus"]["query"] + ") or vector(0)"),
        "weaken-failure-threshold": lambda m: m.update(failureCondition=m["failureCondition"].replace(">= 0.05", ">= 0.5").replace(">= 1.0", ">= 10.0")),
    }
    for name, mutate in mutations.items():
        mutant = copy.deepcopy(shipped)
        for metric in mutant:
            mutate(metric)
        assert mutant != shipped, f"vacuous mutation {name}"
        try:
            prove(mutant, quiet=True)
        except AssertionError as error:
            message = str(error)
            numerical_mismatch = "exp:" in message and "got:" in message
            phase_mismatch = any(f": {phase} want " in message for phase in ("Successful", "Failed", "Inconclusive")) and "error=<nil>" in message
            assert numerical_mismatch or phase_mismatch, f"mutation {name} failed for an unintended reason: {message}"
            print(f"SELF-TEST PASS: rejected {name}")
        else:
            raise AssertionError(f"regression suite accepted {name}")


if __name__ == "__main__":
    actual = consumers()
    assert actual, "no shared-template consumers found"
    for consumer in actual:
        validate_consumer(consumer)
    validate_coverage(actual, legacy_baseline())
    gatelib.subjects(len(actual), "Rollouts using shared canary analysis")
    shipped = metrics()
    prove(shipped)
    if "--self-test" in sys.argv:
        self_test(shipped, actual)
