# SPDX-License-Identifier: Apache-2.0
"""Report safety tests. These fabricated XML fixtures are NOT demo evidence."""
import os
from pathlib import Path
import tempfile
import time
import unittest

import pension_demo as demo


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.xml = self.root / 'TEST-example.xml'
        self.started = time.time() - 1

    def write(self, inner='', attributes=''):
        self.xml.write_text(f'<testsuite name="example" tests="1" {attributes}>'
                            f'<testcase name="synthetic case">{inner}</testcase></testsuite>')

    def test_missing_and_stale_results_are_rejected(self):
        with self.assertRaises(ValueError):
            demo.read_suite(self.xml, self.started, 1)
        self.write()
        os.utime(self.xml, (0, 0))
        with self.assertRaises(ValueError):
            demo.read_suite(self.xml, self.started, 1)

    def test_skips_failures_and_incomplete_suites_are_rejected(self):
        for inner in ('<skipped/>', '<failure/>', '<error/>'):
            with self.subTest(inner=inner):
                self.write(inner)
                with self.assertRaises(ValueError):
                    demo.read_suite(self.xml, self.started, 1)
        self.write(attributes='skipped="1"')
        with self.assertRaises(ValueError):
            demo.read_suite(self.xml, self.started, 1)
        self.write()
        with self.assertRaises(ValueError):
            demo.read_suite(self.xml, self.started, 2)
        self.xml.write_text('<testsuite name="example" tests="0"/>')
        with self.assertRaises(ValueError):
            demo.read_suite(self.xml, self.started, 1)

    def test_failed_process_cannot_reuse_green_xml(self):
        self.write()
        phase = demo.Phase('test', 'test', (), (('.', 'example', 1),))
        with self.assertRaises(ValueError):
            demo.collect(self.root, phase, self.started, 1, self.root)

    def test_fresh_suite_is_copied_and_wrong_suite_rejected(self):
        self.write()
        evidence = self.root / 'evidence'
        evidence.mkdir()
        phase = demo.Phase('test', 'test', (), (('.', 'example', 1),))
        result = demo.collect(self.root, phase, self.started, 0, evidence)
        self.assertEqual(result['status'], 'PASSED')
        self.assertTrue((evidence / self.xml.name).is_file())
        self.xml.write_text(self.xml.read_text().replace('name="example"', 'name="wrong"'))
        with self.assertRaises(ValueError):
            demo.collect(self.root, phase, self.started, 0, evidence)

    def test_company_label_requires_runtime_proof(self):
        alpha = 'PENSION_DEMO_CONTEXT|alpha|00000000-0000-4000-8000-000000000001|openbank_pension_demo_alpha'
        self.assertEqual(demo.verify_context(alpha, 'alpha')['company'], 'alpha')
        for output in ('', alpha, alpha.replace('_alpha', '_beta')):
            with self.subTest(output=output), self.assertRaises(ValueError):
                demo.verify_context(output, 'beta')

    def test_plan_and_html_do_not_invent_success_or_render_markup(self):
        report = dict(status='NOT_RUN', revision='synthetic', dirty=True, createdAt='now',
                      phases=[dict(title='<script>', status='NOT_RUN',
                                   suites=[dict(tests=['<script>alert(1)</script>'])])])
        demo.render(report, self.root)
        page = (self.root / 'index.html').read_text()
        self.assertNotIn('<script>', page)
        self.assertIn('&lt;script&gt;', page)
        self.assertNotIn('DEMO_PROOFS_PASSED', page)


if __name__ == '__main__':
    unittest.main()
