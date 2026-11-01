import copy
import importlib.util
import os
import subprocess
import sys
import unittest
from pathlib import Path

import schemathesis

CHECKOUT_ROOT = Path(__file__).resolve().parents[3]
REPO_ROOT = Path(os.environ.get('OPENBANK_REPO_ROOT', CHECKOUT_ROOT))
HOOK_FILE = CHECKOUT_ROOT / '.github/scripts/fuzz_response_isolation.py'
spec = importlib.util.spec_from_file_location('response_isolation_test_hook', HOOK_FILE)
hook_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook_module)


def schema_with(responses, component_responses):
    return {
        'openapi': '3.0.3',
        'info': {'title': 'response-ref-test', 'version': '1'},
        'paths': {
            '/one': {'post': {'responses': responses[0]}},
            '/two': {'post': {'responses': responses[1]}},
        },
        'components': {'responses': component_responses},
    }


class ResponseIsolationTests(unittest.TestCase):
    def test_shared_response_alias_gets_distinct_operation_objects(self):
        ref = {'$ref': '#/components/responses/Shared'}
        raw = schema_with([{'202': ref}, {'202': ref}], {'Shared': {'description': 'accepted'}})
        original = copy.deepcopy(raw['components']['responses']['Shared'])
        hook_module.before_load_schema(None, raw)
        one = raw['paths']['/one']['post']['responses']['202']
        two = raw['paths']['/two']['post']['responses']['202']
        self.assertIsNot(one, two)
        one['links'] = {'OnlyOne': {'operationId': 'one'}}
        self.assertNotIn('links', two)
        self.assertEqual(raw['components']['responses']['Shared'], original)

    def test_shared_parent_responses_mapping_is_split_first(self):
        responses = {'202': {'$ref': '#/components/responses/Shared'}}
        raw = schema_with([responses, responses], {'Shared': {'description': 'accepted'}})
        hook_module.before_load_schema(None, raw)
        one = raw['paths']['/one']['post']['responses']
        two = raw['paths']['/two']['post']['responses']
        self.assertIsNot(one, two)
        self.assertIsNot(one['202'], two['202'])
        one['202']['links'] = {'OnlyOne': {'operationId': 'one'}}
        self.assertNotIn('links', two['202'])

    def test_percent_encoded_fragment_and_escaped_token_resolve(self):
        ref = {'$ref': '#/components%2Fresponses%2FShared%7E1Response'}
        raw = schema_with([{'202': copy.deepcopy(ref)}, {'202': copy.deepcopy(ref)}], {'Shared/Response': {'description': 'accepted'}})
        hook_module.before_load_schema(None, raw)
        one = raw['paths']['/one']['post']['responses']['202']
        two = raw['paths']['/two']['post']['responses']['202']
        self.assertEqual(one, {'description': 'accepted'})
        self.assertEqual(two, {'description': 'accepted'})
        self.assertIsNot(one, two)

    def test_equivalent_canonical_and_percent_refs_are_grouped(self):
        canonical = {'$ref': '#/components/responses/Shared~1Response'}
        encoded = {'$ref': '#/components%2Fresponses%2FShared%7E1Response'}
        raw = schema_with([{'202': canonical}, {'202': encoded}], {'Shared/Response': {'description': 'accepted'}})
        hook_module.before_load_schema(None, raw)
        one = raw['paths']['/one']['post']['responses']['202']
        two = raw['paths']['/two']['post']['responses']['202']
        self.assertEqual(one, {'description': 'accepted'})
        self.assertEqual(two, {'description': 'accepted'})
        self.assertIsNot(one, two)

    def test_malformed_escape_is_left_for_validator(self):
        ref = {'$ref': '#/components/responses/Bad~2Escape'}
        raw = schema_with([{'202': copy.deepcopy(ref)}, {'202': copy.deepcopy(ref)}], {'Bad~2Escape': {'description': 'untouched'}})
        original = copy.deepcopy(raw)
        hook_module.before_load_schema(None, raw)
        self.assertEqual(raw, original)

    def test_invalid_percent_escape_is_left_for_validator(self):
        ref = {'$ref': '#/components%2Gresponses/Shared'}
        raw = schema_with([{'202': copy.deepcopy(ref)}, {'202': copy.deepcopy(ref)}], {'Shared': {'description': 'untouched'}})
        original = copy.deepcopy(raw)
        hook_module.before_load_schema(None, raw)
        self.assertEqual(raw, original)

    def test_external_reference_is_left_for_validator(self):
        ref = {'$ref': 'responses.yaml#/Shared'}
        raw = schema_with([{'202': copy.deepcopy(ref)}, {'202': copy.deepcopy(ref)}], {})
        original = copy.deepcopy(raw)
        hook_module.before_load_schema(None, raw)
        self.assertEqual(raw, original)

    def test_chained_component_reference_is_left_for_validator(self):
        ref = {'$ref': '#/components/responses/Alias'}
        raw = schema_with(
            [{'202': copy.deepcopy(ref)}, {'202': copy.deepcopy(ref)}],
            {'Alias': {'$ref': '#/components/responses/Target'}, 'Target': {'description': 'untouched'}},
        )
        original = copy.deepcopy(raw)
        hook_module.before_load_schema(None, raw)
        self.assertEqual(raw, original)

    def test_reference_siblings_are_left_for_validator(self):
        ref = {'$ref': '#/components/responses/Shared', 'description': 'override'}
        raw = schema_with([{'202': copy.deepcopy(ref)}, {'202': copy.deepcopy(ref)}], {'Shared': {'description': 'accepted'}})
        original = copy.deepcopy(raw)
        hook_module.before_load_schema(None, raw)
        self.assertEqual(raw, original)

    def test_aliased_operation_objects_get_distinct_response_sites(self):
        operation = {'responses': {'202': {'$ref': '#/components/responses/Shared'}}}
        raw = schema_with([{}, {}], {'Shared': {'description': 'accepted'}})
        raw['paths']['/one']['post'] = operation
        raw['paths']['/two']['post'] = operation
        hook_module.before_load_schema(None, raw)
        one = raw['paths']['/one']['post']['responses']['202']
        two = raw['paths']['/two']['post']['responses']['202']
        self.assertIsNot(raw['paths']['/one']['post'], raw['paths']['/two']['post'])
        self.assertIsNot(one, two)
        one['links'] = {'OnlyOne': {'operationId': 'one'}}
        self.assertNotIn('links', two)

    def test_aliased_path_items_get_distinct_response_sites(self):
        path_item = {'post': {'responses': {'202': {'$ref': '#/components/responses/Shared'}}}}
        raw = {
            'openapi': '3.0.3', 'info': {'title': 'response-ref-test', 'version': '1'},
            'paths': {'/one': path_item, '/two': path_item},
            'components': {'responses': {'Shared': {'description': 'accepted'}}},
        }
        hook_module.before_load_schema(None, raw)
        one_path = raw['paths']['/one']
        two_path = raw['paths']['/two']
        one = one_path['post']['responses']['202']
        two = two_path['post']['responses']['202']
        self.assertIsNot(one_path, two_path)
        self.assertIsNot(one, two)
        one['links'] = {'OnlyOne': {'operationId': 'one'}}
        self.assertNotIn('links', two)

    def test_non_mapping_paths_are_left_untouched(self):
        raw = {'openapi': '3.0.3', 'paths': []}
        original = copy.deepcopy(raw)
        hook_module.before_load_schema(None, raw)
        self.assertEqual(raw, original)

    def test_sca_operation_set_and_state_machine(self):
        from schemathesis.config import SchemathesisConfig
        path = REPO_ROOT / 'openbank-sca-service/src/main/resources/openapi.yaml'
        config = SchemathesisConfig.discover()
        schema = schemathesis.openapi.from_path(path, config=config)
        operations = [(op.method.lower(), op.path) for op in (item.ok() for item in schema.get_all_operations())]
        self.assertEqual(len(operations), 12)
        self.assertEqual(len(set(operations)), 12)
        schema._build_state_machine(error_feedback=None, link_calibration=None, extra_data_source=None)
        by_identity = {(op.method.lower(), op.path): op for item in schema.get_all_operations() if (op := item.ok())}
        enroll = by_identity[('post', '/api/v1/sca/parties/{partyId}/devices')]
        consume = by_identity[('post', '/api/v1/sca/challenges/{id}/consume')]
        self.assertIn('DeleteDevice', enroll.responses.get('202').definition.get('links', {}))
        self.assertNotIn('DeleteDevice', consume.responses.get('202').definition.get('links', {}))
        self.assertNotIn('links', schema.raw_schema['components']['responses']['PendingApproval'])

    def test_extension_env_loads_hook_before_schema_load(self):
        code = r'''
import os
from pathlib import Path
import schemathesis
from schemathesis.config import SchemathesisConfig

root = Path(os.environ["OPENBANK_REPO_ROOT"])
config = SchemathesisConfig.discover()
schema = schemathesis.openapi.from_path(
    root / "openbank-sca-service/src/main/resources/openapi.yaml", config=config
)
operations = [result.ok() for result in schema.get_all_operations()]
assert len(operations) == 12
schema._build_state_machine(error_feedback=None, link_calibration=None, extra_data_source=None)
print("SCHEMATHESIS_HOOK_STATE_MACHINE_OK", len(operations))
'''
        env = os.environ.copy()
        env['OPENBANK_REPO_ROOT'] = str(REPO_ROOT)
        env['SCHEMATHESIS_HOOKS'] = str(HOOK_FILE.resolve())
        result = subprocess.run(
            [sys.executable, '-c', code], env=env, text=True, capture_output=True, check=False
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('SCHEMATHESIS_HOOK_STATE_MACHINE_OK 12', result.stdout)


if __name__ == '__main__':
    unittest.main()
