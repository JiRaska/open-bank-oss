# SPDX-License-Identifier: Apache-2.0
"""Falsify source/content binding before any Pact Broker publication."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPERS = Path(__file__).parent.resolve() / 'service-ci-steps'
SERVICE = 'openbank-example-service'


class ConsumerPactSourceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        self.env.update(GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_NOSYSTEM='1', SERVICE=SERVICE,
                        SERVICE_CI_HELPERS=str(HELPERS), GITHUB_OUTPUT=str(self.root / 'output'),
                        RUNNER_TEMP=str(self.root), GITHUB_SHA='f' * 40, BRANCH='main',
                        GITHUB_SERVER_URL='https://github.com', GITHUB_REPOSITORY='example/repo',
                        GITHUB_RUN_ID='1', PACT_BROKER_URL='https://broker.invalid',
                        RESULT=str(self.root / 'published.json'))
        self.git('init', '-q', '-b', 'main')
        self.git('commit', '-q', '--allow-empty', '-m', 'source')
        self.source = self.git('rev-parse', 'HEAD').strip()
        self.git('remote', 'add', 'origin', str(self.root))
        (self.root / 'pacts').mkdir()
        self.pact = self.root / 'pacts' / (SERVICE + '-openbank-provider-service.json')
        self.pact.write_text(json.dumps(dict(consumer=dict(name=SERVICE), provider=dict(name='openbank-provider-service'), interactions=[])))
        self.run_helper('consumer-pact-handoff.sh', 'collect')
        self.env['EXPECTED_CONSUMER_SOURCE'] = self.source
        (self.root / 'pact-broker-post.sh').write_text('cp "$2" "$RESULT"\nprintf "{}" > "$3"\necho 200\n')

    def git(self, *args):
        return subprocess.check_output(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false',
                                        '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                                        '-C', str(self.root), *args], env=self.env, text=True, stderr=subprocess.DEVNULL)

    def run_helper(self, name, *args, ok=True):
        result = subprocess.run(['bash', str(HELPERS / name), *args], cwd=self.root, env=self.env,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, ok, result.stderr)
        return result

    def manifest(self, change):
        path = self.root / 'pact-handoff/manifest.json'
        value = json.loads(path.read_text())
        change(value)
        path.write_text(json.dumps(value))

    def publish(self):
        self.run_helper('stage-handed-off-pacts.sh')
        self.run_helper('publish-consumer-pacts.sh')
        return json.loads((self.root / 'published.json').read_text())

    def test_workflow_supplies_handoff_identity_to_stage(self):
        import yaml
        workflow = yaml.safe_load((HELPERS.parents[1] / 'workflows/_service-ci.yml').read_text())
        contract = workflow['jobs']['contract']
        stage = next(step for step in contract['steps'] if step.get('name') == 'Stage handed-off consumer pacts into pacts/')
        self.assertEqual(stage['env']['SERVICE'], '${{ inputs.service }}')
        self.assertEqual(contract['env']['EXPECTED_CONSUMER_SOURCE'], '${{ needs.build.outputs.consumer_source }}')
        self.assertEqual(workflow['jobs']['build']['outputs']['consumer_source'], '${{ steps.consumer_pacts.outputs.source }}')

    def test_historical_source_is_not_caller_version(self):
        self.assertEqual(self.publish()['pacticipantVersionNumber'], self.source)

    def test_ordinary_main_source(self):
        self.env['GITHUB_SHA'] = self.source
        self.assertEqual(self.publish()['pacticipantVersionNumber'], self.source)

    def test_provider_overlay_keeps_consumer_build_source(self):
        self.git('commit', '-q', '--allow-empty', '-m', 'provider overlay source')
        self.assertNotEqual(self.git('rev-parse', 'HEAD').strip(), self.source)
        self.assertEqual(self.publish()['pacticipantVersionNumber'], self.source)

    def test_wrong_service_fails(self):
        self.manifest(lambda d: d.update(service='openbank-other-service'))
        self.run_helper('stage-handed-off-pacts.sh', ok=False)

    def test_missing_or_malformed_source_fails(self):
        for value in ['', 'not-a-sha', 'a' * 40]:
            self.manifest(lambda d: d.update(source=value))
            self.run_helper('stage-handed-off-pacts.sh', ok=False)

    def test_source_off_main_fails_even_if_expected(self):
        self.git('checkout', '-q', '-b', 'other')
        self.git('commit', '-q', '--allow-empty', '-m', 'not main')
        source = self.git('rev-parse', 'HEAD').strip()
        self.manifest(lambda d: d.update(source=source))
        self.env['EXPECTED_CONSUMER_SOURCE'] = source
        self.run_helper('stage-handed-off-pacts.sh', ok=False)

    def test_tampered_handoff_hash_fails(self):
        path = self.root / 'pact-handoff' / self.pact.name
        value = json.loads(path.read_text())
        value['interactions'] = [dict(description='changed after build')]
        path.write_text(json.dumps(value))
        self.run_helper('stage-handed-off-pacts.sh', ok=False)

    def test_extra_pact_file_fails(self):
        extra = self.root / 'pact-handoff' / (SERVICE + '-extra.json')
        extra.write_bytes(self.pact.read_bytes())
        self.run_helper('stage-handed-off-pacts.sh', ok=False)

    def test_count_mismatch_fails(self):
        (self.root / 'pact-handoff/manifest.txt').write_text('pact-count=0\n')
        self.run_helper('stage-handed-off-pacts.sh', ok=False)

    def test_missing_manifest_fails(self):
        (self.root / 'pact-handoff/manifest.json').unlink()
        self.run_helper('stage-handed-off-pacts.sh', ok=False)

    def test_publish_revalidates_staged_bytes_before_http(self):
        self.run_helper('stage-handed-off-pacts.sh')
        self.pact.write_text('{}')
        self.run_helper('publish-consumer-pacts.sh', ok=False)
        self.assertFalse((self.root / 'published.json').exists())

    def test_zero_pacts_is_bound_and_does_not_publish(self):
        self.pact.unlink()
        (self.root / 'pact-handoff' / self.pact.name).unlink()
        self.run_helper('consumer-pact-handoff.sh', 'collect')
        self.run_helper('stage-handed-off-pacts.sh')
        self.run_helper('publish-consumer-pacts.sh')
        self.assertFalse((self.root / 'published.json').exists())


if __name__ == '__main__':
    unittest.main()
