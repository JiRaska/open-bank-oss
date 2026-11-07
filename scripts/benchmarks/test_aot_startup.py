# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import argparse
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("aot_startup", Path(__file__).with_name("aot_startup.py"))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class StartupBenchmarkTest(unittest.TestCase):
    def test_remote_urls_and_credentials_rejected(self):
        for url in ("https://example.com/", "http://192.168.1.1/", "file:///tmp/x",
                    "http://127.0.0.1@evil.example/", "http://u:p@127.0.0.1/",
                    "http://127.0.0.1/#fragment"):
            with self.subTest(url=url), self.assertRaises(bench.BenchmarkError):
                bench.endpoint(url)
        self.assertEqual(bench.endpoint("http://127.0.0.1:8080/ready"), ("127.0.0.1", 8080))
        self.assertEqual(bench.endpoint("https://[::1]/ready"), ("::1", 443))

    def test_balanced_alternating_order(self):
        order = bench.ordering(3)
        self.assertEqual(order, ["fast", "aot-cache-off", "aot-cache-on",
                                 "aot-cache-off", "aot-cache-on", "fast",
                                 "aot-cache-on", "fast", "aot-cache-off"])
        for variant in bench.VARIANTS:
            self.assertEqual(order.count(variant), 3)

    def test_cache_flags_are_isolated(self):
        fast = bench.command("java", ["-Xmx256m"], "fast")
        off = bench.command("java", ["-Xmx256m"], "aot-cache-off")
        on = bench.command("java", ["-Xmx256m"], "aot-cache-on")
        self.assertEqual(fast, ["java", "-Xmx256m", "-jar", "quarkus-run.jar"])
        self.assertIn("-XX:AOTMode=off", off)
        self.assertNotIn("-XX:AOTCache=app.aot", off)
        self.assertIn("-XX:AOTMode=on", on)
        self.assertIn("-XX:AOTCache=app.aot", on)
        self.assertIn("-Xlog:aot", on)
        for flag in ("-XX:AOTMode=off", "-Xshare:off", "-Xlog:disable", "-jar", "@options"):
            with self.assertRaises(bench.BenchmarkError):
                bench.command("java", [flag], "aot-cache-on")

    def test_open_without_mapping_or_fallback_is_not_evidence(self):
        opened = b"[0.01s][info][aot] Opened AOT cache app.aot.\n"
        mapped = b"[0.02s][info][aot] Mapped static  region #0 at base 0 top 1 (ReadWrite)\n"
        self.assertFalse(bench.cache_loaded(io.BytesIO(opened)))
        self.assertTrue(bench.cache_loaded(io.BytesIO(opened + mapped)))
        self.assertFalse(bench.cache_loaded(io.BytesIO(opened + mapped +
                         b"[0.03s][warning][aot] Unable to load cache; fallback\n")))

    def test_http_has_absolute_deadline_and_does_not_redirect(self):
        with patch.object(bench.urllib.request, "build_opener") as opener:
            opener.return_value.open.side_effect = lambda *a, **k: time.sleep(30)
            started = time.monotonic()
            with self.assertRaises(TimeoutError):
                bench.status("http://127.0.0.1/", 0.05)
            self.assertLess(time.monotonic() - started, 1)
        self.assertIsNone(bench.NoRedirect().redirect_request(
            None, None, 302, "", {}, "https://example.com/"))

    @unittest.skipUnless(os.name == "posix", "Process groups require POSIX")
    def test_readiness_timeout_terminates_only_spawned_child(self):
        children = []
        real_popen = subprocess.Popen

        def capture(*args, **kwargs):
            process = real_popen(*args, **kwargs)
            children.append(process)
            return process

        unrelated = real_popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            with tempfile.TemporaryDirectory() as directory, \
                    patch.object(bench, "ports_free"), patch.object(bench, "status", return_value=503), \
                    patch.object(bench.subprocess, "Popen", side_effect=capture):
                with self.assertRaisesRegex(bench.BenchmarkError, "Readiness timed out"):
                    bench.run_once([sys.executable, "-c",
                                    "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)"],
                                   directory, os.environ.copy(), "http://127.0.0.1:8080/ready",
                                   "http://127.0.0.1:8080/", 0.2, 0.05, 0.1)
            self.assertIsNotNone(children[0].poll())
            self.assertIsNone(unrelated.poll())
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=2)

    def test_failed_readiness_does_not_create_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for packaging in ("fast", "aot"):
                (root / packaging).mkdir()
                (root / packaging / "quarkus-run.jar").write_bytes(b"synthetic")
            (root / "aot/app.aot").write_bytes(b"synthetic cache")
            args = argparse.Namespace(fast_jar=str(root / "fast"), aot_jar=str(root / "aot"),
                                      java=sys.executable, ready_url="http://127.0.0.1:8080/ready",
                                      first_request_url="http://127.0.0.1:8080/",
                                      output=str(root / "report.json"), runs=3,
                                      timeout=1, request_timeout=0.1, stop_timeout=0.1, jvm_arg=[])
            version = subprocess.CompletedProcess([], 0, "", 'openjdk version "25.0.4"')
            with patch.dict(os.environ, {}, clear=True), patch.object(bench.subprocess, "run", return_value=version), \
                    patch.object(bench, "run_once", side_effect=bench.BenchmarkError("Readiness timed out")):
                with self.assertRaises(bench.BenchmarkError):
                    bench.benchmark(args)
            self.assertFalse((root / "report.json").exists())


if __name__ == "__main__":
    unittest.main()
