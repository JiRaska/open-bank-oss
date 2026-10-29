#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify and retain a complete repair bundle without publishing release assets."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import zipfile

from release_evidence_assets import expected_release_assets
from release_evidence_repair import bind_bundle, binding_digest, identity, MAX_REPAIR_MEMBER
from release_repair_api import ReadOnlyGitHub
from release_repair_crypto import verify_bundle


# Repair-lane-only resource bounds; oversize is rejected, never truncated/accepted.
# The retained official v2.7.1 sample is below 32 KiB. Eight MiB leaves ample
# headroom for this lane's singleton SBOM subject and bounded dispatch inputs.
PROVENANCE_LIMIT = MAX_REPAIR_MEMBER
ZIP_OVERHEAD_LIMIT = 1024 * 1024


def archive(api, artifact_id, run_id, name, maximum_size):
    metadata = api.get(f'actions/artifacts/{artifact_id}')
    if (metadata['id'] != artifact_id or metadata['expired'] is not False
            or metadata['workflow_run']['id'] != run_id or metadata['name'] != name
            or type(metadata['size_in_bytes']) is not int or not 0 < metadata['size_in_bytes'] <= maximum_size):
        raise ValueError('artifact association mismatch')
    process = subprocess.Popen(['gh', 'api', f'repos/{api.repository}/actions/artifacts/{artifact_id}/zip'], stdout=subprocess.PIPE)
    data = process.stdout.read(metadata['size_in_bytes'] + 1)
    if len(data) != metadata['size_in_bytes']:
        process.kill(); process.wait()
        raise ValueError('artifact download size mismatch')
    if process.wait() or metadata['digest'] != 'sha256:' + hashlib.sha256(data).hexdigest():
        raise ValueError('artifact download digest mismatch')
    return metadata, data


def members(data, expected, *, sizes=None, maximum_member=None):
    with zipfile.ZipFile(io.BytesIO(data)) as source:
        infos = source.infolist()
        if len(infos) != len(expected) or {i.filename for i in infos} != expected:
            raise ValueError('artifact members mismatch')
        if any(i.is_dir() or stat.S_IFMT(i.external_attr >> 16) not in (0, stat.S_IFREG) for i in infos):
            raise ValueError('artifact members must be regular files')
        result = {}
        for entry in infos:
            if sizes is not None and (type(sizes[entry.filename]) is not int or entry.file_size != sizes[entry.filename]):
                raise ValueError('artifact member differs from trusted generated length')
            if maximum_member is not None and entry.file_size > maximum_member:
                raise ValueError('provenance exceeds repair-lane resource limit')
            limit = sizes[entry.filename] if sizes is not None else maximum_member
            if limit is None:
                raise ValueError('explicit artifact read bound required')
            with source.open(entry) as stream:
                content = stream.read(limit + 1)
            if len(content) != entry.file_size or len(content) > limit:
                raise ValueError('artifact member length mismatch')
            result[entry.filename] = content
        return result


def main():
    env = os.environ; tag = env['REPAIR_TAG']; run_id = int(env['GITHUB_RUN_ID']); attempt = int(env['GITHUB_RUN_ATTEMPT'])
    if env['GITHUB_WORKFLOW_SHA'] != env['REPAIR_WORKFLOW_REVISION'] or subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() != env['REPAIR_WORKFLOW_REVISION']:
        raise ValueError('verification workflow source mismatch')
    source_spec = importlib.util.spec_from_file_location('repair_exact_source', Path(__file__).with_name('prepare-release-evidence.py'))
    source = importlib.util.module_from_spec(source_spec); source_spec.loader.exec_module(source)
    api = ReadOnlyGitHub(env['GITHUB_REPOSITORY'], lambda tag: source.remote_commit(Path.cwd(), tag))
    producer = api.get(f'actions/runs/{run_id}')
    if producer['run_attempt'] != attempt:
        raise ValueError('producer attempt changed')
    expected = expected_release_assets(tag)
    official = tag + '.cdx.json.intoto.jsonl'
    sizes = json.loads(env['REPAIR_BUNDLE_SIZES'])
    source_names = expected - {official} | {'repair-plan.json'}
    if set(sizes) != source_names or any(type(v) is not int or v <= 0 for v in sizes.values()):
        raise ValueError('trusted generation size outputs are incomplete')
    _, raw = archive(api, int(env['REPAIR_BUNDLE_ARTIFACT_ID']), run_id, 'release-repair-source', sum(sizes.values()) + ZIP_OVERHEAD_LIMIT)
    files = members(raw, source_names, sizes=sizes, maximum_member=MAX_REPAIR_MEMBER)
    envelope = json.loads(files.pop('repair-plan.json'))
    if (envelope['producerRunId'] != run_id or envelope['producerRunAttempt'] != attempt
            or envelope['repository'] != api.repository or envelope['operation'] != 'generate'
            or envelope['request']['tag'] != tag or envelope['request']['commit'] != env['REPAIR_COMMIT']
            or envelope['request']['release_id'] != int(env['REPAIR_RELEASE_ID'])
            or envelope['request']['workflow_revision'] != env['REPAIR_WORKFLOW_REVISION']
            or envelope['sha256'] != {n: hashlib.sha256(b).hexdigest() for n, b in files.items()}):
        raise ValueError('generated bundle envelope mismatch')
    artifacts = api.pages(f'actions/runs/{run_id}/artifacts', 'artifacts')
    selected = [a for a in artifacts if a['name'] == official and not a['expired']]
    if len(selected) != 1 or env['PROVENANCE_NAME'] != official:
        raise ValueError('missing or ambiguous official provenance output')
    metadata, raw_provenance = archive(api, selected[0]['id'], run_id, official, PROVENANCE_LIMIT + ZIP_OVERHEAD_LIMIT)
    files.update(members(raw_provenance, {official}, maximum_member=PROVENANCE_LIMIT))
    jobs = api.pages(f'actions/runs/{run_id}/attempts/{attempt}/jobs', 'jobs')
    selected_jobs = [j for j in jobs if j['name'] == 'Release repair provenance / generator']
    if len(selected_jobs) != 1:
        raise ValueError('missing or ambiguous canonical generator job')
    bound = bind_bundle(envelope['request'], files, repository=api.repository, producer_run_id=run_id, producer_run_attempt=attempt)
    spec = importlib.util.spec_from_file_location('repair_prepare', Path(__file__).with_name('prepare-release-repair.py'))
    prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)
    tools = Path(env['RUNNER_TEMP']) / 'repair-verify-tools'
    cosign = prepare.tool(tools, 'cosign', 'https://github.com/sigstore/cosign/releases/download/v2.4.3/cosign-linux-amd64',
        'caaad125acef1cb81d58dcdc454a1e429d09a750d1e9e2b3ed1aed8964454708')
    verifier = prepare.tool(tools, 'slsa-verifier', 'https://github.com/slsa-framework/slsa-verifier/releases/download/v2.7.1/slsa-verifier-linux-amd64',
        '946dbec729094195e88ef78e1734324a27869f03e2c6bd2f61cbc06bd5350339')
    receipt = verify_bundle(bound, files, producer, cosign=cosign, slsa_verifier=verifier,
        artifact=metadata, artifact_archive=raw_provenance, generator_job=selected_jobs[0],
        expected_artifact_id=metadata['id'], expected_generator_job_id=selected_jobs[0]['id'])
    if receipt.binding_sha256 != binding_digest(bound):
        raise ValueError('strict verification receipt mismatch')
    identity(api, **{k: bound[k] for k in ('tag', 'commit', 'release_id', 'workflow_revision')})
    if api.assets(bound['release_id']):
        raise ValueError('release is no longer a zero-asset candidate')
    out = Path('.repair-verified'); out.mkdir(exist_ok=False)
    for name, data in files.items(): (out / name).write_bytes(data)
    (out / 'repair-binding.json').write_text(json.dumps(bound, sort_keys=True))
    (out / 'verification.json').write_text(json.dumps({'bindingSha256': receipt.binding_sha256,
        'provenanceArtifactId': metadata['id'], 'generatorJobId': selected_jobs[0]['id'],
        'published': False, 'claim': 'verified-current-repair-generation'}))


if __name__ == '__main__':
    main()
