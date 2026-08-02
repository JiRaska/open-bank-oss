#!/usr/bin/env python3
"""Every model routed through the LiteLLM gateway must declare its own per-token price.

WHY THIS EXISTS. LiteLLM costs a request using a built-in model-cost table shipped inside the
pinned dependency. When that table has no entry for a model, the request is costed at **$0** --
it does not error, does not warn, and returns a perfectly normal 200. Every dollar-denominated
control downstream then measures a meter that never moves:

  - the per-key virtual-key budgets (the only per-CALLER ceiling),
  - `max_budget` on the model itself, the one that lives in git,
  - and the fleet-wide AiFleetDailySpendHigh alert.

All three read as healthy while enforcing nothing. Measured on 2026-08-02: `deepseek-ai/
DeepSeek-V3.2` -- the ops model behind devops-agent, the liveness sentinel and copilot -- had
`in=0 out=0` in `/model/info`, and every `/spend/logs` row read `spend=0.0` after a real 200
response of 13 tokens.

A model whose price the table DOES know is not safe either: that is a value inside a dependency,
so a version bump can drop or rename the entry and silently restore the $0 behaviour. Declaring
the price in git makes it auditable and makes this gate possible.

Run standalone:  .github/scripts/check-litellm-model-costs.py [--enforce]
Self-test:       .github/scripts/check-litellm-model-costs.py --self-test
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
CONFIG = REPO / "openbank-infra" / "gitops" / "components" / "ai-platform" / "litellm-config.yaml"
REQUIRED = ("input_cost_per_token", "output_cost_per_token")


def offenders_in(model_list: list, where: str) -> list[str]:
    """One message per model that cannot be costed. Both a MISSING key and an explicit zero are
    violations: a zero price is indistinguishable at runtime from an absent one, so allowing it
    would let the exact failure this gate exists for be re-introduced deliberately."""
    out = []
    for entry in model_list or []:
        if not isinstance(entry, dict):
            continue
        name = entry.get("model_name", "<unnamed>")
        params = entry.get("litellm_params") or {}
        for key in REQUIRED:
            value = params.get(key)
            if value is None:
                out.append(
                    f"{where}: model '{name}' does not declare {key}. Without it LiteLLM falls "
                    f"back to its built-in cost table, which costs an unknown model at $0 and "
                    f"makes every budget and spend alert downstream measure nothing."
                )
            elif not isinstance(value, (int, float)) or value <= 0:
                out.append(
                    f"{where}: model '{name}' declares {key}={value!r}, which cannot cost a "
                    f"request. A zero or non-numeric price is indistinguishable from having no "
                    f"price at all."
                )
    return out


def audit() -> list[str]:
    if not CONFIG.exists():
        return [f"{CONFIG.relative_to(REPO)}: not found — the gateway config moved, so this "
                f"gate is checking nothing. Repoint it or remove it deliberately."]
    doc = yaml.safe_load(CONFIG.read_text())
    # The file is a ConfigMap; the proxy config is a string under data['config.yaml'].
    raw = ((doc or {}).get("data") or {}).get("config.yaml")
    if raw is None:
        return [f"{CONFIG.relative_to(REPO)}: no data['config.yaml'] key — the ConfigMap shape "
                f"changed and the model list can no longer be read."]
    cfg = yaml.safe_load(raw) or {}
    models = cfg.get("model_list")
    if not models:
        return [f"{CONFIG.relative_to(REPO)}: model_list is empty or absent. An empty list would "
                f"otherwise make this gate pass while checking nothing."]
    return offenders_in(models, CONFIG.relative_to(REPO).as_posix())


def self_test() -> int:
    priced = {"model_name": "m", "litellm_params": {"input_cost_per_token": 2.6e-07,
                                                    "output_cost_per_token": 3.8e-07}}
    cases = [
        ("a fully priced model passes", [priced], 0),
        ("a model with no cost keys is flagged twice (input and output)",
         [{"model_name": "m", "litellm_params": {"max_budget": 20}}], 2),
        ("a model missing only the output price is flagged once",
         [{"model_name": "m", "litellm_params": {"input_cost_per_token": 1e-07}}], 1),
        ("an explicit zero is a violation, not a price",
         [{"model_name": "m", "litellm_params": {"input_cost_per_token": 0,
                                                 "output_cost_per_token": 0}}], 2),
        ("a string price cannot cost a request",
         [{"model_name": "m", "litellm_params": {"input_cost_per_token": "0.1",
                                                 "output_cost_per_token": 1e-07}}], 1),
        ("a negative price is a violation",
         [{"model_name": "m", "litellm_params": {"input_cost_per_token": -1e-07,
                                                 "output_cost_per_token": 1e-07}}], 1),
        ("no litellm_params at all is flagged", [{"model_name": "m"}], 2),
        ("two priced models pass", [priced, priced], 0),
        ("a non-dict entry does not crash the walk", ["nonsense"], 0),
    ]
    failed = 0
    for name, models, expected in cases:
        got = len(offenders_in(models, "test"))
        ok = got == expected
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {expected}, got {got})")
    print(f"self-test: {len(cases) - failed}/{len(cases)} passed")
    return 1 if failed else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    enforce = "--enforce" in sys.argv
    findings = audit()
    if not findings:
        print("check-litellm-model-costs: OK — every gateway model declares a usable price.")
        return 0
    for f in findings:
        print(f"{'::error::' if enforce else '::warning::'}{f}")
    print(f"\n{len(findings)} problem(s). Until each model has a price, the per-key budgets, the "
          f"per-model max_budget and AiFleetDailySpendHigh all measure a meter that never moves.")
    return 1 if enforce else 0


if __name__ == "__main__":
    sys.exit(main())
