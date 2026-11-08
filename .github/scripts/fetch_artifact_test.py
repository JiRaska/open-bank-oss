#!/usr/bin/env python3
"""Tests for fetch-artifact.py (#15216): tier order, per-tier sha256 rejection, exit codes.

Network-free: the mirror and upstream tiers are served by a local http.server on 127.0.0.1
that speaks just enough of the OCI distribution API (manifest + blob, with an anonymous
Bearer challenge) and plain HTTP. Run: python3 -I .github/scripts/fetch_artifact_test.py
"""
from __future__ import annotations

import contextlib
import hashlib
import http.server
import importlib.util
import io
import json
import os
import pathlib
import tempfile
import threading
import unittest
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("fetch_artifact", HERE / "fetch-artifact.py")
fa = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fa)

GOOD = b"the pinned artifact bytes\n"
BAD = b"tampered bytes\n"
GOOD_SHA = hashlib.sha256(GOOD).hexdigest()


class _Server:
    """Routes: /v2/<repo>/manifests/<tag>, /v2/<repo>/blobs/<digest>, /token, /up/<file>."""

    def __init__(self):
        self.files: dict[str, bytes] = {}      # path -> body
        self.require_token = True
        self.hits: list[str] = []
        self.auth_seen: dict[str, str | None] = {}   # path -> Authorization header
        self.redirects: dict[str, str] = {}          # path -> location
        self.status: dict[str, int] = {}             # path -> forced status
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                outer.hits.append(self.path)
                path = self.path.split("?")[0]
                outer.auth_seen[path] = self.headers.get("Authorization")
                if path in outer.status:
                    return self._send(outer.status[path], b"gateway timeout")
                if path in outer.redirects:
                    self.send_response(302)
                    self.send_header("Location", outer.redirects[path])
                    self.end_headers()
                    return
                if path == "/token":
                    return self._send(200, json.dumps({"token": "anon"}).encode())
                if path.startswith("/v2/") and outer.require_token and \
                        self.headers.get("Authorization") != "Bearer anon":
                    self.send_response(401)
                    self.send_header("WWW-Authenticate",
                                     f'Bearer realm="{outer.base}/token",service="test",scope="pull"')
                    self.end_headers()
                    return
                body = outer.files.get(path)
                if body is None:
                    return self._send(404, b"nope")
                return self._send(200, body)

            def _send(self, code, body):
                self.send_response(code)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def put_oci(self, repo: str, tag: str, blob: bytes) -> None:
        digest = "sha256:" + hashlib.sha256(blob).hexdigest()
        self.files[f"/v2/{repo}/manifests/{tag}"] = json.dumps(
            {"schemaVersion": 2, "layers": [{"mediaType": "application/octet-stream",
                                             "digest": digest, "size": len(blob)}]}).encode()
        self.files[f"/v2/{repo}/blobs/{digest}"] = blob

    def close(self):
        self.httpd.shutdown()


class FetchArtifactTest(unittest.TestCase):
    def setUp(self):
        self.srv = _Server()
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="fetch-artifact-test-"))
        self.cache = self.tmp / "cache"
        self.cache.mkdir()
        self.lock = self.tmp / "artifacts.lock"
        self.lock.write_text(json.dumps({"schema": 1, "artifacts": [{
            "name": "tool", "kind": "binary-archive", "version": "1.2.3",
            "platforms": {"linux-amd64": {"url": f"{self.srv.base}/up/tool.tgz", "sha256": GOOD_SHA}},
        }]}))
        self.out = self.tmp / "out" / "tool.tgz"
        self.env = mock.patch.dict(os.environ, {
            "OB_ARTIFACT_CACHE": str(self.cache),
            "OB_ARTIFACT_MIRROR": f"{self.srv.base}/ci-mirror",
            "OB_ARTIFACT_ALLOW_HTTP": "1",
            "OB_ARTIFACT_RETRIES": "1",
            "OB_ARTIFACT_BACKOFF": "0",
            "OB_ARTIFACT_TIMEOUT": "5",
        })
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.srv.close()

    def run_main(self, *extra) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = fa.main(["tool", "--platform", "linux-amd64", "--lock", str(self.lock),
                          "--out", str(self.out), *extra])
        return rc, out.getvalue() + err.getvalue()

    # -- hits per tier ------------------------------------------------------------------

    def test_cache_hit_needs_no_network(self):
        (self.cache / GOOD_SHA).write_bytes(GOOD)
        rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        self.assertEqual(self.out.read_bytes(), GOOD)
        self.assertIn("cache: verified", log)
        self.assertEqual(self.srv.hits, [], "a cache hit must not touch mirror or upstream")

    def test_mirror_hit_with_anonymous_token(self):
        self.srv.put_oci("ci-mirror/tool", "1.2.3-linux-amd64", GOOD)
        rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        self.assertEqual(self.out.read_bytes(), GOOD)
        self.assertIn("mirror: verified", log)
        self.assertIn("/token?service=test&scope=pull", self.srv.hits)
        self.assertFalse(any(h.startswith("/up/") for h in self.srv.hits), "mirror hit must not reach upstream")
        self.assertEqual((self.cache / GOOD_SHA).read_bytes(), GOOD, "a remote hit must populate the cache")

    def test_upstream_fallback_when_cache_and_mirror_miss(self):
        self.srv.files["/up/tool.tgz"] = GOOD
        rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        self.assertIn("cache: unavailable", log)
        self.assertIn("mirror: unavailable", log)
        self.assertIn("upstream: verified", log)

    def test_mirror_tier_skipped_cleanly_when_unset(self):
        self.srv.files["/up/tool.tgz"] = GOOD
        with mock.patch.dict(os.environ, {"OB_ARTIFACT_MIRROR": ""}):
            rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        self.assertIn("mirror: not configured, skipped", log)

    # -- sha256 rejection, per tier -----------------------------------------------------

    def test_cache_mismatch_rejected_then_next_tier(self):
        (self.cache / GOOD_SHA).write_bytes(BAD)
        self.srv.files["/up/tool.tgz"] = GOOD
        rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        self.assertIn("cache: REJECTED", log)
        self.assertEqual(self.out.read_bytes(), GOOD)

    def test_mirror_mismatch_rejected_then_next_tier(self):
        self.srv.put_oci("ci-mirror/tool", "1.2.3-linux-amd64", BAD)
        self.srv.files["/up/tool.tgz"] = GOOD
        rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        self.assertIn("mirror: REJECTED", log)
        self.assertEqual(self.out.read_bytes(), GOOD)

    def test_upstream_mismatch_rejected_and_is_integrity_not_outage(self):
        self.srv.files["/up/tool.tgz"] = BAD
        rc, log = self.run_main()
        self.assertEqual(rc, fa.EXIT_INTEGRITY, log)
        self.assertIn("upstream: REJECTED", log)
        self.assertNotIn("UNVERIFIED", log)
        self.assertFalse(self.out.exists(), "rejected bytes must never be written to --out")
        self.assertFalse((self.cache / GOOD_SHA).exists(), "rejected bytes must never enter the cache")

    # -- total failure ------------------------------------------------------------------

    def test_all_tiers_down_gives_distinct_exit_code(self):
        rc, log = self.run_main()
        self.assertEqual(rc, fa.EXIT_UNAVAILABLE, log)
        self.assertEqual(fa.EXIT_UNAVAILABLE, 75)
        self.assertIn("UNVERIFIED (artifact unavailable): tool", log)
        self.assertFalse(self.out.exists())

    def test_unknown_name_is_usage_error(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = fa.main(["nope", "--lock", str(self.lock), "--out", str(self.out)])
        self.assertEqual(rc, fa.EXIT_USAGE)

    def test_show_version_reads_the_lock(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = fa.main(["tool", "--platform", "linux-amd64", "--lock", str(self.lock), "--show", "version"])
        self.assertEqual((rc, out.getvalue().strip()), (0, "1.2.3"))

    def test_duplicate_key_lock_refused(self):
        self.lock.write_text('{"schema": 1, "artifacts": [], "artifacts": []}')
        rc, _ = self.run_main()
        self.assertEqual(rc, fa.EXIT_USAGE)

    def test_real_lock_entries_resolve_by_name(self):
        lock = fa.load_lock(fa.DEFAULT_LOCK)
        for name, plat in (("gitleaks", "linux-amd64"), ("promtool", "linux-amd64")):
            entry, platform, spec = fa.select(lock, name, plat)
            self.assertEqual(platform, plat)
            self.assertRegex(spec["sha256"], r"^[0-9a-f]{64}$")


class GithubReleaseApiFallbackTest(unittest.TestCase):
    """A github.com release URL whose web frontend 504s is fetched through the REST API route."""

    URL = "https://github.com/o/r/releases/download/v1.2.3/tool.tgz"

    def setUp(self):
        self.srv = _Server()
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="fetch-artifact-gh-"))
        self.lock = self.tmp / "artifacts.lock"
        self.lock.write_text(json.dumps({"schema": 1, "artifacts": [{
            "name": "tool", "kind": "binary-archive", "version": "1.2.3",
            "platforms": {"linux-amd64": {"url": self.URL, "sha256": GOOD_SHA}}}]}))
        self.out = self.tmp / "tool.tgz"
        self.srv.require_token = False
        self.srv.status["/web/o/r/releases/download/v1.2.3/tool.tgz"] = 504
        self.srv.files["/api/repos/o/r/releases/tags/v1.2.3"] = json.dumps(
            {"assets": [{"id": 7, "name": "tool.tgz"}, {"id": 8, "name": "other.tgz"}]}).encode()
        self.srv.redirects["/api/repos/o/r/releases/assets/7"] = f"{self.srv.base.replace('127.0.0.1', 'localhost')}/store/7"
        self.env = mock.patch.dict(os.environ, {
            "OB_ARTIFACT_CACHE": "", "OB_ARTIFACT_MIRROR": "",
            "OB_GITHUB_WEB": f"{self.srv.base}/web", "OB_GITHUB_API": f"{self.srv.base}/api",
            "OB_ARTIFACT_RETRIES": "1", "OB_ARTIFACT_BACKOFF": "0", "OB_ARTIFACT_TIMEOUT": "5",
            "GITHUB_TOKEN": "test-token-not-a-secret",
        })
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.srv.close()

    def run_main(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = fa.main(["tool", "--platform", "linux-amd64", "--lock", str(self.lock), "--out", str(self.out)])
        return rc, out.getvalue() + err.getvalue()

    def test_web_504_falls_back_to_api_asset_route(self):
        self.srv.files["/store/7"] = GOOD
        rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        self.assertEqual(self.out.read_bytes(), GOOD)
        self.assertIn("REST API asset route", log)
        self.assertEqual(self.srv.auth_seen["/api/repos/o/r/releases/assets/7"], "Bearer test-token-not-a-secret")
        self.assertIsNone(self.srv.auth_seen["/store/7"], "the token must not follow a cross-host redirect")

    def test_web_route_preferred_when_it_answers(self):
        del self.srv.status["/web/o/r/releases/download/v1.2.3/tool.tgz"]
        self.srv.files["/web/o/r/releases/download/v1.2.3/tool.tgz"] = GOOD
        rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        self.assertFalse(any(h.startswith("/api/") for h in self.srv.hits), "API quota spent needlessly")

    def test_api_route_bytes_still_sha256_verified(self):
        self.srv.files["/store/7"] = BAD
        rc, log = self.run_main()
        self.assertEqual(rc, fa.EXIT_INTEGRITY, log)
        self.assertFalse(self.out.exists())

    def test_504_is_not_retried(self):
        self.srv.files["/store/7"] = GOOD
        with mock.patch.dict(os.environ, {"OB_ARTIFACT_RETRIES": "3"}):
            rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        web = [h for h in self.srv.hits if h.startswith("/web/")]
        self.assertEqual(len(web), 1, f"a 504 must end the retries at once (#15220), got {web}")

    def test_prefer_api_skips_the_dead_web_route(self):
        self.srv.files["/store/7"] = GOOD
        with mock.patch.dict(os.environ, {"OB_GITHUB_PREFER_API": "1"}):
            rc, log = self.run_main()
        self.assertEqual(rc, 0, log)
        self.assertFalse(any(h.startswith("/web/") for h in self.srv.hits))

    def test_web_and_api_down_is_unavailable(self):
        self.srv.status["/api/repos/o/r/releases/tags/v1.2.3"] = 504
        rc, log = self.run_main()
        self.assertEqual(rc, fa.EXIT_UNAVAILABLE, log)
        self.assertIn("UNVERIFIED (artifact unavailable): tool", log)


if __name__ == "__main__":
    unittest.main(verbosity=1)
