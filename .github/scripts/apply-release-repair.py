#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Publish only an independently verified, immutable prior no-apply generation."""
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess

from release_evidence_assets import expected_release_assets
from release_evidence_repair import apply, binding_digest
from release_repair_api import AppendOnlyGitHub, require_main_inclusion
from release_repair_attestation import WORKFLOW
from release_repair_crypto import verify_bundle

# Fixed trust closure; a changed implementation requires a fresh no-apply run.
TRUSTED_PATHS = (
    'openbank-libs/governance/rules.yaml', 'SECURITY.md', 'release-please-config.json', WORKFLOW, '.github/scripts/apply-release-repair.py',
    '.github/scripts/prepare-release-repair.py', '.github/scripts/verify-release-repair.py',
    '.github/scripts/prepare-release-evidence.py', '.github/scripts/build-release-evidence.sh',
    '.github/scripts/release_support_period.py', '.github/scripts/release_evidence_assets.py', '.github/scripts/release_evidence_repair.py',
    '.github/scripts/release_repair_api.py', '.github/scripts/release_repair_attestation.py',
    '.github/scripts/release_repair_crypto.py', '.github/scripts/verify-release-evidence-manifest.py',
)


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def prior_run(api, run_id, attempt, current_run, prior_sha, current_sha):
    if any(type(v) is not int or v <= 0 for v in (run_id, attempt, current_run)) or any(re.fullmatch(r'[0-9a-f]{40}', value) is None for value in (prior_sha, current_sha)):
        raise ValueError('invalid explicit producer identity')
    if run_id == current_run:
        raise ValueError('apply requires a different completed generation run')
    producer = api.get(f'actions/runs/{run_id}/attempts/{attempt}')
    expected = {'id': run_id, 'run_attempt': attempt, 'head_sha': prior_sha,
                'head_branch': 'main', 'event': 'workflow_dispatch', 'path': WORKFLOW,
                'status': 'completed', 'conclusion': 'success'}
    if any(type(producer.get(k)) is not type(v) or producer.get(k) != v for k, v in expected.items()) or producer['repository']['full_name'] != api.repository:
        raise ValueError('prior successful generation identity mismatch')
    subprocess.run(['git', 'merge-base', '--is-ancestor', prior_sha, current_sha], check=True)
    for path in TRUSTED_PATHS:
        old = subprocess.check_output(['git', 'ls-tree', prior_sha, '--', path])
        new = subprocess.check_output(['git', 'ls-tree', current_sha, '--', path])
        if not re.fullmatch(rb'100(?:644|755) blob [0-9a-f]{40}\t[^\n]+\n', old) or old != new:
            raise ValueError('trusted repair implementation changed; generate again')
    jobs = api.pages(f'actions/runs/{run_id}/attempts/{attempt}/jobs', 'jobs')
    selected = [j for j in jobs if j['name'] == 'verify']
    if len(selected) != 1 or any(type(selected[0].get(k)) is not type(v) or selected[0].get(k) != v for k, v in {
            'run_id': run_id, 'run_attempt': attempt, 'status': 'completed', 'conclusion': 'success'}.items()):
        raise ValueError('prior successful verification job missing')
    return producer, jobs


def main():
    env = os.environ; current_sha = env['REPAIR_WORKFLOW_REVISION']
    if env['GITHUB_EVENT_NAME'] != 'workflow_dispatch' or env['GITHUB_REF'] != 'refs/heads/main' or env['GITHUB_WORKFLOW_SHA'] != current_sha or subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() != current_sha:
        raise ValueError('current apply invocation is not the approved main revision')
    if re.fullmatch(r'[0-9a-f]{64}', env['REPAIR_VERIFIED_ARTIFACT_SHA256']) is None:
        raise ValueError('explicit archive SHA256 required')
    source = load('repair_apply_source', 'prepare-release-evidence.py')
    verify = load('repair_apply_verify', 'verify-release-repair.py')
    prepare = load('repair_apply_prepare', 'prepare-release-repair.py')
    api = AppendOnlyGitHub(env['GITHUB_REPOSITORY'], lambda tag: source.remote_commit(Path.cwd(), tag), int(env['REPAIR_RELEASE_ID']))
    require_main_inclusion(api, env['REPAIR_COMMIT'], current_sha)
    run_id = int(env['REPAIR_PRIOR_RUN_ID']); attempt = int(env['REPAIR_PRIOR_ATTEMPT'])
    producer, jobs = prior_run(api, run_id, attempt, int(env['GITHUB_RUN_ID']), env['REPAIR_PRODUCER_REVISION'], current_sha)
    names = expected_release_assets(env['REPAIR_TAG']) | {'repair-binding.json', 'verification.json'}
    # A lane-specific acceptance ceiling, not an existing fleet test/time budget.
    # All retained members are JSON/signatures, never application layers or JARs.
    maximum = len(names) * verify.PROVENANCE_LIMIT + verify.ZIP_OVERHEAD_LIMIT
    metadata, raw = verify.archive(api, int(env['REPAIR_VERIFIED_ARTIFACT_ID']), run_id, 'release-repair-verified', maximum)
    if metadata['digest'] != 'sha256:' + env['REPAIR_VERIFIED_ARTIFACT_SHA256']:
        raise ValueError('approved immutable artifact digest mismatch')
    files = verify.members(raw, names, maximum_member=verify.PROVENANCE_LIMIT)
    bound = json.loads(files.pop('repair-binding.json')); receipt = json.loads(files.pop('verification.json'))
    if (bound['producerRunId'] != run_id or bound['producerRunAttempt'] != attempt
            or bound['repository'] != api.repository or bound['workflow_revision'] != env['REPAIR_PRODUCER_REVISION']
            or bound['tag'] != env['REPAIR_TAG'] or bound['commit'] != env['REPAIR_COMMIT']
            or bound['release_id'] != api.release_id or bound['originalAssets'] != []
            or receipt['bindingSha256'] != binding_digest(bound)
            or receipt['published'] is not False or receipt['claim'] != 'verified-current-repair-generation'):
        raise ValueError('prior immutable bundle identity mismatch')
    official = bound['tag'] + '.cdx.json.intoto.jsonl'
    provenance, provenance_archive = verify.archive(api, receipt['provenanceArtifactId'], run_id, official,
        verify.PROVENANCE_LIMIT + verify.ZIP_OVERHEAD_LIMIT)
    selected = [j for j in jobs if j['id'] == receipt['generatorJobId']]
    if len(selected) != 1:
        raise ValueError('original generator job missing')
    tools = Path(env['RUNNER_TEMP']) / 'repair-apply-tools'
    cosign = prepare.tool(tools, 'cosign', 'https://github.com/sigstore/cosign/releases/download/v2.4.3/cosign-linux-amd64',
        'caaad125acef1cb81d58dcdc454a1e429d09a750d1e9e2b3ed1aed8964454708')
    verifier = prepare.tool(tools, 'slsa-verifier', 'https://github.com/slsa-framework/slsa-verifier/releases/download/v2.7.1/slsa-verifier-linux-amd64',
        '946dbec729094195e88ef78e1734324a27869f03e2c6bd2f61cbc06bd5350339')
    def authenticate(binding, bundle):
        return verify_bundle(binding, bundle, producer, cosign=cosign, slsa_verifier=verifier,
            artifact=provenance, artifact_archive=provenance_archive, generator_job=selected[0],
            expected_artifact_id=receipt['provenanceArtifactId'], expected_generator_job_id=receipt['generatorJobId'])
    hashes = apply(api, bound, files, authenticate, resume=True)
    Path('repair-published.json').write_text(json.dumps({'bindingSha256': binding_digest(bound),
        'applierWorkflowRevision': current_sha, 'producerWorkflowRevision': bound['workflow_revision'],
        'verifiedArtifactId': metadata['id'], 'verifiedArtifactDigest': metadata['digest'], 'committedSha256': hashes}, sort_keys=True))


if __name__ == '__main__':
    main()
