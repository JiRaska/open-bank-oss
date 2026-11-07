"""Private prototype tests: no network, branch updates or CI dispatch."""

import datetime as dt
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location(
    "updater", pathlib.Path(__file__).with_name("auto-update-armed-prs.py")
)
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)
NOW = dt.datetime(2026, 10, 16, tzinfo=dt.timezone.utc)


def entry(n, kind="small", **kw):
    return dict(
        number=n,
        kind=kind,
        building=False,
        ok=True,
        why="existing decide allowed",
        waiting_since=f"2026-09-{n:02}",
        **kw,
    )


class GlobalSlotTest(unittest.TestCase):
    def test_actual_decide_classify_five_small_unknown_fleet(self):
        for count in [0, 2, 81]:
            checks = {
                "gate": "success",
                **{f"build (openbank-{i})": "success" for i in range(count)},
            }
            kind, _building = u.classify(checks, 20)
            ok, why = u.decide(
                {
                    "draft": False,
                    "auto_merge": True,
                    "same_repo": True,
                    "mergeable_state": "behind",
                },
                ["gate"],
                checks,
                NOW,
                NOW - dt.timedelta(hours=1),
                20,
            )
            entries = [entry(i, kind) for i in range(1, 6)]
            self.assertTrue(ok, why)
            self.assertEqual(sum(ok for _, ok, _ in u.global_plan(entries, 5)), 1)
            if count < 20:
                self.assertEqual(sum(ok for _, ok, _ in u.plan(entries, 5)), 5)

    def reader(self, checks=None, runs=None, **changes):
        pr = {
            "state": "open",
            "draft": False,
            "labels": [],
            "auto_merge": True,
            "mergeable_state": "clean",
            "head": {"sha": "head", "repo": {"full_name": "a/b"}},
        }
        pr.update(changes)

        def get(path):
            if "/pulls/" in path:
                return pr
            if "/compare/" in path:
                return {"behind_by": 0}
            if "/check-runs?" in path:
                return {"check_runs": checks or []}
            if path.endswith("/status"):
                return {"statuses": []}
            if "/actions/runs?" in path:
                return {"workflow_runs": runs or []}
            raise AssertionError(path)

        return get

    def read(self, **kw):
        return u.read_reservation(
            "a/b", 1, "main", ["gate"], NOW, 20, self.reader(**kw)
        )

    def check(self, status="in_progress", conclusion=None, ident=1, completed=None):
        return {
            "id": ident,
            "name": "gate",
            "status": status,
            "conclusion": conclusion,
            "completed_at": completed or NOW.isoformat(),
        }

    def test_successive_cron_pending_small_reserves(self):
        r = self.read(checks=[self.check()])
        self.assertEqual(r["reservation"], "reserved")
        for _ in range(2):
            entries = [dict(entry(1), ok=False, **r), entry(2)]
            self.assertFalse(any(ok for _, ok, _ in u.global_plan(entries, 5)))

    def test_failure_and_cancel_release_despite_pending_sibling(self):
        for conclusion in ["failure", "cancelled", "timed_out"]:
            checks = [
                self.check("completed", conclusion),
                dict(self.check(), name="other"),
            ]
            r = u.read_reservation(
                "a/b", 1, "main", ["gate", "other"], NOW, 20, self.reader(checks=checks)
            )
            self.assertEqual(r["reservation"], "none")
            entries = [dict(entry(1), ok=False, **r), entry(2)]
            self.assertEqual([n for n, ok, _ in u.global_plan(entries, 5) if ok], [2])

    def test_newest_check_id_not_old_success(self):
        r = self.read(
            checks=[self.check("completed", "success", 1), self.check(ident=2)]
        )
        self.assertEqual(r["reservation"], "reserved")

    def test_closed_draft_blocked_unarmed_fork_do_not_reserve(self):
        for change in [
            {"state": "closed"},
            {"draft": True},
            {"labels": [{"name": "blocked"}]},
            {"auto_merge": None},
            {"head": {"sha": "head", "repo": {"full_name": "fork/b"}}},
        ]:
            self.assertEqual(
                self.read(checks=[self.check()], **change)["reservation"], "none"
            )

    def test_head_replacement_has_no_stored_lease(self):
        self.assertEqual(self.read(checks=[self.check()])["reservation"], "reserved")
        r = self.read(
            checks=[self.check("completed", "failure")],
            head={"sha": "new", "repo": {"full_name": "a/b"}},
        )
        self.assertEqual((r["head"], r["reservation"]), ("new", "none"))

    def test_known_primary_registration_gap_only(self):
        run = {
            "id": 1,
            "repository": {"full_name": "a/b"},
            "pull_requests": [{"number": 1}],
            "run_attempt": 1,
            "path": ".github/workflows/services-ci.yml",
            "head_sha": "head",
            "event": "pull_request",
            "status": "queued",
            "conclusion": None,
        }
        self.assertEqual(self.read(runs=[run])["reservation"], "reserved")
        for change in [
            {"path": ".github/workflows/publish.yml"},
            {"head_sha": "old"},
            {"event": "push"},
        ]:
            self.assertEqual(
                self.read(runs=[dict(run, **change)])["reservation"], "unknown"
            )
        failed = dict(run, id=2, status="completed", conclusion="failure")
        self.assertEqual(self.read(runs=[run, failed])["reservation"], "none")

    def test_old_green_cannot_hide_new_queued_rerun(self):
        old = (NOW - dt.timedelta(minutes=21)).isoformat()
        run = {
            "id": 2,
            "run_attempt": 2,
            "path": ".github/workflows/ci.yml",
            "head_sha": "head",
            "event": "pull_request",
            "status": "queued",
            "conclusion": None,
            "repository": {"full_name": "a/b"},
            "pull_requests": [{"number": 1}],
        }
        checks = [self.check("completed", "success", completed=old)]
        self.assertEqual(
            self.read(checks=checks, runs=[run])["reservation"], "reserved"
        )
        self.assertEqual(
            self.read(checks=checks, runs=[dict(run, pull_requests=[{"number": 99}])])[
                "reservation"
            ],
            "none",
        )
        self.assertEqual(
            self.read(
                checks=checks, runs=[dict(run, repository={"full_name": "other/repo"})]
            )["reservation"],
            "none",
        )

    def test_same_sha_other_pr_cannot_reserve_registration_gap(self):
        run = {
            "id": 2,
            "run_attempt": 1,
            "path": ".github/workflows/ci.yml",
            "head_sha": "head",
            "event": "pull_request",
            "status": "queued",
            "conclusion": None,
            "repository": {"full_name": "a/b"},
            "pull_requests": [{"number": 99}],
        }
        self.assertEqual(self.read(runs=[run])["reservation"], "unknown")

    def test_pagination_and_latest_run_attempt(self):
        run = {
            "id": 2,
            "run_attempt": 1,
            "path": ".github/workflows/ci.yml",
            "head_sha": "head",
            "event": "pull_request",
            "status": "completed",
            "conclusion": "failure",
            "repository": {"full_name": "a/b"},
            "pull_requests": [{"number": 1}],
        }
        reader = self.reader()
        calls = []

        def pages(path):
            calls.append(path)
            if "/check-runs?" in path:
                return {
                    "check_runs": [self.check("completed", "success", 1)] * 100
                    if path.endswith("&page=1")
                    else [self.check(ident=2)]
                }
            return reader(path)

        self.assertEqual(
            u.read_reservation("a/b", 1, "main", ["gate"], NOW, 20, pages)[
                "reservation"
            ],
            "reserved",
        )
        self.assertTrue(
            any("check-runs?" in p and p.endswith("&page=2") for p in calls)
        )
        calls.clear()

        def run_pages(path):
            calls.append(path)
            if "/actions/runs?" in path:
                return {
                    "workflow_runs": [run] * 100
                    if path.endswith("&page=1")
                    else [dict(run, run_attempt=2, status="queued", conclusion=None)]
                }
            return reader(path)

        self.assertEqual(
            u.read_reservation("a/b", 1, "main", ["gate"], NOW, 20, run_pages)[
                "reservation"
            ],
            "reserved",
        )
        self.assertTrue(
            any("actions/runs?" in p and p.endswith("&page=2") for p in calls)
        )

    def retry_fixture(self, independent=False, attempt=2, **job_changes):
        old = {
            "id": 10, "run_attempt": 1, "workflow_id": 70,
            "path": ".github/workflows/ci.yml", "head_sha": "head",
            "event": "pull_request", "status": "completed", "conclusion": "failure",
            "repository": {"full_name": "a/b"}, "pull_requests": [{"number": 1}],
        }
        retry = dict(old, run_attempt=attempt, status="queued", conclusion=None)
        if independent:
            retry = dict(retry, id=20, workflow_id=80, path=".github/workflows/services-ci.yml")
        failure = dict(self.check("completed", "failure"),
                       details_url="https://github.com/a/b/actions/runs/10/job/100")
        base = self.reader(checks=[failure], runs=[old, retry])
        job = {"id": 100, "run_id": 10, "run_attempt": 1, "head_sha": "head",
                   "check_run_url": u.API + "/repos/a/b/check-runs/1"}
        job.update(job_changes)

        def reader(path):
            if path.endswith("/actions/jobs/100"):
                return job
            if path.endswith("/actions/runs/10"):
                return old
            return base(path)

        return reader

    def test_failed_old_attempt_new_same_workflow_queued_reserves(self):
        r = u.read_reservation("a/b", 1, "main", ["gate"], NOW, 20,
                               self.retry_fixture())
        self.assertEqual(r["reservation"], "reserved")

    def test_independent_failed_workflow_not_erased_by_pending_other(self):
        r = u.read_reservation("a/b", 1, "main", ["gate"], NOW, 20,
                               self.retry_fixture(independent=True))
        self.assertEqual(r["reservation"], "none")

    def test_same_attempt_or_foreign_job_does_not_supersede_failure(self):
        for reader in [self.retry_fixture(attempt=1),
                       self.retry_fixture(head_sha="other"),
                       self.retry_fixture(check_run_url=u.API + "/repos/a/b/check-runs/999")]:
            r = u.read_reservation("a/b", 1, "main", ["gate"], NOW, 20, reader)
            self.assertEqual(r["reservation"], "none")

    def test_new_run_same_workflow_reserves_but_wrong_identity_does_not(self):
        base = self.retry_fixture()

        def changed_run(**changes):
            def reader(path):
                data = base(path)
                if "/actions/runs?" in path:
                    old, retry = data["workflow_runs"]
                    return {"workflow_runs": [old, dict(retry, id=11, **changes)]}
                return data
            return reader

        self.assertEqual(u.read_reservation("a/b", 1, "main", ["gate"], NOW, 20,
                                           changed_run())["reservation"], "reserved")
        for changes in [{"workflow_id": 99}, {"head_sha": "other"},
                        {"pull_requests": [{"number": 99}]},
                        {"repository": {"full_name": "other/repo"}}]:
            r = u.read_reservation("a/b", 1, "main", ["gate"], NOW, 20,
                                   changed_run(**changes))
            self.assertEqual(r["reservation"], "none")

    def test_one_retry_cannot_hide_independent_required_failure(self):
        base = self.retry_fixture()

        def reader(path):
            data = base(path)
            if "/check-runs?" in path:
                return {"check_runs": data["check_runs"] + [
                    dict(self.check("completed", "failure", ident=2), name="independent")
                ]}
            return data

        r = u.read_reservation("a/b", 1, "main", ["gate", "independent"], NOW, 20, reader)
        self.assertEqual(r["reservation"], "none")

    def test_retry_metadata_failure_is_unknown_not_free(self):
        base = self.retry_fixture()

        def reader(path):
            if "/actions/jobs/" in path:
                raise RuntimeError("job metadata unavailable")
            return base(path)

        r = u.read_reservation("a/b", 1, "main", ["gate"], NOW, 20, reader)
        self.assertEqual(r["reservation"], "unknown")

    def test_ready_lease_bounded_and_clean_only(self):
        self.assertEqual(
            self.read(checks=[self.check("completed", "success")])["reservation"],
            "reserved",
        )
        old = (NOW - dt.timedelta(minutes=21)).isoformat()
        self.assertEqual(
            self.read(checks=[self.check("completed", "success", completed=old)])[
                "reservation"
            ],
            "none",
        )
        self.assertEqual(
            self.read(
                checks=[self.check("completed", "success")], mergeable_state="blocked"
            )["reservation"],
            "none",
        )

    def test_read_failure_failclosed(self):
        def broken(_):
            raise RuntimeError("read failed")

        r = u.read_reservation("a/b", 1, "main", ["gate"], NOW, 20, broken)
        self.assertEqual(r["reservation"], "unknown")
        self.assertFalse(
            any(
                ok
                for _, ok, _ in u.global_plan(
                    [dict(entry(1), ok=False, **r), entry(2)], 5
                )
            )
        )

    def test_oldest_after_budget_exclusion_hold_and_cap(self):
        entries = [dict(entry(1), ok=False), entry(3), entry(2), entry(4, held=True)]
        self.assertEqual([n for n, ok, _ in u.global_plan(entries, 5) if ok], [2])
        self.assertFalse(any(ok for _, ok, _ in u.global_plan(entries, 0)))

    def test_snapshot_guard(self):
        pr = {
            "state": "open",
            "draft": False,
            "labels": [],
            "auto_merge": True,
            "mergeable_state": "behind",
            "head": {"sha": "head", "repo": {"full_name": "a/b"}},
        }

        def reader(change=None, main="main"):
            def get(path):
                return (
                    {"object": {"sha": main}}
                    if "/git/ref/" in path
                    else dict(pr, **(change or {}))
                )

            return get

        self.assertTrue(
            u.snapshot_unchanged("a/b", "main", "main", 1, "head", reader())
        )
        for change in [
            {"state": "closed"},
            {"draft": True},
            {"labels": [{"name": "blocked"}]},
            {"auto_merge": None},
            {"mergeable_state": "dirty"},
            {"head": {"sha": "new", "repo": {"full_name": "a/b"}}},
            {"head": {"sha": "head", "repo": {"full_name": "fork/repo"}}},
        ]:
            self.assertFalse(
                u.snapshot_unchanged("a/b", "main", "main", 1, "head", reader(change))
            )
        self.assertFalse(
            u.snapshot_unchanged("a/b", "main", "main", 1, "head", reader(main="new"))
        )


if __name__ == "__main__":
    unittest.main()
