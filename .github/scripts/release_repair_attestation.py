# SPDX-License-Identifier: Apache-2.0
"""Strict identity checks AFTER official SLSA cryptographic verification.

This module has no CLI and does not authenticate arbitrary JSON. Its caller must
supply only --print-provenance output from a successful pinned slsa-verifier run
and canonical producer metadata from the bound repository's authenticated API.
"""
BUILDER = 'https://github.com/slsa-framework/slsa-github-generator/.github/workflows/generator_generic_slsa3.yml@refs/tags/v2.1.0'
WORKFLOW = '.github/workflows/backfill-release-evidence.yml'
APPLY_INPUTS = ('prior_run_id', 'prior_attempt', 'verified_artifact_id', 'verified_artifact_sha256', 'producer_revision')


def validate_authenticated_payload(statement, bound, producer):
    """Validate authenticated JSON against an already validated repair binding.

    Fail closed for the reviewed generator v2.1.0 SLSA v0.2 format only.
    Canonical producer may still be running its own pre-upload verification job;
    generator job success and artifact association are separate adapter checks.
    """
    repo = bound['repository']
    sha = bound['workflow_revision']
    uri = 'git+https://github.com/' + repo + '@refs/heads/main'
    inputs = {'tag': bound['tag'], 'expected_commit': bound['commit'],
              'release_id': str(bound['release_id']), 'workflow_revision': sha,
              'operation': 'generate'}
    try:
        if statement['_type'] != 'https://in-toto.io/Statement/v0.1' or statement['predicateType'] != 'https://slsa.dev/provenance/v0.2':
            raise ValueError('unreviewed statement schema')
        expected_subject = {'name': bound['tag'] + '.cdx.json',
                            'digest': {'sha256': bound['sha256'][bound['tag'] + '.cdx.json']}}
        if statement['subject'] != [expected_subject]:
            raise ValueError('attestation subject must be the exact singleton SBOM')
        p = statement['predicate']
        if p['builder']['id'] != BUILDER or p['buildType'] != 'https://github.com/slsa-framework/slsa-github-generator/generic@v1':
            raise ValueError('unreviewed builder or build type')
        inv = p['invocation']
        if inv['configSource'] != {'uri': uri, 'digest': {'sha1': sha}, 'entryPoint': WORKFLOW}:
            raise ValueError('producer workflow source mismatch')
        if p['materials'] != [{'uri': uri, 'digest': {'sha1': sha}}]:
            raise ValueError('producer material mismatch')
        env = inv['environment']
        expected_env = {'github_event_name': 'workflow_dispatch', 'github_ref': 'refs/heads/main',
                        'github_ref_type': 'branch', 'github_sha1': sha,
                        'github_run_id': str(bound['producerRunId']),
                        'github_run_attempt': str(bound['producerRunAttempt'])}
        if any(env[k] != v for k, v in expected_env.items()):
            raise ValueError('producer invocation mismatch')
        for actual in (inv['parameters']['event_inputs'], env['github_event_payload']['inputs']):
            if (not isinstance(actual, dict) or any(actual.get(k) != v for k, v in inputs.items())
                    or set(actual) - set(inputs) - set(APPLY_INPUTS)
                    or any(actual[k] != '' for k in APPLY_INPUTS if k in actual)):
                raise ValueError('repair dispatch inputs mismatch')
        if p['metadata']['buildInvocationID'] != f"{bound['producerRunId']}-{bound['producerRunAttempt']}":
            raise ValueError('producer run binding mismatch')
        expected_run = {'id': bound['producerRunId'], 'run_attempt': bound['producerRunAttempt'],
                        'head_sha': sha, 'head_branch': 'main', 'event': 'workflow_dispatch', 'path': WORKFLOW}
        if any(type(producer[k]) is not type(v) or producer[k] != v for k, v in expected_run.items()):
            raise ValueError('canonical producer metadata mismatch')
        if producer['repository']['full_name'] != repo:
            raise ValueError('canonical producer repository mismatch')
    except (KeyError, TypeError) as exc:
        raise ValueError('missing or malformed authenticated identity') from exc


def validate_signed_manual_statement(statement, bound, producer):
    """Repair-lane semantics after successful signature verification, not crypto itself."""
    repo = bound['repository']; component, _, version = bound['tag'].rpartition('-v')
    try:
        if (type(producer['id']) is not int or producer['id'] != bound['producerRunId']
                or producer['head_sha'] != bound['workflow_revision']
                or producer['path'] != WORKFLOW or producer['repository']['full_name'] != repo):
            raise ValueError('canonical manual producer mismatch')
        if statement['_type'] != 'https://in-toto.io/Statement/v1' or statement['predicateType'] != 'https://slsa.dev/provenance/v1':
            raise ValueError('manual statement schema mismatch')
        if statement['subject'] != [{'name': bound['tag'] + '.cdx.json',
                'digest': {'sha256': bound['sha256'][bound['tag'] + '.cdx.json']}}]:
            raise ValueError('manual statement subject mismatch')
        definition = statement['predicate']['buildDefinition']
        if (definition['buildType'] != 'https://openbank.tech/release-please-evidence/v1'
                or definition['externalParameters'] != {'component': component, 'version': version, 'tag': bound['tag']}
                or definition['internalParameters'] != {}
                or definition['resolvedDependencies'] != [{'uri': f"git+https://github.com/{repo}@{bound['commit']}",
                    'digest': {'gitCommit': bound['commit']}}]):
            raise ValueError('manual statement source mismatch')
        details = statement['predicate']['runDetails']
        if (details['builder']['id'] != f'https://github.com/{repo}/{WORKFLOW}'
                or details['metadata']['invocationId'] != f"https://github.com/{repo}/actions/runs/{bound['producerRunId']}"):
            raise ValueError('manual statement producer mismatch')
    except (KeyError, TypeError) as exc:
        raise ValueError('missing or malformed signed manual statement') from exc
