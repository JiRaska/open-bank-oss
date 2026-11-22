# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Tests for the Langfuse -> Prometheus bridge (ADR-0112 P1).

The script is loaded straight out of its ConfigMap, so the code under test IS the code that
ships — there is no second copy to drift. The metric-name test pins the exact series admin-ui's
/api/finops/ai-costs route queries, read from the route file itself.
"""
from __future__ import annotations

import datetime as dt
import re
import types
import unittest
from pathlib import Path

import yaml

_ROOT = Path(__file__).resolve().parents[2]
_MANIFEST = _ROOT / "openbank-infra/gitops/components/ai-platform/langfuse-exporter.yaml"
_ROUTE = _ROOT / "openbank-admin-ui/src/app/api/finops/ai-costs/route.ts"

_cm = next(d for d in yaml.safe_load_all(_MANIFEST.read_text()) if d and d["kind"] == "ConfigMap")
ex = types.ModuleType("langfuse_exporter")
exec(compile(_cm["data"]["langfuse_exporter.py"], "langfuse_exporter.py", "exec"), ex.__dict__)

NOW = dt.datetime(2026, 10, 30, 12, 0, tzinfo=dt.timezone.utc)


def payload(*rows):
    return {"data": [
        {"providedModelName": m, "userId": a, "time_dimension": day, "sum_totalTokens": t, "sum_totalCost": c}
        for (a, m, day, t, c) in rows
    ]}


def per_day(*rows):
    """A fetcher that answers each per-day query with only that day's rows, as Langfuse does.

    The exporter asks once per day (build_queries) and takes the day from the query, not from
    the row, so a stub that returned every row for every query would count each day twice.
    """
    def fetch(q):
        day = q["fromTimestamp"][:10]
        return payload(*[r for r in rows if r[2] == day])
    return fetch


def sample(text, name, **labels):
    want = ",".join(f'{k}="{v}"' for k, v in labels.items())
    pattern = rf"^{name}{{{re.escape(want)}}} (\S+)$" if labels else rf"^{name} (\S+)$"
    match = re.search(pattern, text, re.M)
    return None if match is None else float(match.group(1))


class ExporterTest(unittest.TestCase):
    def test_metric_names_match_the_route_queries(self):
        route = _ROUTE.read_text()
        queried = set(re.findall(r"\blangfuse_[a-z_]+", route))
        self.assertEqual(queried, {"langfuse_up", "langfuse_agent_tokens_total", "langfuse_model_cost_per_token"})
        state = ex.State()
        ex.poll_once(state, per_day(("copilot", "m1", "2026-10-30", 10, 0.1)), "userId", NOW)
        exported = set(re.findall(r"^(langfuse_[a-z_]+)", state.render(), re.M))
        self.assertTrue(queried <= exported, queried - exported)
        # The route joins on(model) and filters agent_id: both labels must exist with these names.
        self.assertIn('langfuse_agent_tokens_total{agent_id="copilot",model="m1"}', state.render())
        self.assertIn('langfuse_model_cost_per_token{model="m1"}', state.render())

    def test_first_poll_seeds_then_only_growth_is_counted(self):
        state = ex.State()
        ex.poll_once(state, per_day(("copilot", "m1", "2026-10-30", 1000, 2.0)), "userId", NOW)
        self.assertEqual(sample(state.render(), "langfuse_agent_tokens_total", agent_id="copilot", model="m1"), 0.0)
        ex.poll_once(state, per_day(("copilot", "m1", "2026-10-30", 1500, 3.0)), "userId", NOW)
        out = state.render()
        self.assertEqual(sample(out, "langfuse_agent_tokens_total", agent_id="copilot", model="m1"), 500.0)
        self.assertEqual(sample(out, "langfuse_agent_cost_usd_total", agent_id="copilot", model="m1"), 1.0)
        self.assertAlmostEqual(sample(out, "langfuse_model_cost_per_token", model="m1"), 3.0 / 1500)

    def test_a_day_ageing_out_never_decreases_the_counter(self):
        state = ex.State()
        ex.poll_once(state, per_day(("a", "m", "2026-10-29", 100, 0), ("a", "m", "2026-10-30", 10, 0)), "userId", NOW)
        ex.poll_once(state, per_day(("a", "m", "2026-10-30", 30, 0)), "userId", NOW)
        ex.poll_once(state, per_day(("a", "m", "2026-10-30", 25, 0)), "userId", NOW)  # late shrink
        self.assertEqual(sample(state.render(), "langfuse_agent_tokens_total", agent_id="a", model="m"), 20.0)

    def test_new_day_after_seed_counts_from_zero(self):
        state = ex.State()
        ex.poll_once(state, per_day(("a", "m", "2026-10-30", 100, 0)), "userId", NOW)
        ex.poll_once(state, per_day(("a", "m", "2026-10-30", 100, 0), ("a", "m", "2026-10-31", 7, 0)), "userId",
                     NOW + dt.timedelta(days=1))
        self.assertEqual(sample(state.render(), "langfuse_agent_tokens_total", agent_id="a", model="m"), 7.0)

    def test_missing_agent_is_unattributed_not_dropped(self):
        state = ex.State()
        ex.poll_once(state, per_day((None, "m", "2026-10-30", 5, 0)), "userId", NOW)
        self.assertIn('agent_id="unattributed"', state.render())

    def test_negative_langfuse_unreachable_sets_up_zero_and_keeps_counters(self):
        state = ex.State()
        ex.poll_once(state, per_day(("a", "m", "2026-10-30", 1, 0)), "userId", NOW)
        ex.poll_once(state, per_day(("a", "m", "2026-10-30", 4, 0)), "userId", NOW)
        self.assertEqual(sample(state.render(), "langfuse_up"), 1.0)

        def boom(_q):
            raise OSError("connection refused")

        ex.poll_once(state, boom, "userId", NOW)
        out = state.render()
        self.assertEqual(sample(out, "langfuse_up"), 0.0)
        self.assertEqual(sample(out, "langfuse_agent_tokens_total", agent_id="a", model="m"), 3.0)

    def test_negative_never_reached_and_bad_payload_are_down(self):
        self.assertEqual(sample(ex.State().render(), "langfuse_up"), 0.0)
        state = ex.State()
        ex.poll_once(state, lambda q: {"message": "events_only"}, "userId", NOW)
        self.assertEqual(sample(state.render(), "langfuse_up"), 0.0)

    def test_query_targets_v2_observations_one_query_per_day(self):
        qs = ex.build_queries("userId", NOW)
        self.assertEqual([d for d, _ in qs], ["2026-10-29", "2026-10-30"])
        (_, y), (_, t) = qs
        self.assertEqual(y["view"], "observations")
        self.assertNotIn("timeDimension", y)  # Langfuse 4.x refuses it with a userId dimension
        self.assertEqual((y["fromTimestamp"], y["toTimestamp"]), ("2026-10-29T00:00:00Z", "2026-10-30T00:00:00Z"))
        self.assertEqual((t["fromTimestamp"], t["toTimestamp"]), ("2026-10-30T00:00:00Z", "2026-10-30T12:00:00Z"))
        self.assertIn({"field": "userId"}, y["dimensions"])
        self.assertEqual(y["config"], {"row_limit": ex.ROW_LIMIT})

    def test_negative_a_truncated_result_fails_the_poll(self):
        state = ex.State()
        full = [("a", f"m{i}", "2026-10-30", 1, 0) for i in range(ex.ROW_LIMIT)]
        ex.poll_once(state, per_day(*full), "userId", NOW)
        self.assertEqual(sample(state.render(), "langfuse_up"), 0.0)


if __name__ == "__main__":
    unittest.main()
