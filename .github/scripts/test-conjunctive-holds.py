"""Offline regression proof; uses only standard library and existing gate PyYAML."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import yaml

spec = importlib.util.spec_from_file_location('merger', Path(__file__).with_name('merge-deploy-holds.py'))
merger = importlib.util.module_from_spec(spec)
spec.loader.exec_module(merger)
gate = merger.gate
SERVICE = 'openbank-sanctions-service'
A = {'issue': '#1', 'reason': 'owner A: preserve\nraw', 'release_condition': 'A explicitly approves'}
B = {'issue': '#2', 'reason': 'owner B', 'release_condition': 'B explicitly approves'}
HEAD = 'a' * 40


def document(item):
    return yaml.safe_dump({'unrelated': {'keep': True}, 'deploy_holds': {'services': {SERVICE: item}}})


class ConjunctionTests(unittest.TestCase):
    def test_legacy_and_raw_metadata_union(self):
        self.assertEqual(gate.holds(document(A)), {SERVICE: A})
        merged = merger.merge(document(A), document(B), 22, HEAD, [SERVICE])
        values = gate.constraints(gate.holds(merged)[SERVICE])
        self.assertEqual([{f: v[f] for f in gate.FIELDS} for v in values], [A, B])
        self.assertEqual(values[1]['origins'], [{'pr': 22, 'head': HEAD}])
        self.assertTrue(yaml.safe_load(merged)['unrelated']['keep'])
        self.assertEqual(merger.merge(merged, document(B), 22, HEAD, [SERVICE]), merged)

    def test_splice_preserves_unrelated_bytes_and_values(self):
        prefix = ("# unrelated governance comment\n"
                  "owner: &owner 'owner A: preserve' # retain quote and anchor\n"
                  "unrelated_scalar: 0x10\n"
                  "multiline: |-\n  literal: keep # text\n  second line\n"
                  "deploy_holds:\n  note: >-\n    unrelated folded\n    text\n")
        body = ("  services: # hold-map header comment\n"
                "    # inside-service comment is intentionally replaced\n"
                "    openbank-sanctions-service:\n"
                "      issue: '#1'\n      reason: *owner\n"
                "      release_condition: A explicitly approves\n")
        suffix = ("\n  # adjacent policy comment must remain\n"
                  "  other_policy: 'verbatim' # unrelated sibling\n"
                  "# trailing governance section\n"
                  "elsewhere: {flag: true, text: 'spacing stays'}\n"
                  "alias_copy: *owner\n")
        text = prefix + body + suffix
        result = merger.merge(text, document(B), 22, HEAD, [SERVICE])
        self.assertTrue(result.startswith(prefix))
        self.assertTrue(result.endswith(suffix))
        self.assertIn('services: # hold-map header comment', result)
        self.assertNotIn('inside-service comment', result)
        before, after = yaml.safe_load(text), yaml.safe_load(result)
        before['deploy_holds']['services'] = {}
        after['deploy_holds']['services'] = {}
        self.assertEqual(before, after)

    def test_ambiguous_splice_refused(self):
        for text in ("deploy_holds: {services: {}}\n",
                     "deploy_holds:\n  services: &holds {}\n",
                     "deploy_holds:\n  services: {}\n  services: {}\n"):
            with self.subTest(text=text), self.assertRaises(gate.InvalidHold):
                merger.merge(text, document(B), 22, HEAD, [SERVICE])

    def test_nonoverlap_donor_conditions_are_also_preserved(self):
        other = 'openbank-ledger-service'
        donor = yaml.safe_dump({'deploy_holds': {'services': {SERVICE: B, other: A}}})
        merged = gate.holds(merger.merge(document(A), donor, 22, HEAD, [SERVICE]))
        self.assertIn(other, merged)
        self.assertEqual(gate.constraints(merged[other])[0]['release_condition'], A['release_condition'])

    def test_union_algebra_and_distinct_origins(self):
        a, b = {SERVICE: A}, {SERVICE: B}
        self.assertEqual(gate.union_holds(a, b), gate.union_holds(b, a))
        self.assertEqual(gate.union_holds(a, a), a)
        self.assertEqual(gate.union_holds(a, gate.union_holds(a, b)), gate.union_holds(a, b))
        first = merger.merge(document(A), document(A), 22, HEAD, [SERVICE])
        second = merger.merge(first, document(A), 23, 'b' * 40, [SERVICE])
        self.assertEqual(len(gate.constraints(gate.holds(second)[SERVICE])), 1)
        self.assertEqual(len(gate.constraints(gate.holds(second)[SERVICE])[0]['origins']), 2)

    def test_malformed_refused(self):
        for item in ({'all_of': []}, {'all_of': [A], **A}, {'all_of': [A, A]},
                     {'all_of': [{**A, 'resolved': True}]}, {'all_of': [{**A, 'origins': []}]},
                     {'all_of': [{**A, 'origins': [{'pr': True, 'head': HEAD}]}]}):
            with self.subTest(item=item), self.assertRaises(gate.InvalidHold):
                gate.holds(document(item))
        with self.assertRaises(gate.InvalidHold):
            gate.holds(document(A).replace('    openbank-sanctions-service:',
                '    openbank-sanctions-service: {}\n    openbank-sanctions-service:'))
        with self.assertRaises(gate.InvalidHold):
            merger.merge(document(A), 'deploy_holds: {services: {}}', 22, HEAD, [SERVICE])
        with self.assertRaises(gate.InvalidHold):
            merger.merge(document(A), document(B), 22, 'short', [SERVICE])

    def test_removal_and_image_change(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            rule = root / gate.RULES
            rule.parent.mkdir(parents=True)
            module = root / SERVICE
            module.mkdir()
            (module / 'version.txt').write_text('1.0.0')
            manifest = root / gate.GITOPS / 'pod.yaml'
            manifest.parent.mkdir(parents=True)
            before = f'kind: Pod\nspec:\n  containers:\n    - image: registry/{SERVICE}:old\n'
            after = before.replace(':old', ':new')
            manifest.write_text(after)
            base_rule = document({'all_of': [A, B]})
            diff = b'M\0' + str(manifest.relative_to(root)).encode() + b'\0'
            def at_base(_root, _base, path):
                return base_rule if path == gate.RULES else before
            for current in (document(A), 'deploy_holds: {services: {}}'):
                rule.write_text(current)
                with patch.object(gate, 'git', return_value=diff), patch.object(gate, 'at_base', side_effect=at_base), contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(gate.check(root, 'base'), 1)
                    self.assertIn('A explicitly approves', output.getvalue())
                    self.assertIn('B explicitly approves', output.getvalue())
            # Partial removal remains filtered even in a subsequent reconcile.
            rule.write_text(document(A))
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(gate.filter_tags(root, '{"'+SERVICE+'":"new"}'), {})
            # Only a fresh successor after last hold removal may change the pin.
            rule.write_text('deploy_holds: {services: {}}')
            base_rule = 'deploy_holds: {services: {}}'
            with patch.object(gate, 'git', return_value=diff), patch.object(gate, 'at_base', side_effect=at_base), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(gate.check(root, 'released-base'), 0)


if __name__ == '__main__':
    unittest.main()
