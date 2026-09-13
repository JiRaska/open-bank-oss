#!/usr/bin/env python3
"""A `sensitivity: self-hosted` model entry must point somewhere actually in-cluster.

WHY THIS EXISTS (ADR-0175 D3, issue #3599).

`ModelGateway.resolve()` is the data-residency control for LLM prompt content: when a caller
passes `sensitive=true` it refuses the requested model and instead picks

    registry.values.firstOrNull { it.sensitivity == Sensitivity.SELF_HOSTED }

...failing closed with "sensitive request but no self-hosted model registered" if there is none.
That fail-closed half is real and is covered by a falsifying unit test in both gateway copies.

What NOTHING checks is the other half. `sensitivity` is a free-text field on a `model-gateway.
models[]` entry, and `parseSensitivity` only compares the STRING: any entry may declare itself
`self-hosted` while its `endpoint` points at a hosted US provider. The moment ADR-0175 D3's EU
tier is registered -- the entire point of the issue -- a mislabelled or mis-typed endpoint sends
class-1/2 prompt content to a US provider under a GDPR Chapter V control that every layer reports
as working: the gateway logs `sensitive routing: X -> Y`, the fail-closed test still passes
(a self-hosted model IS registered), and the EU AI Act inventory reads the residency claim off
`agents.yaml` and renders it as a control.

That is a green signal that cannot distinguish routed-to-EU from routed-anywhere. This gate is
the layer at which that distinction is expressible in this repo, so it is the layer that asserts
it.

WHAT IT CANNOT DO -- read this before treating it as coverage. This is a CONFIG-LEVEL gate. It
proves that the committed configuration cannot express an off-cluster self-hosted tier. It does
NOT observe a single request, cannot see where a packet actually went, and cannot prove that the
host behind an in-cluster address is EU-resident (an in-cluster Service could proxy anywhere --
the egress NetworkPolicy, not this gate, is what constrains that). Runtime residency is not
observable from this repository at all.

THE RULES
  R1  A self-hosted entry must declare an `endpoint`. Without one, the residency claim rests on
      nothing at all.
  R2  That endpoint must resolve to an in-cluster address (`*.svc`, `*.svc.cluster.local`,
      `localhost`, `127.0.0.1`) -- as a literal, or as the DEFAULT of a `${VAR:default}`
      placeholder. A public FQDN is rejected. An env placeholder with NO default is rejected
      too: a residency claim whose target is supplied entirely at runtime cannot be verified
      from the repo, which is the same as unverified.
  R4  The DEPLOYED value wins. R2's `${VAR:default}` is only what the service falls back to; the
      value that reaches the pod is whatever `openbank-infra/gitops/**` sets for that env name.
      This gate used to read `openbank-*/src/main/resources/application.yaml` and nothing else,
      so a self-hosted entry with an in-cluster DEFAULT passed while gitops overrode the same
      variable to a hosted US endpoint — measured: the gate printed "no self-hosted model entry
      points off-cluster" and exited 0 for exactly that construction. The corpus now includes the
      gitops env, and EVERY committed value of the variable must be in-cluster, not just the
      default. A `valueFrom:` override (no literal in the repo) is reported for the same reason
      R2 rejects a default-less placeholder: unverifiable from this repo is unverified.

  R3  `agents.yaml: model_gateway_as_built.routing` and the registered tiers must agree, in
      BOTH directions. `routing: none` while a self-hosted entry exists understates a live
      control; anything other than `none` while no self-hosted entry exists is the machine-
      claimed-but-absent control that issue #1280 already had to unwind once.

Run standalone:  .github/scripts/check-model-residency-claims.py [--enforce]
Self-test:       .github/scripts/check-model-residency-claims.py --self-test
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
AGENTS_YAML = REPO / "openbank-libs" / "governance" / "agents.yaml"

# Mirrors ModelGateway.parseSensitivity: trim, lowercase, three accepted spellings. Kept in
# lockstep deliberately -- a spelling this gate does not know but the Kotlin does would be a
# self-hosted tier the gate never inspects.
SELF_HOSTED_SPELLINGS = {"self-hosted", "self_hosted", "selfhosted"}

# An address that cannot leave the cluster on its own. `.svc` covers the Kubernetes DNS forms;
# localhost covers a sidecar. Anything else is a public name until proven otherwise.
IN_CLUSTER = re.compile(
    r"^(?:[a-z0-9-]+\.)*[a-z0-9-]+\.svc(?:\.cluster\.local)?$|^localhost$|^127\.0\.0\.1$"
)

ENV_PLACEHOLDER = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)(?::(.*))?\}$")


# The deployed half of the corpus. DERIVED by walking every gitops manifest for container env
# entries, so a new component is in scope the day it is committed — no hand-kept service list.
GITOPS_ROOT = REPO / "openbank-infra" / "gitops"


def _walk_env(node, path, out):
    """Collect every container env entry anywhere in a manifest tree."""
    if isinstance(node, dict):
        env = node.get("env")
        if isinstance(env, list):
            for item in env:
                if isinstance(item, dict) and isinstance(item.get("name"), str):
                    out.setdefault(item["name"], []).append((path, item))
        for v in node.values():
            _walk_env(v, path, out)
    elif isinstance(node, list):
        for v in node:
            _walk_env(v, path, out)


def gitops_env_overrides() -> dict:
    """{ENV_NAME: [(relpath, env_entry_dict), ...]} across every committed gitops manifest."""
    out = {}
    if not GITOPS_ROOT.is_dir():
        return out
    for src in sorted(GITOPS_ROOT.rglob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(src.read_text(encoding="utf-8")))
        except yaml.YAMLError:
            continue  # a Helm-templated or otherwise unparseable manifest sets no literal env
        for doc in docs:
            _walk_env(doc, str(src.relative_to(REPO)), out)
    return out


def is_self_hosted(raw) -> bool:
    return isinstance(raw, str) and raw.strip().lower() in SELF_HOSTED_SPELLINGS


def host_of(url: str) -> str | None:
    """Bare host of a URL, lowercased, port stripped. None when it does not look like one."""
    m = re.match(r"^[a-z][a-z0-9+.-]*://([^/?#]+)", url.strip(), re.I)
    if not m:
        return None
    authority = m.group(1)
    authority = authority.rsplit("@", 1)[-1]          # strip any userinfo
    return authority.rsplit(":", 1)[0].strip("[]").lower() if ":" in authority else authority.lower()


def endpoint_findings(entry_id: str, endpoint, where: str, env_index: dict | None = None) -> list[str]:
    """R1 + R2 + R4 for one entry. Returns one message per distinct problem."""
    if endpoint is None or (isinstance(endpoint, str) and not endpoint.strip()):
        return [f"{where}: model '{entry_id}' declares sensitivity self-hosted but has NO endpoint "
                f"— the residency claim rests on nothing (R1)."]
    if not isinstance(endpoint, str):
        return [f"{where}: model '{entry_id}' has a non-string endpoint ({endpoint!r}) (R1)."]

    findings: list[str] = []
    target = endpoint.strip()
    placeholder = ENV_PLACEHOLDER.match(target)
    if placeholder:
        var, default = placeholder.group(1), placeholder.group(2)
        findings.extend(_deployed_findings(entry_id, var, where, env_index or {}))
        if default is None or not default.strip():
            findings.append(
                f"{where}: model '{entry_id}' is self-hosted but its endpoint is ${{{var}}} "
                f"with no default — the residency target is supplied entirely at runtime and "
                f"cannot be verified from this repo (R2).")
            return findings
        target = default.strip()

    host = host_of(target)
    if host is None:
        findings.append(f"{where}: model '{entry_id}' is self-hosted but its endpoint '{target}' is not a "
                        f"URL this gate can resolve to a host (R2).")
        return findings
    if not IN_CLUSTER.match(host):
        findings.append(f"{where}: model '{entry_id}' declares sensitivity self-hosted but its endpoint "
                        f"resolves to '{host}', which is NOT in-cluster. Sensitive-context prompts routed "
                        f"to it leave the cluster — and every layer above still reports the residency "
                        f"control as working (R2).")
        return findings
    return findings


def _deployed_findings(entry_id: str, var: str, where: str, env_index: dict) -> list[str]:
    """R4 — every committed gitops value of `var` must also be in-cluster."""
    out = []
    for relpath, item in env_index.get(var, []):
        if "value" in item:
            value = item["value"]
            if not isinstance(value, str) or not value.strip():
                continue
            host = host_of(value.strip())
            if host is None or IN_CLUSTER.match(host):
                continue
            out.append(
                f"{where}: model '{entry_id}' is self-hosted and its endpoint falls back to an "
                f"in-cluster default, but {relpath} sets {var}={value.strip()} — the DEPLOYED "
                f"target resolves to '{host}', which is NOT in-cluster. The default is not what "
                f"reaches the pod (R4).")
        elif "valueFrom" in item:
            out.append(
                f"{where}: model '{entry_id}' is self-hosted and {relpath} supplies {var} via "
                f"valueFrom — the deployed residency target is not committed anywhere in this "
                f"repo, so it cannot be verified (R4).")
    return out


def walk_model_gateways(node, where: str, path: str = "") -> list[tuple[str, list]]:
    """Every `model-gateway.models` list at any depth (copilot nests its own under `copilot:`)."""
    found = []
    if isinstance(node, dict):
        for key, value in node.items():
            here = f"{path}.{key}" if path else str(key)
            if key == "model-gateway" and isinstance(value, dict):
                models = value.get("models")
                if isinstance(models, list):
                    found.append((f"{where} [{here}]", models))
            found.extend(walk_model_gateways(value, where, here))
    elif isinstance(node, list):
        for i, value in enumerate(node):
            found.extend(walk_model_gateways(value, where, f"{path}[{i}]"))
    return found


def audit_models(models: list, where: str, env_index: dict | None = None) -> tuple[list[str], int]:
    """R1+R2+R4 over one models list. Returns (findings, self_hosted_count)."""
    findings, count = [], 0
    for entry in models or []:
        if not isinstance(entry, dict) or not is_self_hosted(entry.get("sensitivity")):
            continue
        count += 1
        findings.extend(
            endpoint_findings(str(entry.get("id", "<unnamed>")), entry.get("endpoint"), where, env_index)
        )
    return findings, count


def audit_routing_record(routing, self_hosted_total: int) -> list[str]:
    """R3, both directions."""
    declared = str(routing).strip().lower() if routing is not None else "none"
    if declared == "none" and self_hosted_total > 0:
        return [f"agents.yaml: model_gateway_as_built.routing is 'none' but {self_hosted_total} "
                f"self-hosted model entr(ies) are registered. The EU AI Act inventory is generated "
                f"from this field, so it understates a control that exists (R3)."]
    if declared != "none" and self_hosted_total == 0:
        return [f"agents.yaml: model_gateway_as_built.routing declares '{declared}' but NO "
                f"self-hosted model entry is registered anywhere. That is a machine-claimed "
                f"residency control with nothing behind it — the exact shape issue #1280 had to "
                f"unwind from 34 OPA bundles (R3)."]
    return []


def audit() -> list[str]:
    findings, self_hosted_total = [], 0
    sources = sorted(REPO.glob("openbank-*/src/main/resources/application.yaml"))
    env_index = gitops_env_overrides()
    for src in sources:
        try:
            data = yaml.safe_load(src.read_text()) or {}
        except yaml.YAMLError as exc:
            findings.append(f"{src.relative_to(REPO)}: unparseable YAML ({exc.__class__.__name__}).")
            continue
        for where, models in walk_model_gateways(data, str(src.relative_to(REPO))):
            f, n = audit_models(models, where, env_index)
            findings.extend(f)
            self_hosted_total += n

    agents = yaml.safe_load(AGENTS_YAML.read_text()) or {}
    routing = (agents.get("model_gateway_as_built") or {}).get("routing")
    findings.extend(audit_routing_record(routing, self_hosted_total))
    return findings


def self_test() -> int:
    """Every case the gate MUST reject, and the near-misses it must NOT. The first case is the
    whole point of the gate: a self-hosted LABEL on a hosted US endpoint."""
    model_cases = [
        ("a self-hosted label on a hosted US endpoint is REJECTED",
         [{"id": "eu-tier", "sensitivity": "self-hosted",
           "endpoint": "https://api.groq.com/openai/v1"}], 1),
        ("an in-cluster Service endpoint passes",
         [{"id": "vllm", "sensitivity": "self-hosted", "endpoint": "http://vllm.copilot.svc:8000"}], 0),
        ("svc.cluster.local passes",
         [{"id": "v", "sensitivity": "self-hosted",
           "endpoint": "http://vllm.ai-platform.svc.cluster.local:8000"}], 0),
        ("a self-hosted entry with no endpoint at all is REJECTED",
         [{"id": "v", "sensitivity": "self-hosted"}], 1),
        ("an env placeholder whose DEFAULT is off-cluster is REJECTED",
         [{"id": "v", "sensitivity": "self-hosted",
           "endpoint": "${EU_MODEL_ENDPOINT:https://api.deepinfra.com/v1}"}], 1),
        ("an env placeholder with an in-cluster default passes",
         [{"id": "v", "sensitivity": "self-hosted",
           "endpoint": "${EU_MODEL_ENDPOINT:http://vllm.ai-platform.svc:8000}"}], 0),
        ("an env placeholder with NO default is REJECTED (unverifiable from the repo)",
         [{"id": "v", "sensitivity": "self-hosted", "endpoint": "${EU_MODEL_ENDPOINT}"}], 1),
        ("the underscore spelling ModelGateway also accepts is inspected, not skipped",
         [{"id": "v", "sensitivity": "self_hosted", "endpoint": "https://api.groq.com/v1"}], 1),
        ("mixed case and padding are normalised like parseSensitivity does",
         [{"id": "v", "sensitivity": "  Self-Hosted  ", "endpoint": "https://api.groq.com/v1"}], 1),
        ("a HOSTED entry on a US endpoint is not this gate's business",
         [{"id": "v", "sensitivity": "hosted", "endpoint": "https://api.groq.com/v1"}], 0),
        ("a typo'd sensitivity is HOSTED to the Kotlin too, so it is not inspected",
         [{"id": "v", "sensitivity": "self hosted", "endpoint": "https://api.groq.com/v1"}], 0),
        ("localhost (a sidecar) passes",
         [{"id": "v", "sensitivity": "self-hosted", "endpoint": "http://localhost:8000/v1"}], 0),
        ("a bare hostname that is not a URL is REJECTED rather than silently accepted",
         [{"id": "v", "sensitivity": "self-hosted", "endpoint": "vllm.ai-platform.svc:8000"}], 1),
        ("userinfo cannot smuggle an in-cluster name past the host check",
         [{"id": "v", "sensitivity": "self-hosted",
           "endpoint": "http://vllm.ai-platform.svc@api.groq.com/v1"}], 1),
        ("a non-dict entry does not crash the walk", ["nonsense"], 0),
        ("an empty list is clean", [], 0),
    ]
    # R4 — the deployed value wins over the default. Each case carries its own env index, so the
    # rule is falsified against a synthetic gitops override rather than against today's tree
    # (which registers no self-hosted tier at all, and would make every case vacuously green).
    deployed_cases = [
        ("an in-cluster default overridden by gitops to a US host is REJECTED",
         [{"id": "v", "sensitivity": "self-hosted", "endpoint": "${EU_EP:http://vllm.ai-platform.svc:8000}"}],
         {"EU_EP": [("gitops/x.yaml", {"name": "EU_EP", "value": "https://api.us.example.com/v1"})]}, 1),
        ("an in-cluster default overridden by gitops to another in-cluster host passes",
         [{"id": "v", "sensitivity": "self-hosted", "endpoint": "${EU_EP:http://vllm.ai-platform.svc:8000}"}],
         {"EU_EP": [("gitops/x.yaml", {"name": "EU_EP", "value": "http://vllm.copilot.svc:8000"})]}, 0),
        ("a gitops valueFrom override is REJECTED — the deployed target is not in this repo",
         [{"id": "v", "sensitivity": "self-hosted", "endpoint": "${EU_EP:http://vllm.ai-platform.svc:8000}"}],
         {"EU_EP": [("gitops/x.yaml", {"name": "EU_EP", "valueFrom": {"secretKeyRef": {"name": "s"}}})]}, 1),
        ("an override of a DIFFERENT variable is not attributed to this entry",
         [{"id": "v", "sensitivity": "self-hosted", "endpoint": "${EU_EP:http://vllm.ai-platform.svc:8000}"}],
         {"OTHER_EP": [("gitops/x.yaml", {"name": "OTHER_EP", "value": "https://api.us.example.com/v1"})]}, 0),
        ("a literal (non-placeholder) endpoint is unaffected by any gitops env",
         [{"id": "v", "sensitivity": "self-hosted", "endpoint": "http://vllm.ai-platform.svc:8000"}],
         {"EU_EP": [("gitops/x.yaml", {"name": "EU_EP", "value": "https://api.us.example.com/v1"})]}, 0),
        ("two overrides, one off-cluster, reports the off-cluster one",
         [{"id": "v", "sensitivity": "self-hosted", "endpoint": "${EU_EP:http://vllm.ai-platform.svc:8000}"}],
         {"EU_EP": [("gitops/a.yaml", {"name": "EU_EP", "value": "http://vllm.copilot.svc:8000"}),
                    ("gitops/b.yaml", {"name": "EU_EP", "value": "https://api.us.example.com/v1"})]}, 1),
        ("the real gitops walk finds the agent-service endpoint variable at all",
         None, None, None),
    ]

    routing_cases = [
        ("routing 'none' with no tier is today's honest state", "none", 0, 0),
        ("routing 'none' while a tier IS registered is record drift", "none", 1, 1),
        ("a routing claim with no tier behind it is REJECTED", "class-based", 0, 1),
        ("a routing claim backed by a tier passes", "class-based", 1, 0),
        ("a missing routing key is read as 'none'", None, 0, 0),
    ]
    walk_cases = [
        ("a nested model-gateway (copilot's shape) is found",
         {"copilot": {"model-gateway": {"models": [{"id": "a"}]}}}, 1),
        ("a top-level model-gateway (agent-service's shape) is found",
         {"model-gateway": {"models": [{"id": "a"}]}}, 1),
        ("a model-gateway with no models list yields nothing",
         {"model-gateway": {"default-model": "x"}}, 0),
    ]

    failed = 0
    for name, models, env_index, expected in deployed_cases:
        if models is None:
            # Not a rule case: a floor on the CORPUS itself. A walk that finds no env at all
            # would make every case above vacuous against the real tree, and print nothing.
            idx = gitops_env_overrides()
            got, expected_desc = len(idx), ">0 env names across gitops"
            ok = got > 0
            print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {expected_desc}, got {got})")
        else:
            got = len(audit_models(models, "test", env_index)[0])
            ok = got == expected
            print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {expected}, got {got})")
        failed += 0 if ok else 1

    for name, models, expected in model_cases:
        got = len(audit_models(models, "test")[0])
        ok = got == expected
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {expected}, got {got})")
    for name, routing, count, expected in routing_cases:
        got = len(audit_routing_record(routing, count))
        ok = got == expected
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {expected}, got {got})")
    for name, tree, expected in walk_cases:
        got = len(walk_model_gateways(tree, "test"))
        ok = got == expected
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {expected}, got {got})")

    total = len(model_cases) + len(routing_cases) + len(walk_cases) + len(deployed_cases)
    print(f"self-test: {total - failed}/{total} passed")
    return 1 if failed else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    enforce = "--enforce" in sys.argv
    findings = audit()
    if not findings:
        print("check-model-residency-claims: OK — no self-hosted model entry points off-cluster, "
              "and the agents.yaml routing record agrees with the registered tiers.")
        print("Scope: CONFIG-LEVEL only. This gate cannot observe where a request actually went.")
        return 0
    for f in findings:
        print(f"{'::error::' if enforce else '::warning::'}{f}")
    print(f"\n{len(findings)} problem(s). A self-hosted LABEL is the whole of the ADR-0175 D3 "
          f"residency control: ModelGateway.resolve() trusts it, the fail-closed test passes as "
          f"soon as any entry carries it, and gen-eu-ai-act.py renders it as a control.")
    return 1 if enforce else 0


if __name__ == "__main__":
    sys.exit(main())
