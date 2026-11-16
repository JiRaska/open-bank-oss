# SPDX-License-Identifier: Apache-2.0
import copy
import contextlib
import io
import json
import urllib.error
import unittest
from unittest.mock import MagicMock, patch
from urllib.parse import parse_qsl, urlsplit

from read_pact_pair import MAX_BYTES, main, matrix_url, verdict

PINS = ("openbank-consumer", "a" * 40, "openbank-provider", "b" * 40)
LINK = {"_links": {"self": {"href": "https://example.test/result"}}}
ROW = {
    "consumer": {"name": PINS[0], "version": {"number": PINS[1]}},
    "provider": {"name": PINS[2], "version": {"number": PINS[3]}},
    "pact": LINK,
    "verificationResult": {"success": True, **LINK},
}

class MatrixTest(unittest.TestCase):
    def test_query_preserves_pair_order_and_explicit_versions(self):
        query = urlsplit(matrix_url("https://example.test/root", *PINS)).query
        self.assertEqual(parse_qsl(query), [
            ("q[][pacticipant]", PINS[0]), ("q[][version]", PINS[1]),
            ("q[][pacticipant]", PINS[2]), ("q[][version]", PINS[3]), ("latestby", "cvpv"),
        ])

    def test_reject_credentials_in_broker_url_and_abbreviated_version(self):
        with self.assertRaises(ValueError):
            matrix_url("https://user:secret@example.test", *PINS)
        with self.assertRaises(ValueError):
            matrix_url("https://example.test", PINS[0], "aaa", *PINS[2:])

    def test_success_requires_an_exact_successful_row(self):
        self.assertIs(verdict({"matrix": [ROW], "summary": {"deployable": True}}, *PINS), True)
        self.assertIsNone(verdict({"matrix": [], "summary": {"deployable": True}}, *PINS))
        self.assertIsNone(verdict({"matrix": [ROW], "summary": {"deployable": "true"}}, *PINS))

    def test_wrong_version_foreign_or_ignored_row_refuses(self):
        foreign = copy.deepcopy(ROW)
        foreign["provider"]["version"]["number"] = "c" * 40
        for rows in ([foreign], [ROW, foreign], [dict(ROW, ignored=True)], [dict(ROW, ignored=None)], [dict(ROW, ignored="false")]):
            with self.subTest(rows=rows):
                self.assertIsNone(verdict({"matrix": rows, "summary": {"deployable": True}}, *PINS))

    def test_unknown_result_cannot_pass(self):
        for result in (None, {}, {"success": "true", **LINK}):
            with self.subTest(result=result):
                self.assertIsNone(verdict({"matrix": [dict(ROW, verificationResult=result)], "summary": {"deployable": True}}, *PINS))

    def test_evidence_links_must_be_strings(self):
        for href in ("", "  ", True, {}, None):
            bad_link = {"_links": {"self": {"href": href}}}
            for row in (dict(ROW, pact=bad_link), dict(ROW, verificationResult={"success": True, **bad_link})):
                self.assertIsNone(verdict({"matrix": [row], "summary": {"deployable": True}}, *PINS))

    def test_exact_failure_reported(self):
        row = dict(ROW, verificationResult={"success": False, **LINK})
        self.assertIs(verdict({"matrix": [row], "summary": {"deployable": False}}, *PINS), False)

class BrokerReadTest(unittest.TestCase):
    def run_with_opener(self, opener):
        env = {"PACT_BROKER_URL": "https://example.test", "PACT_BROKER_USERNAME": "user",
               "PACT_BROKER_PASSWORD": "synthetic-secret"}
        output = io.StringIO()
        with patch.dict("os.environ", env, clear=True), patch("sys.argv", ["read", *PINS]):
            with patch("urllib.request.build_opener", return_value=opener), contextlib.redirect_stdout(output):
                code = main()
        self.assertNotIn("synthetic-secret", output.getvalue())
        return code, output.getvalue().strip()

    def test_real_request_object_is_get_and_response_is_bounded(self):
        opener = MagicMock()
        response = opener.open.return_value.__enter__.return_value
        response.read.return_value = json.dumps({"matrix": [ROW], "summary": {"deployable": True}}).encode()
        self.assertEqual(self.run_with_opener(opener), (0, "PACT_PAIR_VERIFIED"))
        request = opener.open.call_args.args[0]
        self.assertEqual(request.get_method(), "GET")
        response.read.assert_called_once_with(MAX_BYTES + 1)

    def test_auth_denial_and_redirect_do_not_parse_or_expose_body(self):
        for code in (401, 302):
            opener = MagicMock()
            opener.open.side_effect = urllib.error.HTTPError("https://example.test", code, "", {}, None)
            self.assertEqual(self.run_with_opener(opener), (2, f"PACT_PAIR_UNPROVEN (HTTP {code})"))

    def test_oversized_response_stays_unproven(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.read.return_value = b" " * (MAX_BYTES + 1)
        self.assertEqual(self.run_with_opener(opener), (2, "PACT_PAIR_UNPROVEN"))

if __name__ == "__main__":
    unittest.main()
