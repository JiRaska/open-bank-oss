#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Falsification for gatelib's caches.

Every case here is one a cache can get wrong while still looking fast and green. The ones that
matter most are the two that only ever fail in a SHARD — a caller mutating what the cache handed
it, and a file that changed between two reads — because a gate that is correct alone and wrong
under concurrency is the hardest failure this repo could ship into `Validate manifests`.
"""

from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import gatelib  # noqa: E402


class GatelibTest(unittest.TestCase):
    def setUp(self):
        gatelib.clear()
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()
        gatelib.clear()

    def write(self, name, text):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        return p

    # --- the property whose failure only shows up in a shard ------------------------------
    def test_a_caller_mutating_the_result_cannot_poison_the_next_caller(self):
        p = self.write("a.yaml", "spec:\n  ports:\n    - 8080\n")
        first = gatelib.load_yaml(p)
        first["spec"]["ports"].append(9999)
        first["spec"]["injected"] = True
        second = gatelib.load_yaml(p)
        self.assertEqual(second, {"spec": {"ports": [8080]}})

    def test_two_paths_with_identical_bytes_do_not_alias(self):
        a = self.write("a.yaml", "k: [1, 2]\n")
        b = self.write("b.yaml", "k: [1, 2]\n")
        da = gatelib.load_yaml(a)
        da["k"].append(3)
        self.assertEqual(gatelib.load_yaml(b), {"k": [1, 2]})

    def test_a_file_rewritten_IN_THE_SAME_PROCESS_is_reread(self):
        """The case that broke `check-libs-annotations-implemented --self-test`.

        Its harness builds a fixture tree, analyses it, rewrites the SAME paths and analyses
        again. An earlier gatelib memoised `read_text` by path, so the second pass saw the
        first pass's bytes and the gate reported itself unfalsifiable. Nothing about that is
        specific to self-tests: the drift gates run a generator and then read what it wrote.
        """
        p = self.write("a.yaml", "value: before\n")
        self.assertEqual(gatelib.load_yaml(p), {"value": "before"})
        p.write_text("value: after\n", encoding="utf-8")   # same path, same process, no clear()
        self.assertEqual(gatelib.load_yaml(p), {"value": "after"})
        self.assertEqual(gatelib.read_text(p), "value: after\n")

    def test_a_rewritten_file_is_not_a_stale_hit(self):
        # The drift gates run their generator and then read what it wrote. Keying on the path
        # (or on mtime, which has 1s granularity on some filesystems) would serve them the
        # pre-generation parse and report drift-free against a file that had just changed.
        p = self.write("a.yaml", "value: before\n")
        self.assertEqual(gatelib.load_yaml(p), {"value": "before"})
        gatelib.clear()  # a fresh process, as a later gate would be
        p.write_text("value: after\n", encoding="utf-8")
        self.assertEqual(gatelib.load_yaml(p), {"value": "after"})

    # --- parity with the stdlib calls it replaces -----------------------------------------
    def test_multi_document_parity_with_safe_load_all(self):
        import yaml

        text = "a: 1\n---\nb: 2\n---\n[]\n"
        p = self.write("multi.yaml", text)
        self.assertEqual(gatelib.load_yaml_all(p), list(yaml.safe_load_all(text)))
        self.assertEqual(gatelib.load_yaml(p), {"a": 1})

    def test_python_loader_preserves_directives_tabs_escapes_and_safe_tags(self):
        import yaml

        cases = [
            "%YAML 1.3\n---\nkind: ServiceAccount\n",
            "kind:\tServiceAccount\n",
            'value: "\\uD800"\n',
            "a: &one [1, 2]\nb: *one\n---\nwhen: 2026-08-09\n",
            "value: !!python/object:builtins.object {}\n",
        ]
        for text in cases:
            with self.subTest(text=text):
                path = self.write("parser.yaml", text)
                try:
                    expected = list(yaml.safe_load_all(text))
                except yaml.YAMLError as error:
                    with self.assertRaises(type(error)) as caught:
                        gatelib.load_yaml_all(path, pure_python=True)
                    self.assertEqual(str(caught.exception), str(error))
                else:
                    self.assertEqual(gatelib.load_yaml_all(path, pure_python=True), expected)

    def test_python_and_default_loader_caches_are_separate_in_memory_and_on_disk(self):
        import os
        from unittest.mock import patch
        import yaml

        class DifferentLoader(yaml.SafeLoader):
            pass

        DifferentLoader.add_constructor(
            "tag:yaml.org,2002:str", lambda loader, node: "auto:" + loader.construct_scalar(node))
        cache = self.root / "parser-cache"
        cache.mkdir()
        path = self.write("loader.yaml", "key: value\n")
        with patch.dict(os.environ, {gatelib.CACHE_DIR_ENV: str(cache)}), \
                patch.object(gatelib, "SafeLoader", DifferentLoader):
            for _ in range(2):
                self.assertEqual(gatelib.load_yaml_all(path), [{"auto:key": "auto:value"}])
                self.assertEqual(gatelib.load_yaml_all(path, pure_python=True), [{"key": "value"}])
                gatelib.clear()  # next iteration must read both loader-specific disk entries
            self.assertEqual(len(list(cache.glob("*.marshal"))), 2)

    def test_python_loader_retains_copy_and_content_invalidation(self):
        path = self.write("python.yaml", "values: [1, 2]\n")
        first = gatelib.load_yaml_all(path, pure_python=True)
        first[0]["values"].append(3)
        self.assertEqual(gatelib.load_yaml_all(path, pure_python=True), [{"values": [1, 2]}])
        path.write_text("values: [4, 5]\n", encoding="utf-8")
        self.assertEqual(gatelib.load_yaml_all(path, pure_python=True), [{"values": [4, 5]}])

    def test_security_callers_keep_parser_sensitive_findings(self):
        import contextlib
        import importlib.util
        import io

        def checker(name):
            path = pathlib.Path(__file__).with_name(name)
            spec = importlib.util.spec_from_file_location(name, path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            return module

        residency = checker("check-model-residency-claims.py")
        residency.REPO = self.root
        residency.GITOPS_ROOT = self.root / "models"
        self.write("models/env.yaml", "%YAML 1.3\n---\nenv:\n  - name: MODEL_URL\n"
                   "    value: https://example.invalid\n")
        index = residency.gitops_env_overrides()
        findings = residency.endpoint_findings(
            "model", "${MODEL_URL:http://model.ns.svc.cluster.local}", "fixture", index)
        self.assertTrue(any("MODEL_URL" in finding and "R4" in finding for finding in findings))

        honey = checker("check-honeytoken-wiring.py")
        self.write("honey/binding.yaml", "%YAML 1.3\n---\nkind: RoleBinding\nsubjects:\n"
                   "  - kind: ServiceAccount\n    namespace: fixture\n    name: watched\n")
        self.assertEqual(honey.bound_subjects(self.root / "honey"), {("fixture", "watched")})

        edge = checker("check-edge-decoy-rule-parity.py")
        path = self.write("edge/tab.yaml", "kind:\tHTTPRoute\nmetadata:\n"
                          "  namespace: fixture\n  name: edge-decoys-unread\n")
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            self.assertEqual(list(edge._docs(path)), [])
        self.assertIn("not parseable YAML", errors.getvalue())

        # The eager cache and streaming fallback must consume the SAME file snapshot.
        from unittest.mock import patch

        text = "kind: Kept\n---\ninvalid: [\n"
        errors = io.StringIO()
        with patch.object(pathlib.Path, "read_text", side_effect=[text, "kind: Changed\n"]) as reads, \
                contextlib.redirect_stderr(errors):
            self.assertEqual(list(edge._docs(path)), [{"kind": "Kept"}])
        self.assertEqual(reads.call_count, 1)
        self.assertIn("not parseable YAML", errors.getvalue())

    def test_python_cache_cannot_validate_a_default_loader_invalid_input(self):
        import os
        from unittest.mock import patch
        import yaml

        cache = self.root / "namespace-cache"
        cache.mkdir()
        text = "key: value\n"
        normal = self.write("normal.yaml", text)
        invalid = self.write("invalid.yaml", "yaml.SafeLoader\0" + text)
        with patch.dict(os.environ, {gatelib.CACHE_DIR_ENV: str(cache)}):
            self.assertEqual(gatelib.load_yaml_all(normal, pure_python=True), [{"key": "value"}])
            for _ in range(2):
                with self.assertRaises(yaml.YAMLError):
                    gatelib.load_yaml_all(invalid)
                gatelib.clear()  # repeat against the persisted Python-loader entry

    def test_python_loader_preserves_deep_alias_graphs_without_exposing_cache(self):
        # Shallow YAML merge entries hide a deep alias graph from the parser's call stack.
        # SafeLoader accepts this while deepcopy can exceed its own recursion limit.
        text = "kind: ServiceAccount\nmetadata: {name: watched}\n<<:\n  - {a: &a0 [0]}\n"
        text += "".join(f"  - {{a: &a{i} [*a{i - 1}]}}\n" for i in range(1, 500))
        text += "a: *a499\n"
        path = self.write("aliases.yaml", text)
        first = gatelib.load_yaml_all(path, pure_python=True)
        value = first[0]["a"]
        for _ in range(500):
            value = value[0]
        self.assertEqual(value, 0)
        first[0]["metadata"]["name"] = "poison"
        second = gatelib.load_yaml_all(path, pure_python=True)
        self.assertEqual(second[0]["metadata"]["name"], "watched")

    def test_empty_file_is_none_not_an_error(self):
        p = self.write("empty.yaml", "")
        self.assertIsNone(gatelib.load_yaml(p))
        self.assertEqual(gatelib.load_yaml_all(p), [])

    def test_malformed_yaml_still_raises(self):
        import yaml

        p = self.write("bad.yaml", "a: [1,\nb: 2\n")
        with self.assertRaises(yaml.YAMLError):
            gatelib.load_yaml(p)
        # …and again on the second call: a raising parse must not be remembered as a success.
        with self.assertRaises(yaml.YAMLError):
            gatelib.load_yaml(p)

    def test_read_text_matches_pathlib(self):
        p = self.write("x.txt", "line\n")
        self.assertEqual(gatelib.read_text(p), p.read_text(encoding="utf-8"))

    def test_rglob_is_sorted_and_complete(self):
        self.write("d/one.yaml", "a: 1\n")
        self.write("d/e/two.yaml", "a: 2\n")
        self.write("d/three.txt", "no")
        got = list(gatelib.rglob(self.root, "*.yaml"))
        self.assertEqual(sorted(got), got, "sorted by PATH, as sorted(root.rglob(...)) is")
        self.assertEqual({p.name for p in got}, {"one.yaml", "two.yaml"})

    # --- the disk layer -------------------------------------------------------------------
    def test_disk_cache_round_trips_and_a_corrupt_blob_is_a_miss(self):
        cache = self.root / "cache"
        cache.mkdir()
        p = self.write("a.yaml", "k: v\n")
        import os

        os.environ[gatelib.CACHE_DIR_ENV] = str(cache)
        try:
            self.assertEqual(gatelib.load_yaml(p), {"k": "v"})
            blobs = list(cache.glob("*.marshal"))
            self.assertEqual(len(blobs), 1, "the parse should have been written to disk")

            gatelib.clear()
            self.assertEqual(gatelib.load_yaml(p), {"k": "v"}, "disk hit must round-trip")

            gatelib.clear()
            blobs[0].write_bytes(b"\x00not-a-blob")
            self.assertEqual(
                gatelib.load_yaml(p), {"k": "v"},
                "a corrupt blob must be a cache MISS, never a failure and never a wrong answer",
            )
        finally:
            os.environ.pop(gatelib.CACHE_DIR_ENV, None)

    def test_no_disk_writes_without_the_env_var(self):
        cache = self.root / "cache"
        cache.mkdir()
        p = self.write("a.yaml", "k: v\n")
        self.assertEqual(gatelib.load_yaml(p), {"k": "v"})
        self.assertEqual(list(cache.iterdir()), [])

    def test_a_document_marshal_cannot_carry_still_parses(self):
        # A YAML timestamp becomes a datetime, which marshal refuses. The disk layer has to
        # decline that document rather than fail the gate — and the value must still be right.
        import datetime
        import os

        cache = self.root / "cache"
        cache.mkdir()
        p = self.write("ts.yaml", "when: 2026-08-09T10:00:00Z\n")
        os.environ[gatelib.CACHE_DIR_ENV] = str(cache)
        try:
            got = gatelib.load_yaml(p)
            self.assertIsInstance(got["when"], datetime.datetime)
            self.assertEqual(list(cache.glob("*.marshal")), [])
            gatelib.clear()
            self.assertEqual(gatelib.load_yaml(p)["when"], got["when"])
        finally:
            os.environ.pop(gatelib.CACHE_DIR_ENV, None)


if __name__ == "__main__":
    unittest.main()
