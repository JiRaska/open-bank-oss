# SPDX-License-Identifier: Apache-2.0
"""Identity-only tests; synthetic statements are NOT cryptographic evidence.

Field shapes follow slsa-github-generator v2.1.0 slsa/provenance.go and
slsa/buildtype.go, serialized with in-toto-golang v0.9.0 SLSA v0.2 types.
"""
import copy
import unittest
from release_repair_attestation import BUILDER, WORKFLOW, validate_authenticated_payload


class IdentityTests(unittest.TestCase):
    def setUp(self):
        self.bound = {'repository': 'example/project', 'tag': 'example-v1.0.0',
                      'commit': 'a' * 40, 'release_id': 7, 'workflow_revision': 'b' * 40,
                      'producerRunId': 19, 'producerRunAttempt': 2,
                      'sha256': {'example-v1.0.0.cdx.json': 'c' * 64}}
        source = {'uri': 'git+https://github.com/example/project@refs/heads/main', 'digest': {'sha1': 'b' * 40}}
        inputs = {'tag': 'example-v1.0.0', 'expected_commit': 'a' * 40,
                  'release_id': '7', 'workflow_revision': 'b' * 40, 'operation': 'generate'}
        self.statement = {'_type': 'https://in-toto.io/Statement/v0.1',
                          'predicateType': 'https://slsa.dev/provenance/v0.2',
                          'subject': [{'name': 'example-v1.0.0.cdx.json', 'digest': {'sha256': 'c' * 64}}],
                          'predicate': {'builder': {'id': BUILDER},
                            'buildType': 'https://github.com/slsa-framework/slsa-github-generator/generic@v1',
                            'materials': [source],
                            'invocation': {'configSource': source | {'entryPoint': WORKFLOW},
                              'parameters': {'event_inputs': inputs},
                              'environment': {'github_event_name': 'workflow_dispatch', 'github_ref': 'refs/heads/main',
                                'github_ref_type': 'branch', 'github_sha1': 'b' * 40,
                                'github_run_id': '19', 'github_run_attempt': '2', 'github_event_payload': {'inputs': inputs}}},
                            'metadata': {'buildInvocationID': '19-2'}}}
        self.producer = {'id': 19, 'run_attempt': 2, 'head_sha': 'b' * 40, 'head_branch': 'main',
                         'event': 'workflow_dispatch', 'path': WORKFLOW, 'repository': {'full_name': 'example/project'}}

    def test_exact_current_invocation_with_distinct_historical_source(self):
        validate_authenticated_payload(self.statement, self.bound, self.producer)

    def test_subject_name_cardinality_and_hash_are_all_required(self):
        original = self.statement['subject']
        mutations = [[], original * 2, [{'name': 'other', 'digest': original[0]['digest']}],
                     [{'name': original[0]['name'], 'digest': {'sha256': 'd' * 64}}]]
        for subject in mutations:
            with self.subTest(subject=subject), self.assertRaises(ValueError):
                validate_authenticated_payload(self.statement | {'subject': subject}, self.bound, self.producer)

    def test_declared_apply_defaults_are_empty_and_unknown_inputs_rejected(self):
        from release_repair_attestation import APPLY_INPUTS
        for location in ('parameters', 'environment'):
            statement = copy.deepcopy(self.statement)
            invocation = statement['predicate']['invocation']
            target = invocation['parameters']['event_inputs'] if location == 'parameters' else invocation['environment']['github_event_payload']['inputs']
            target.update({key: '' for key in APPLY_INPUTS})
            validate_authenticated_payload(statement, self.bound, self.producer)
            for key in (*APPLY_INPUTS, 'unknown'):
                changed = copy.deepcopy(statement)
                inv = changed['predicate']['invocation']
                inputs = inv['parameters']['event_inputs'] if location == 'parameters' else inv['environment']['github_event_payload']['inputs']
                inputs[key] = 'unapproved'
                with self.subTest(location=location, key=key), self.assertRaises(ValueError):
                    validate_authenticated_payload(changed, self.bound, self.producer)

    def test_all_authenticated_identity_fields_fail_closed(self):
        paths = [('_type',), ('predicateType',), ('predicate', 'builder', 'id'), ('predicate', 'buildType'),
                 ('predicate', 'invocation', 'configSource', 'uri'), ('predicate', 'invocation', 'configSource', 'entryPoint'),
                 ('predicate', 'invocation', 'configSource', 'digest', 'sha1'),
                 ('predicate', 'metadata', 'buildInvocationID')]
        paths += [('predicate', 'invocation', 'environment', key) for key in
                  ['github_event_name', 'github_ref', 'github_ref_type', 'github_sha1', 'github_run_id', 'github_run_attempt']]
        for path in paths:
            for operation in ['wrong', 'missing']:
                s = copy.deepcopy(self.statement); target = s
                for part in path[:-1]: target = target[part]
                if operation == 'wrong': target[path[-1]] = 'wrong'
                else: del target[path[-1]]
                with self.subTest(path=path, operation=operation), self.assertRaises(ValueError):
                    validate_authenticated_payload(s, self.bound, self.producer)

    def test_wrong_dispatch_inputs_rejected(self):
        for key in ['tag', 'expected_commit', 'release_id', 'workflow_revision', 'operation']:
            s = copy.deepcopy(self.statement)
            inv = s['predicate']['invocation']
            inv['parameters']['event_inputs'] = dict(inv['parameters']['event_inputs'])
            inv['parameters']['event_inputs'][key] = 'wrong'
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_authenticated_payload(s, self.bound, self.producer)

    def test_event_payload_inputs_independently_required(self):
        s = copy.deepcopy(self.statement)
        inv = s['predicate']['invocation']
        inv['environment']['github_event_payload']['inputs'] = dict(inv['environment']['github_event_payload']['inputs'])
        inv['environment']['github_event_payload']['inputs']['tag'] = 'other-v1.0.0'
        with self.assertRaises(ValueError):
            validate_authenticated_payload(s, self.bound, self.producer)

    def test_canonical_producer_wrong_run_attempt_source_path_repository(self):
        for key in ['id', 'run_attempt', 'head_sha', 'head_branch', 'event', 'path', 'repository']:
            p = self.producer | {key: 'wrong'}
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_authenticated_payload(self.statement, self.bound, p)

    def test_malformed_nested_authenticated_structures_fail_closed(self):
        paths = [('predicate',), ('predicate', 'invocation'), ('predicate', 'invocation', 'environment'),
                 ('predicate', 'invocation', 'parameters'), ('predicate', 'builder')]
        for path in paths:
            for invalid in [None, [], 'scalar']:
                s = copy.deepcopy(self.statement); node = s
                for key in path[:-1]: node = node[key]
                node[path[-1]] = invalid
                with self.subTest(path=path, invalid=invalid), self.assertRaises(ValueError):
                    validate_authenticated_payload(s, self.bound, self.producer)
        for invalid in [None, [], 'scalar']:
            with self.subTest(root=invalid), self.assertRaises(ValueError):
                validate_authenticated_payload(invalid, self.bound, self.producer)
            with self.subTest(repository=invalid), self.assertRaises(ValueError):
                validate_authenticated_payload(self.statement, self.bound, self.producer | {'repository': invalid})

    def test_unknown_v1_schema_and_historical_material_not_relabelled(self):
        s = copy.deepcopy(self.statement); s['predicateType'] = 'https://slsa.dev/provenance/v1'
        with self.assertRaises(ValueError): validate_authenticated_payload(s, self.bound, self.producer)
        s = copy.deepcopy(self.statement); s['predicate']['materials'][0]['digest']['sha1'] = 'a' * 40
        with self.assertRaises(ValueError): validate_authenticated_payload(s, self.bound, self.producer)


if __name__ == '__main__':
    unittest.main()
