#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise the manifest scanner as a process; fixture credentials are synthetic."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml

SCRIPT = Path(__file__).with_name('check-manifest-secret-literals.py')
DIGEST = "{{ sha256(join(':', ['test-purpose', request.object.data.password])) }}"


class ManifestSecretTest(unittest.TestCase):
    def scan(self, text, accepted):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'resource.yaml'
            path.write_text(text)
            result = subprocess.run([sys.executable, str(SCRIPT), directory],
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)
            self.assertNotIn('fixture-credential', result.stdout + result.stderr)

    def test_real_secrets_are_rejected_regardless_of_yaml_layout(self):
        for field in ('data', 'stringData'):
            for text in (
                f'kind: Secret\n{field}:\n  password: fixture-credential\n',
                f'kind: "Secret"\n{field}: {{password: fixture-credential}}\n',
                f'kind: ConfigMap\ndata: {{public: value}}\n---\nkind: Secret\n{field}: {{password: fixture-credential}}',
                f'kind: List\nitems:\n  - kind: Secret\n    {field}: {{password: fixture-credential}}',
            ):
                with self.subTest(field=field, text=text):
                    self.scan(text, False)

    def test_nonsecret_resources_and_secret_references_pass(self):
        for text in (
            'kind: ConfigMap\ndata: {name: Secret}\n',
            'kind: Application\nspec:\n  ignoreDifferences:\n    - kind: Secret\n      jsonPointers: [/data]\n',
            'kind: ExternalSecret\nspec:\n  data: [{secretKey: password, remoteRef: {key: source}}]\n',
            'kind: Secret\nmetadata: {name: externally-populated}\n',
        ):
            self.scan(text, True)

    def test_generated_credentials_allow_only_the_constrained_runtime_digest(self):
        for password, accepted in (
            (DIGEST, True),
            ('fixture-credential', False),
            ("{{ 'fixture-credential' }}", False),
            ("{{ request.object.data.password || 'fixture-credential' }}", False),
            ('fixture-credential' + DIGEST, False),
            (DIGEST + 'fixture-credential', False),
            ("{{ sha256('fixture-credential') }}", False),
        ):
            document = {'kind': 'ClusterPolicy', 'spec': {'rules': [{'generate': {
                'kind': 'Secret', 'data': {'type': 'kubernetes.io/basic-auth',
                                         'stringData': {'username': 'reader', 'password': password}},
            }}]}}
            with self.subTest(password=password):
                self.scan(yaml.safe_dump(document), accepted)
        # A safe generator cannot exempt a literal Secret elsewhere in the same file.
        document['spec']['rules'][0]['generate']['data']['stringData']['password'] = DIGEST
        self.scan(yaml.safe_dump(document) + '\n---\nkind: Secret\ndata: {password: fixture-credential}', False)

    def test_malformed_or_duplicate_yaml_fails_without_printing_payloads(self):
        self.scan('kind: Secret\nstringData: {password: fixture-credential\n', False)
        self.scan('kind: Secret\nstringData: {password: fixture-credential}\nstringData: {}\n', False)


if __name__ == '__main__':
    unittest.main()
