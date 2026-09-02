#!/usr/bin/env python3
"""Every model the LiteLLM gateway can route must have an LLM price-book entry, and the
price must agree with the gateway's own declared cost.

Issue #6045. Two independent failure modes, one gate:

1. MISSING PRICE. `openbank:llm_price_usd_per_token` in prometheus-rules-ai.yaml is joined
   `on (model, kind)` by the spend rules. A model served by the gateway with no entry is
   costed at ZERO -- the spend figure is silently too low and no error appears anywhere.
   AiSpendUnpriced does detect this at runtime, but only once the model carries traffic, and
   only if somebody actions the alert (it fired for openai/gpt-oss-120b and nobody did).
   This gate fails at PR time instead, before the first untracked token.

2. PRICE DISAGREEMENT. litellm-config.yaml declares input_cost_per_token/output_cost_per_token
   per route -- that is what LiteLLM meters and what the per-model max_budget bites on. The
   Prometheus price book is a SECOND copy of the same numbers, feeding the dashboards and the
   25 USD/day AiFleetDailySpendHigh ceiling. Nothing kept them in lockstep, and on 2026-08-21
   they disagreed on three figures, the worst by 2.6x on the model carrying 100% of live
   traffic. The config file's own comment already asks for lockstep; this makes it checkable.

THE SUBJECT SET IS DERIVED, NEVER HAND-KEPT. The models come from the `model_name` keys of
litellm-config.yaml's `model_list`. A gate whose scope is a hand-maintained list of the thing
it checks reads as PASSING when the list is short, never as UNCHECKED.

Exclusions carry a reason and fail STALE IN BOTH DIRECTIONS: an exclusion for a model that is
no longer served fails, and an exclusion for a model that now HAS a price fails too -- so a
temporary waiver cannot quietly become permanent.

Usage:
  check-llm-price-book.py --enforce
  check-llm-price-book.py --selftest
"""
import sys
import re
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
LITELLM = ROOT / "openbank-infra/gitops/components/ai-platform/litellm-config.yaml"
RULES = ROOT / "openbank-infra/gitops/components/observability/prometheus-rules-ai.yaml"

# Models deliberately absent from a price-book KIND. Keyed (model, kind) -> reason.
# Both directions are checked: a stale entry here fails the gate.
EXCLUSIONS = {
    ("BAAI/bge-m3", "completion"): (
        "Embeddings generate no completion tokens -- measured, the adapter emits kind=prompt "
        "and nothing else. A completion price here would price tokens that cannot exist."
    ),
}


def _dedent_block(text, start, indent):
    """Return the lines of a YAML block starting at `start` while indent > `indent`."""
    out = []
    for line in text[start:].splitlines()[1:]:
        if not line.strip() or line.lstrip().startswith("#"):
            out.append(line)
            continue
        if len(line) - len(line.lstrip()) <= indent:
            break
        out.append(line)
    return out


def parse_litellm(text):
    """model_name -> {'input': float|None, 'output': float|None}.

    Deliberately a line scanner rather than a YAML load: the config lives inside a ConfigMap
    `data:` string, so a yaml.safe_load gives one giant scalar, and the embedded document is
    what we need. Anchored on the two-space `- model_name:` list items.
    """
    models = {}
    current = None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        m = re.match(r"^\s*-\s*model_name:\s*(\S+)\s*$", line)
        if m:
            current = m.group(1)
            models[current] = {"input": None, "output": None}
            continue
        if current is None:
            continue
        m = re.match(r"^\s*input_cost_per_token:\s*([0-9.eE+-]+)\s*$", line)
        if m:
            models[current]["input"] = float(m.group(1))
            continue
        m = re.match(r"^\s*output_cost_per_token:\s*([0-9.eE+-]+)\s*$", line)
        if m:
            models[current]["output"] = float(m.group(1))
    return models


def parse_price_book(text):
    """(model, kind) -> (value, price_source) from openbank:llm_price_usd_per_token rules."""
    book = {}
    for m in re.finditer(
        r"-\s*record:\s*openbank:llm_price_usd_per_token\s*\n"
        r"\s*expr:\s*vector\(\s*([0-9.eE+-]+)\s*\)\s*\n"
        r"\s*labels:\s*\n"
        r"((?:\s*#.*\n|\s+\w+:\s*\S+\s*\n)+)",
        text,
    ):
        value = float(m.group(1))
        labels = dict(
            re.findall(r"^\s+(\w+):\s*(\S+)\s*$", m.group(2), re.M)
        )
        if "model" in labels and "kind" in labels:
            book[(labels["model"], labels["kind"])] = (
                value,
                labels.get("price_source", "(unset)"),
            )
    return book


# The gateway's declared cost is per (model, kind); map the price-book `kind` onto the
# litellm-config key that declares the same number.
KIND_TO_CONFIG_KEY = {"prompt": "input", "completion": "output"}


def check(litellm_text, rules_text):
    """Return a list of failure strings. Empty list == pass."""
    served = parse_litellm(litellm_text)
    book = parse_price_book(rules_text)
    failures = []

    if not served:
        return ["no model_name entries parsed from litellm-config.yaml -- the gate has no "
                "subjects, which is a broken probe, not a pass"]
    if not book:
        return ["no openbank:llm_price_usd_per_token rules parsed from prometheus-rules-ai.yaml "
                "-- the gate has no price book to check against"]

    for model in sorted(served):
        for kind, cfg_key in KIND_TO_CONFIG_KEY.items():
            declared = served[model][cfg_key]
            excluded = (model, kind) in EXCLUSIONS
            entry = book.get((model, kind))

            if entry is None:
                if excluded:
                    continue
                failures.append(
                    f"UNPRICED: model '{model}' is routable by the LiteLLM gateway but has no "
                    f"openbank:llm_price_usd_per_token entry for kind='{kind}'. Every {kind} "
                    f"token it burns is costed at $0, so openbank:llm_cost_usd_24h:total "
                    f"understates real spend and AiFleetDailySpendHigh cannot bite. Add the "
                    f"entry to the openbank.ai.price-book group in prometheus-rules-ai.yaml, "
                    f"or declare an EXCLUSIONS reason in this script."
                )
                continue

            if excluded:
                failures.append(
                    f"STALE EXCLUSION: ('{model}', '{kind}') is listed in EXCLUSIONS but the "
                    f"price book now HAS an entry for it. Remove the exclusion."
                )
                continue

            value, source = entry
            if value <= 0:
                failures.append(
                    f"ZERO PRICE: ('{model}', '{kind}') is priced at {value}, which is "
                    f"indistinguishable from no price at all -- it makes the join succeed while "
                    f"costing every token at nothing, which is worse than being unpriced "
                    f"because AiSpendUnpriced then cannot see it."
                )
                continue

            if declared is None:
                continue  # gateway declares no cost for this direction; nothing to compare.
            if abs(value - declared) > declared * 1e-6:
                failures.append(
                    f"PRICE DISAGREEMENT: ('{model}', '{kind}') is {value:g} in the Prometheus "
                    f"price book (price_source={source}) but {declared:g} in litellm-config.yaml "
                    f"({cfg_key}_cost_per_token). LiteLLM meters and enforces max_budget on its "
                    f"own number; the dashboards and AiFleetDailySpendHigh use the other. They "
                    f"must be the same figure from the same source."
                )

    for (model, kind), reason in sorted(EXCLUSIONS.items()):
        if model not in served:
            failures.append(
                f"STALE EXCLUSION: ('{model}', '{kind}') is listed in EXCLUSIONS but that model "
                f"is no longer routable by the gateway. Remove it. Reason on file: {reason}"
            )

    return failures


SELFTEST_LITELLM = """
    model_list:
      - model_name: alpha/model-one
        litellm_params:
          model: deepinfra/alpha/model-one
          input_cost_per_token: 0.000001
          output_cost_per_token: 0.000002
      - model_name: beta/model-two
        litellm_params:
          model: deepinfra/beta/model-two
          input_cost_per_token: 0.000003
          output_cost_per_token: 0.000004
"""

SELFTEST_RULES_GOOD = """
        - record: openbank:llm_price_usd_per_token
          expr: vector(0.000001)
          labels:
            model: alpha/model-one
            kind: prompt
            price_source: provider-rate-card
        - record: openbank:llm_price_usd_per_token
          expr: vector(0.000002)
          labels:
            model: alpha/model-one
            kind: completion
            price_source: provider-rate-card
        - record: openbank:llm_price_usd_per_token
          expr: vector(0.000003)
          labels:
            model: beta/model-two
            kind: prompt
            price_source: provider-rate-card
        - record: openbank:llm_price_usd_per_token
          expr: vector(0.000004)
          labels:
            model: beta/model-two
            kind: completion
            price_source: provider-rate-card
"""


def selftest():
    """Prove the gate by what it PREVENTS, on fixtures, not on the live files."""
    import copy  # noqa: F401  (kept for clarity of intent)
    global EXCLUSIONS
    saved = EXCLUSIONS
    EXCLUSIONS = {}
    ok = True

    def case(name, litellm, rules, must_fail, needle=None):
        nonlocal ok
        f = check(litellm, rules)
        failed = bool(f)
        if failed != must_fail:
            print(f"  SELFTEST FAIL [{name}]: expected "
                  f"{'failure' if must_fail else 'pass'}, got {f or 'pass'}")
            ok = False
            return
        if needle and not any(needle in x for x in f):
            print(f"  SELFTEST FAIL [{name}]: message did not name '{needle}': {f}")
            ok = False
            return
        print(f"  ok [{name}]")

    case("baseline passes", SELFTEST_LITELLM, SELFTEST_RULES_GOOD, False)

    # NEGATIVE CONTROL 1: remove one price-book entry.
    removed = SELFTEST_RULES_GOOD.replace(
        """        - record: openbank:llm_price_usd_per_token
          expr: vector(0.000004)
          labels:
            model: beta/model-two
            kind: completion
            price_source: provider-rate-card
""", "")
    case("missing entry fails, naming the model", SELFTEST_LITELLM, removed, True,
         "beta/model-two")

    # NEGATIVE CONTROL 2: a price that disagrees with the gateway config.
    wrong = SELFTEST_RULES_GOOD.replace("vector(0.000003)", "vector(0.000009)")
    case("disagreeing price fails", SELFTEST_LITELLM, wrong, True, "PRICE DISAGREEMENT")

    # NEGATIVE CONTROL 3: a zero price (join succeeds, cost is nothing).
    zeroed = SELFTEST_RULES_GOOD.replace("vector(0.000003)", "vector(0.0)")
    case("zero price fails", SELFTEST_LITELLM, zeroed, True, "ZERO PRICE")

    # NEGATIVE CONTROL 4: a new gateway model with no price at all.
    grown = SELFTEST_LITELLM + """      - model_name: gamma/model-three
        litellm_params:
          model: deepinfra/gamma/model-three
          input_cost_per_token: 0.000005
          output_cost_per_token: 0.000006
"""
    case("new gateway model fails", grown, SELFTEST_RULES_GOOD, True, "gamma/model-three")

    # NEGATIVE CONTROL 5: stale exclusion, both directions.
    EXCLUSIONS = {("beta/model-two", "completion"): "test"}
    case("exclusion covering a priced entry fails", SELFTEST_LITELLM, SELFTEST_RULES_GOOD,
         True, "STALE EXCLUSION")
    EXCLUSIONS = {("ghost/model", "prompt"): "test"}
    case("exclusion for an unserved model fails", SELFTEST_LITELLM, SELFTEST_RULES_GOOD,
         True, "STALE EXCLUSION")

    # And the exclusion working as intended.
    EXCLUSIONS = {("beta/model-two", "completion"): "test"}
    case("honoured exclusion passes", SELFTEST_LITELLM, removed, False)

    # BROKEN-PROBE CONTROL: empty inputs must FAIL, never pass vacuously.
    EXCLUSIONS = {}
    case("empty config fails", "", SELFTEST_RULES_GOOD, True, "no subjects")
    case("empty rules fails", SELFTEST_LITELLM, "", True, "no price book")

    EXCLUSIONS = saved
    return ok


def main():
    if "--selftest" in sys.argv:
        print("check-llm-price-book self-test")
        sys.exit(0 if selftest() else 1)

    litellm_text = LITELLM.read_text()
    rules_text = RULES.read_text()
    served = parse_litellm(litellm_text)
    book = parse_price_book(rules_text)
    failures = check(litellm_text, rules_text)

    print(f"LiteLLM gateway routes {len(served)} model(s); price book carries "
          f"{len(book)} (model, kind) entr(ies).")
    for model in sorted(served):
        print(f"  - {model}")
    # Printed unconditionally, on the failure path too: a gate that found its corpus and then
    # failed on it must not also be reported as having lost its corpus.
    gatelib.subjects(len(served), "model_name routes in litellm-config.yaml")

    if failures:
        print(f"\nFAIL: {len(failures)} finding(s)\n")
        for f in failures:
            print(f"  * {f}\n")
        sys.exit(1)
    print("\nOK: every gateway-routable model is priced, and every price agrees with "
          "litellm-config.yaml.")
    sys.exit(0)


if __name__ == "__main__":
    main()
