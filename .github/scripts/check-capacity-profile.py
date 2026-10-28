#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Check internal consistency of ADR-0356's workload profile; this is not a load gate."""

from __future__ import annotations

import argparse
import math
import pathlib
import shutil
import sys
import tempfile

try:
    import yaml
except ImportError:  # pragma: no cover
    print("::error::check-capacity-profile: PyYAML is required", file=sys.stderr)
    raise SystemExit(1) from None

PROFILE = "perf/profiles/cz-current-account-5m-v1.yaml"
SCENARIOS = "perf/scenarios.yaml"
PROFILE_KEYS = {
    "schema_version", "profile_id", "decision", "enforcement", "adr", "as_of", "description",
    "qualification", "population", "business_volume", "reads", "history", "scenarios",
    "latency_targets", "outcomes", "financial_oracle", "queues", "fault_scenarios",
    "fault_campaign_contract", "fault_evidence_requirements", "evidence_contract", "commercial_targets",
}


class UniqueSafeLoader(yaml.SafeLoader):
    """Safe YAML loader that rejects duplicate mapping keys before they can be discarded."""

    def construct_mapping(self, node, deep=False):
        self.flatten_mapping(node)
        seen = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=deep)
            try:
                duplicate = key in seen
                seen.add(key)
            except TypeError as exc:
                raise yaml.constructor.ConstructorError(
                    "while constructing a mapping", node.start_mark,
                    "unhashable mapping key", key_node.start_mark,
                ) from exc
            if duplicate:
                raise yaml.constructor.ConstructorError(
                    "while constructing a mapping", node.start_mark,
                    f"duplicate key {key!r}", key_node.start_mark,
                )
        return super().construct_mapping(node, deep=deep)


def load_yaml(path: pathlib.Path):
    return yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueSafeLoader)


def close(actual, expected) -> bool:
    return isinstance(actual, (int, float)) and not isinstance(actual, bool) and math.isclose(
        actual, expected, rel_tol=1e-9, abs_tol=1e-6
    )


def numeric(value, *, positive=False, integer=False) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        return False
    return (not positive or value > 0) and (not integer or isinstance(value, int))


def validate(root: pathlib.Path, doc) -> list[str]:
    errors: list[str] = []

    def need(condition: bool, message: str) -> None:
        if not condition:
            errors.append(message)

    if not isinstance(doc, dict):
        return ["profile root must be a mapping"]
    unknown, missing = set(doc) - PROFILE_KEYS, PROFILE_KEYS - set(doc)
    need(not unknown, f"unknown profile keys: {sorted(unknown)}"); need(not missing, f"missing profile keys: {sorted(missing)}")
    if unknown or missing:
        return errors
    need(doc["schema_version"] == 1 and not isinstance(doc["schema_version"], bool), "schema_version must be 1")
    need(doc["profile_id"] == "cz-current-account-5m-v1", "profile_id must be cz-current-account-5m-v1")
    need(doc["adr"] == "ADR-0356", "adr must be ADR-0356")
    need(doc["decision"] in ("proposed", "accepted"), "decision must be proposed or accepted")
    need(doc["enforcement"] in ("none", "advisory", "enforced"), "enforcement must be none, advisory, or enforced")

    q = doc["qualification"]
    need(isinstance(q, dict), "qualification must be a mapping")
    if isinstance(q, dict):
        pending = q.get("pending_decisions")
        need(q.get("pending_decisions_block_qualification") is True, "qualification.pending_decisions_block_qualification must be true")
        need(isinstance(pending, list) and all(isinstance(x, str) and x.strip() for x in pending), "qualification.pending_decisions must be a non-empty list of reasons")
        if doc["decision"] == "proposed":
            need(doc["enforcement"] == "none", "a proposed profile must have enforcement: none"); need(q.get("measured_results_exist") is False, "a proposed profile cannot claim measured results")
            need(bool(pending), "a proposed profile must retain blocking pending decisions")
        if doc["enforcement"] == "enforced":
            need(doc["decision"] == "accepted", "an enforced profile must be accepted"); need(q.get("measured_results_exist") is True, "an enforced profile must have measured results")
            need(pending == [], "an enforced profile cannot have blocking pending decisions")
        need(isinstance(q.get("required_owner_approvals"), list) and bool(q["required_owner_approvals"]), "qualification.required_owner_approvals must be a non-empty list")

    population, volume = doc["population"], doc["business_volume"]
    reads, history, scenarios = doc["reads"], doc["history"], doc["scenarios"]
    for name, value in (("population", population), ("business_volume", volume), ("reads", reads), ("history", history), ("scenarios", scenarios)):
        need(isinstance(value, dict), f"{name} must be a mapping")
    if not all(isinstance(x, dict) for x in (population, volume, reads, history, scenarios)):
        return errors

    rates = volume.get("rates")
    mix, counts = volume.get("daily_mix"), volume.get("mix_counts_per_day")
    read_mix, read_rates = reads.get("mix"), reads.get("rates_per_second")
    normal, burst, soak = (scenarios.get(k) for k in ("normal", "burst", "soak"))
    for label, value in (("business_volume.rates", rates), ("business_volume.daily_mix", mix),
                         ("business_volume.mix_counts_per_day", counts), ("reads.mix", read_mix),
                         ("reads.rates_per_second", read_rates), ("scenarios.normal", normal),
                         ("scenarios.burst", burst), ("scenarios.soak", soak)):
        need(isinstance(value, dict), f"{label} must be a mapping")
    if not all(isinstance(x, dict) for x in (rates, mix, counts, read_mix, read_rates, normal, burst, soak)):
        return errors
    positive_ints = (
        ("population.clients", population.get("clients")), ("population.accounts", population.get("accounts")),
        ("population.accounts_per_client", population.get("accounts_per_client")),
        ("business_volume.instructions_per_day", volume.get("instructions_per_day")),
        ("business_volume.instructions_per_client_per_day", volume.get("instructions_per_client_per_day")),
        ("business_volume.rates.seconds_per_day", rates.get("seconds_per_day")),
        ("reads.target_reads_per_second", reads.get("target_reads_per_second")),
        ("history.model_year_days", history.get("model_year_days")),
        ("history.minimum_model_years", history.get("minimum_model_years")),
        ("history.instructions_per_day", history.get("instructions_per_day")),
        ("history.instructions_total", history.get("instructions_total")),
        ("scenarios.burst.duration_seconds", burst.get("duration_seconds")),
        ("scenarios.burst.valid_new_instructions_per_second", burst.get("valid_new_instructions_per_second")),
        ("scenarios.burst.expected_instructions", burst.get("expected_instructions")),
        ("scenarios.soak.duration_seconds", soak.get("duration_seconds")),
        ("scenarios.soak.duration_hours", soak.get("duration_hours")),
        ("scenarios.soak.expected_instructions", soak.get("expected_instructions")),
        ("scenarios.soak.peak_windows_per_day", soak.get("peak_windows_per_day")),
        ("scenarios.soak.peak_window_duration_seconds", soak.get("peak_window_duration_seconds")),
        ("scenarios.soak.peak_instructions_per_second", soak.get("peak_instructions_per_second")),
        ("scenarios.soak.remaining_daily_instructions", soak.get("remaining_daily_instructions")),
        ("scenarios.soak.remaining_daily_duration_seconds", soak.get("remaining_daily_duration_seconds")),
    )
    for label, value in positive_ints:
        need(numeric(value, positive=True, integer=True), f"{label} must be a positive integer")
    positive_numbers = (
        ("business_volume.rates.average_instructions_per_second", rates.get("average_instructions_per_second")),
        ("business_volume.rates.planning_peak_multiplier", rates.get("planning_peak_multiplier")),
        ("business_volume.rates.planning_peak_instructions_per_second", rates.get("planning_peak_instructions_per_second")),
        ("business_volume.rates.soak_peak_instructions_per_second", rates.get("soak_peak_instructions_per_second")),
    )
    for label, value in positive_numbers:
        need(numeric(value, positive=True), f"{label} must be a positive finite number")
    remaining_rate = soak.get("remaining_instructions_per_second")
    need(numeric(remaining_rate) and remaining_rate >= 0,
         "scenarios.soak.remaining_instructions_per_second must be finite and non-negative")
    for label, values in (("business_volume.daily_mix", mix), ("reads.mix", read_mix)):
        for key, value in values.items():
            need(numeric(value) and value >= 0, f"{label}.{key} must be a finite non-negative number")
    for label, values in (("business_volume.mix_counts_per_day", counts), ("reads.rates_per_second", read_rates)):
        for key, value in values.items():
            need(numeric(value, integer=True) and value >= 0, f"{label}.{key} must be a non-negative integer")
    if errors:
        return errors

    need(population.get("clients", 0) * population.get("accounts_per_client", 0) == population.get("accounts"), "population accounts must equal clients × accounts_per_client")
    daily = volume.get("instructions_per_day", 0)
    need(daily == population.get("clients", 0) * volume.get("instructions_per_client_per_day", 0),
         "business volume must equal clients × instructions_per_client_per_day")
    mix = volume.get("daily_mix", {})
    counts = volume.get("mix_counts_per_day", {})
    need(isinstance(mix, dict) and bool(mix), "business_volume.daily_mix must be a non-empty mapping")
    if isinstance(mix, dict) and mix:
        need(close(sum(mix.values()), 1), "business_volume.daily_mix shares must sum to 1")
        need(set(mix) == set(counts), "daily_mix and mix_counts_per_day must have identical keys")
        for key, share in mix.items():
            if isinstance(counts, dict):
                need(close(counts.get(key), daily * share), f"mix_counts_per_day.{key} must equal daily volume × share")
        need(isinstance(counts, dict) and sum(counts.values()) == daily, "mix_counts_per_day must sum to instructions_per_day")
    seconds = rates.get("seconds_per_day", 0)
    need(seconds > 0 and close(rates.get("average_instructions_per_second"), daily / seconds), "average_instructions_per_second must equal instructions_per_day / seconds_per_day")
    peak = rates.get("average_instructions_per_second", 0) * rates.get("planning_peak_multiplier", 0)
    need(close(rates.get("planning_peak_instructions_per_second"), peak), "planning_peak_instructions_per_second must equal average × multiplier")
    need(rates.get("soak_peak_instructions_per_second") == round(peak),
         "soak_peak_instructions_per_second must be the rounded planning peak")

    target_reads = reads.get("target_reads_per_second", 0)
    need(isinstance(read_mix, dict) and bool(read_mix), "reads.mix must be a non-empty mapping")
    if isinstance(read_mix, dict) and read_mix:
        need(close(sum(read_mix.values()), 1), "reads.mix shares must sum to 1")
        need(set(read_mix) == set(read_rates), "reads.mix and rates_per_second must have identical keys")
        for key, share in read_mix.items():
            need(close(read_rates.get(key), target_reads * share), f"reads.rates_per_second.{key} must equal target × share")
        need(sum(read_rates.values()) == target_reads, "read rates must sum to target_reads_per_second")

    need(history.get("instructions_per_day") == daily, "history.instructions_per_day must equal business volume")
    need(history.get("model_year_days") >= history.get("minimum_model_years") * 365,
         "history.model_year_days must satisfy minimum_model_years")
    need(history.get("instructions_total") == history.get("instructions_per_day", 0) * history.get("model_year_days", 0), "history.instructions_total must equal daily instructions × model_year_days")
    need(normal.get("instructions_per_day") == daily, "normal.instructions_per_day must equal business volume")
    need(normal.get("reads_per_second") == target_reads, "normal.reads_per_second must equal target reads")
    need(normal.get("required_for_qualification") is True, "normal scenario must be required for qualification")
    need(burst.get("required_for_qualification") is True and burst.get("pass_fail") == "mandatory",
         "burst scenario must be required and mandatory pass/fail")
    need(burst.get("expected_instructions") == burst.get("duration_seconds", 0) * burst.get("valid_new_instructions_per_second", 0), "burst.expected_instructions must equal duration × valid instruction rate")
    need(burst.get("intentionally_exceeds_ordinary_daily_model") is True and burst.get("expected_instructions", 0) > daily,
         "burst must explicitly exceed the ordinary daily volume")
    need(burst.get("concurrent_reads_per_second") == target_reads, "burst reads must equal target read rate")
    soak_seconds = soak.get("duration_seconds", 0)
    need(soak.get("duration_hours", 0) * 3600 == soak_seconds, "soak duration_hours must match duration_seconds")
    need(soak_seconds % seconds == 0, "soak duration must contain whole model days")
    need(soak.get("expected_instructions") == daily * (soak_seconds // seconds), "soak expected_instructions must equal daily volume × duration in days")
    peak_day = soak.get("peak_windows_per_day", 0) * soak.get("peak_window_duration_seconds", 0) * soak.get("peak_instructions_per_second", 0)
    need(soak.get("peak_instructions_per_second") == rates.get("soak_peak_instructions_per_second"),
         "soak peak rate must equal the derived soak peak")
    need(soak.get("remaining_daily_instructions") == daily - peak_day, "soak remaining_daily_instructions must equal daily volume minus peak work")
    remaining_seconds = seconds - soak.get("peak_windows_per_day", 0) * soak.get("peak_window_duration_seconds", 0)
    need(soak.get("remaining_daily_duration_seconds") == remaining_seconds, "soak remaining_daily_duration_seconds must subtract the peak windows")
    if remaining_seconds > 0:
        need(close(soak.get("remaining_instructions_per_second"), (daily - peak_day) / remaining_seconds), "soak remaining rate must equal remaining instructions / remaining seconds")

    links = scenarios.get("existing_scenario_links", [])
    need(isinstance(links, list), "scenarios.existing_scenario_links must be a list")
    if not isinstance(links, list):
        return errors
    try:
        catalog = load_yaml(root / SCENARIOS)
    except (OSError, yaml.YAMLError) as exc:
        return errors + [f"cannot load {SCENARIOS}: {exc}"]
    entries = catalog.get("scenarios") if isinstance(catalog, dict) else None
    need(isinstance(entries, list), f"{SCENARIOS}: scenarios must be a list")
    if not isinstance(entries, list):
        return errors
    by_id = {item.get("id"): item for item in entries if isinstance(item, dict) and isinstance(item.get("id"), str)}
    link_ids = set()
    for link in links:
        if not isinstance(link, dict):
            errors.append("existing_scenario_links entries must be mappings")
            continue
        sid = link.get("id")
        need(isinstance(sid, str) and sid not in link_ids, f"duplicate or invalid linked scenario id: {sid!r}")
        link_ids.add(sid)
        scenario = by_id.get(sid)
        need(scenario is not None, f"linked scenario {sid!r} is absent from {SCENARIOS}")
        if scenario is not None:
            need(link.get("definition") == scenario.get("definition"), f"linked scenario {sid!r} definition disagrees with catalog")
        need(link.get("counts_as_profile_coverage") is False, f"existing scenario {sid!r} must not be represented as profile coverage")
        definition = link.get("definition")
        if isinstance(definition, str):
            candidate = (root / definition).resolve()
            need(candidate.is_relative_to(root.resolve()), f"scenario definition escapes root: {definition}")
            if candidate.is_relative_to(root.resolve()):
                need(candidate.is_file(), f"scenario definition file is missing: {definition}")
    qualification_ids = scenarios.get("qualification_scenario_ids", [])
    need(isinstance(qualification_ids, list) and all(isinstance(x, str) for x in qualification_ids),
         "qualification_scenario_ids must be a list of scenario ids")
    if isinstance(qualification_ids, list):
        strings = [x for x in qualification_ids if isinstance(x, str)]
        need(len(strings) == len(set(strings)), "qualification_scenario_ids must be unique")
        for sid in strings:
            need(sid in by_id, f"qualification scenario {sid!r} is absent from {SCENARIOS}")
            need(sid not in link_ids, f"existing baseline scenario {sid!r} cannot count as qualification coverage")

    faults = doc["fault_scenarios"]
    campaign = doc["fault_campaign_contract"]
    need(isinstance(faults, list) and bool(faults), "fault_scenarios must be a non-empty list")
    need(isinstance(campaign, dict) and campaign.get("blocks_qualification_until_frozen") is True,
         "fault_campaign_contract must block qualification until approved bounds are frozen")
    if isinstance(faults, list):
        for fault in faults:
            need(isinstance(fault, dict), "fault_scenarios entries must be mappings")
            if isinstance(fault, dict) and fault.get("required") is True:
                for key in ("severity", "injection_duration_seconds", "recovery_boundary"):
                    need(key in fault and fault[key] not in (None, ""), f"required fault {fault.get('case')} needs {key}")

    return errors


def self_test() -> int:
    failures = 0
    with tempfile.TemporaryDirectory(prefix="capacity-profile-") as td:
        root = pathlib.Path(td)
        repo = pathlib.Path(__file__).resolve().parents[2]
        (root / SCENARIOS).parent.mkdir(parents=True, exist_ok=True)
        (root / PROFILE).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(repo / SCENARIOS, root / SCENARIOS)
        shutil.copyfile(repo / PROFILE, root / PROFILE)
        good = load_yaml(root / PROFILE)
        for link in good["scenarios"]["existing_scenario_links"]:
            target = root / link["definition"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(repo / link["definition"], target)
        cases = [("valid fixture", good, None)]
        bad_sum = yaml.safe_load(yaml.safe_dump(good)); bad_sum["business_volume"]["daily_mix"]["b"] = .4
        cases.append(("wrong mix sum", bad_sum, "shares must sum"))
        missing = yaml.safe_load(yaml.safe_dump(good)); missing["scenarios"]["existing_scenario_links"][0]["id"] = "missing"
        cases.append(("missing reference", missing, "absent from perf/scenarios.yaml"))
        inconsistent = yaml.safe_load(yaml.safe_dump(good)); inconsistent["business_volume"]["instructions_per_day"] = 21
        cases.append(("inconsistent model", inconsistent, "clients × instructions_per_client_per_day"))
        malformed_rate = yaml.safe_load(yaml.safe_dump(good)); malformed_rate["business_volume"]["rates"] = []
        cases.append(("malformed nested rates", malformed_rate, "business_volume.rates must be a mapping"))
        malformed_soak = yaml.safe_load(yaml.safe_dump(good)); malformed_soak["scenarios"]["soak"] = "invalid"
        cases.append(("malformed soak", malformed_soak, "scenarios.soak must be a mapping"))
        numeric_string = yaml.safe_load(yaml.safe_dump(good)); numeric_string["reads"]["target_reads_per_second"] = "300"
        cases.append(("numeric string", numeric_string, "reads.target_reads_per_second must be a positive integer"))
        boolean_number = yaml.safe_load(yaml.safe_dump(good)); boolean_number["population"]["clients"] = True
        cases.append(("boolean number", boolean_number, "population.clients must be a positive integer"))
        nonfinite = yaml.safe_load(yaml.safe_dump(good)); nonfinite["business_volume"]["rates"]["average_instructions_per_second"] = float("nan")
        cases.append(("non-finite rate", nonfinite, "average_instructions_per_second must be a positive finite number"))
        unknown = yaml.safe_load(yaml.safe_dump(good)); unknown["invented"] = True
        cases.append(("unknown root shape", unknown, "unknown profile keys"))
        unblocked = yaml.safe_load(yaml.safe_dump(good))
        unblocked["qualification"]["pending_decisions_block_qualification"] = False
        cases.append(("pending decisions do not block", unblocked, "must be true"))
        for name, doc, expected in cases:
            found = validate(root, doc)
            okay = not found if expected is None else any(expected in item for item in found)
            if not okay:
                print(f"FAIL {name}: {found}", file=sys.stderr); failures += 1
        duplicate = root / "duplicate.yaml"
        duplicate.write_text("schema_version: 1\nschema_version: 1\n", encoding="utf-8")
        try:
            load_yaml(duplicate)
            print("FAIL duplicate key was accepted", file=sys.stderr); failures += 1
        except yaml.YAMLError:
            pass
    if failures:
        print(f"check-capacity-profile self-test: {failures} failure(s)", file=sys.stderr)
        return 1
    print("check-capacity-profile self-test: pass")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="repository root")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root).resolve()
    path = root / PROFILE
    try:
        doc = load_yaml(path)
        errors = validate(root, doc)
    except FileNotFoundError:
        errors = [f"required profile is missing: {PROFILE}"]
    except yaml.YAMLError as exc:
        errors = [f"invalid or duplicate-key YAML in {PROFILE}: {exc}"]
    except (OSError, TypeError, ValueError) as exc:
        errors = [f"cannot validate {PROFILE}: {exc}"]
    if errors:
        for error in errors:
            print(f"::error::check-capacity-profile: {error}", file=sys.stderr)
        return 2
    print("SUBJECTS=1")
    print(f"check-capacity-profile: {PROFILE} is internally consistent; no capacity qualification is asserted")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
