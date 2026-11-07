#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise shipped canary PromQL with promtool and Argo's pinned result evaluator."""
from __future__ import annotations

import copy
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


def consumers() -> list[dict]:
    found = []
    for path in gatelib.rglob(ROOT / "openbank-infra/gitops/components", "*.yaml"):
        for doc in gatelib.load_yaml_all(path):
            if not isinstance(doc, dict) or doc.get("kind") != "Rollout":
                continue
            analysis = doc.get("spec", {}).get("strategy", {}).get("canary", {}).get("analysis", {})
            if any(t.get("templateName") == TEMPLATE for t in analysis.get("templates", [])):
                found.append({"path": str(path.relative_to(ROOT)), "name": doc["metadata"]["name"], "analysis": analysis})
    return found


def validate_consumer(consumer: dict) -> None:
    analysis = consumer["analysis"]
    args = {arg["name"]: arg for arg in analysis.get("args", [])}
    assert args.get("canary-hash") == {"name": "canary-hash", "valueFrom": {"podTemplateHashValue": "Latest"}}, consumer
    assert args.get("namespace") == {"name": "namespace", "valueFrom": {"fieldRef": {"fieldPath": "metadata.namespace"}}}, consumer
    assert any(t.get("templateName") == TEMPLATE and t.get("clusterScope") is True for t in analysis["templates"]), consumer


def metrics() -> list[dict]:
    template = gatelib.load_yaml(ROOT / "openbank-infra/gitops/components/argo-rollouts/analysis-template.yaml")
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
    command = ["go", "run", "-mod=readonly", "."]
    run = subprocess.run(command, cwd=HERE, input=json.dumps(expressions), capture_output=True, text=True)
    assert run.returncode == 0, run.stdout + run.stderr
    if not quiet:
        print(f"PASS: {len(tests)} actual-query cases, {len(expressions)} pinned Argo result cases")


def self_test(shipped: list[dict], actual_consumers: list[dict]) -> None:
    # Mutate every real parsed consumer, not a handcrafted valid example.
    for consumer in actual_consumers:
        for bad in ["Canary", "Stable", "", None]:
            mutant = copy.deepcopy(consumer)
            next(a for a in mutant["analysis"]["args"] if a["name"] == "canary-hash")["valueFrom"]["podTemplateHashValue"] = bad
            try:
                validate_consumer(mutant)
            except AssertionError:
                pass
            else:
                raise AssertionError(f"consumer guard accepted {bad!r}: {consumer['path']}")
    print(f"SELF-TEST PASS: {4 * len(actual_consumers)} parsed-consumer mutations rejected")
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
    gatelib.subjects(len(actual), "Rollouts using shared canary analysis")
    shipped = metrics()
    prove(shipped)
    if "--self-test" in sys.argv:
        self_test(shipped, actual)
