import importlib.util
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("status_checks", Path(__file__).with_name("checks.py"))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class StatusEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 7, 12, tzinfo=timezone.utc)
        self.green = {name: {"ok": True} for name in checks.TARGETS}

    def test_missing_current_check_cannot_reuse_previous_green(self):
        previous = {
            "history": [{"at": (self.now - timedelta(minutes=5)).isoformat(),
                         "checks": {name: True for name in checks.TARGETS}}]
        }
        current = dict(self.green)
        del current["customer_sign_in"]
        document = checks.build_document(self.now, previous, current)
        self.assertEqual(document["state"], "DEGRADED")
        self.assertEqual(document["checks"]["customer_sign_in"], {"ok": False, "missing": True})
        self.assertTrue(document["history"][-1]["checks"]["customer_sign_in"] is False)

    def test_history_excludes_future_and_older_than_30_days(self):
        previous = {"history": [
            {"at": (self.now - timedelta(days=31)).isoformat(), "checks": {"website": True}},
            {"at": (self.now + timedelta(days=1)).isoformat(), "checks": {"website": True}},
            {"at": (self.now - timedelta(days=1)).isoformat(), "checks": {"website": False}},
        ]}
        document = checks.build_document(self.now, previous, self.green)
        self.assertEqual(len(document["history"]), 2)
        self.assertEqual(document["history"][0]["checks"]["website"], False)

    def test_current_failed_probe_is_degraded_and_expires(self):
        current = dict(self.green)
        current["website"] = {"ok": False, "http_status": 200}
        document = checks.build_document(self.now, None, current)
        self.assertEqual(document["state"], "DEGRADED")
        self.assertEqual(document["expires_at"], (self.now + timedelta(minutes=10)).isoformat().replace("+00:00", "Z"))

    def test_website_and_oidc_require_content_not_just_http_200(self):
        with patch.object(checks, "_http", return_value=(False, 200, 1)) as http:
            self.assertFalse(checks.probe("website", checks.TARGETS["website"])["ok"])
            self.assertEqual(http.call_args.kwargs["markers"], (b"<title>OpenBank",))
            self.assertFalse(checks.probe("customer_sign_in", checks.TARGETS["customer_sign_in"])["ok"])
            self.assertEqual(http.call_args.kwargs["markers"],
                             (b'"issuer"', b'"token_endpoint"', b'"authorization_endpoint"', b'"jwks_uri"'))

    def test_only_confirmed_incidents_with_valid_recovery_are_published(self):
        incident = {"id": "maintenance-1", "summary": "Service interruption",
                    "started_at": "2026-10-07T12:00:00Z", "resolved_at": "2026-10-07T12:30:00Z",
                    "confirmed": True}
        self.assertEqual(checks.confirmed_incidents([incident])[0]["resolved_at"], incident["resolved_at"])
        with self.assertRaises(ValueError):
            checks.confirmed_incidents([{**incident, "confirmed": False}])
        with self.assertRaises(ValueError):
            checks.confirmed_incidents([{**incident, "resolved_at": "2026-10-07T11:00:00Z"}])

    def test_malformed_existing_history_fails_instead_of_starting_over(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "previous.json"
            path.write_text('{"schema_version":1,"history":"missing"}')
            with self.assertRaises(ValueError):
                checks.load_previous(path)

    def test_open_confirmed_incident_prevents_green_headline(self):
        document = checks.build_document(self.now, None, self.green)
        self.assertEqual(document["state"], "OPERATIONAL")
        incident = checks.confirmed_incidents([{"id": "incident-1", "summary": "Interruption",
            "started_at": "2026-10-07T11:00:00Z", "confirmed": True}])
        self.assertEqual(checks.with_incidents(document, incident)["state"], "DEGRADED")


if __name__ == "__main__":
    unittest.main()
