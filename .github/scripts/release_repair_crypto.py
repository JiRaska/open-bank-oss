# SPDX-License-Identifier: Apache-2.0
"""Real verification executor. No release upload or command-line apply surface."""
import hashlib
import importlib.util
import json
import io
import stat
import zipfile
from pathlib import Path
import subprocess
import tempfile

from release_evidence_assets import expected_release_assets
from release_evidence_repair import VerifiedBundle, binding_digest
from release_repair_attestation import validate_signed_manual_statement, BUILDER, validate_authenticated_payload

# Published asset digests from official slsa-verifier v2.7.1 release metadata.
VERIFIER_SHA256 = frozenset({
    '946dbec729094195e88ef78e1734324a27869f03e2c6bd2f61cbc06bd5350339',  # linux-amd64
    '39abfcf5f1d690c3e889ce3d2d6a8b87711424d83368511868d414e8f8bcb05c',  # darwin-arm64
})
# Existing cosign v2.4.3 pins; no ambient executable is trusted implicitly.
SIGNING_KEY = 'awskms:///alias/openbank-cosign-signing'

COSIGN_SHA256 = frozenset({
    'caaad125acef1cb81d58dcdc454a1e429d09a750d1e9e2b3ed1aed8964454708',
    'edfc761b27ced77f0f9ca288ff4fac7caa898e1e9db38f4dfdf72160cdf8e638',
})


def pinned_binary(path, hashes):
    path = Path(path)
    if not path.is_absolute() or not path.is_file() or path.is_symlink():
        raise ValueError('trusted absolute regular executable required')
    if hashlib.sha256(path.read_bytes()).hexdigest() not in hashes:
        raise ValueError('verification executable digest mismatch')
    return str(path)


def verify_bundle(bound, bundle, producer, *, cosign, slsa_verifier,
                  artifact, artifact_archive, generator_job, expected_artifact_id, expected_generator_job_id):
    """Canonical metadata must come from the authenticated bound-repository API.

    Generator job and immutable artifact IDs must be persisted by the workflow,
    never selected by a filename-only lookup. This executor performs actual crypto;
    the caller must additionally authenticate that persisted artifact envelope.
    """
    tag = bound['tag']
    if set(bundle) != expected_release_assets(tag) or any(type(b) is not bytes or not b for b in bundle.values()):
        raise ValueError('complete immutable nine-file bundle required')
    if {n: hashlib.sha256(b).hexdigest() for n, b in bundle.items()} != bound['sha256']:
        raise ValueError('persisted bundle hashes mismatch')
    if (type(expected_artifact_id) is not int or artifact['id'] != expected_artifact_id
            or artifact['name'] != tag + '.cdx.json.intoto.jsonl' or artifact['expired'] is not False
            or artifact['workflow_run']['id'] != bound['producerRunId']):
        raise ValueError('provenance artifact identity mismatch')
    if (artifact.get('digest') != 'sha256:' + hashlib.sha256(artifact_archive).hexdigest()):
        raise ValueError('downloaded artifact archive digest mismatch')
    try:
        with zipfile.ZipFile(io.BytesIO(artifact_archive)) as archive:
            entries = archive.infolist()
            expected_name = tag + '.cdx.json.intoto.jsonl'
            if (len(entries) != 1 or entries[0].filename != expected_name or entries[0].is_dir()
                    or stat.S_IFMT(entries[0].external_attr >> 16) not in (0, stat.S_IFREG)
                    or entries[0].file_size != len(bundle[expected_name])):
                raise ValueError('provenance archive must contain exact single regular file')
            if archive.read(entries[0]) != bundle[expected_name]:
                raise ValueError('bundle provenance differs from immutable artifact bytes')
    except zipfile.BadZipFile as exc:
        raise ValueError('invalid provenance artifact archive') from exc
    run_url = f"https://api.github.com/repos/{bound['repository']}/actions/runs/{bound['producerRunId']}"
    if (type(expected_generator_job_id) is not int or generator_job['id'] != expected_generator_job_id
            or generator_job['run_url'] != run_url or generator_job['run_attempt'] != bound['producerRunAttempt']
            or generator_job['name'] != 'Release repair provenance / generator'
            or generator_job['status'] != 'completed' or generator_job['conclusion'] != 'success'):
        raise ValueError('generator job is not the successful bound attempt')
    cosign = pinned_binary(cosign, COSIGN_SHA256)
    verifier = pinned_binary(slsa_verifier, VERIFIER_SHA256)
    with tempfile.TemporaryDirectory(prefix='release-repair-verify-') as temporary:
        directory = Path(temporary)
        for name, content in bundle.items():
            if Path(name).name != name:
                raise ValueError('unsafe bundle filename')
            (directory / name).write_bytes(content)
        for suffix in ('.cdx.json', '.slsa.json', '.vex.json', '.evidence.json'):
            name = tag + suffix
            subprocess.run([cosign, 'verify-blob', '--key', SIGNING_KEY, '--signature', name + '.sig', name],
                           cwd=directory, check=True, capture_output=True)
        validate_signed_manual_statement(json.loads(bundle[tag + '.slsa.json']), bound, producer)
        spec = importlib.util.spec_from_file_location('repair_manifest', Path(__file__).with_name('verify-release-evidence-manifest.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        component, separator, version = tag.rpartition('-v')
        if not separator:
            raise ValueError('canonical component-version tag required')
        errors = module.verify(tag, directory, component, version, bound['commit'])
        if errors:
            raise ValueError('; '.join(errors))
        command = [verifier, 'verify-artifact', tag + '.cdx.json', '--provenance-path', tag + '.cdx.json.intoto.jsonl',
                   '--source-uri', 'github.com/' + bound['repository'], '--source-branch', 'main',
                   '--builder-id', BUILDER, '--print-provenance']
        inputs = {'tag': tag, 'expected_commit': bound['commit'], 'release_id': str(bound['release_id']),
                  'workflow_revision': bound['workflow_revision'], 'operation': 'generate'}
        for name, value in inputs.items():
            command += ['--build-workflow-input', name + '=' + value]
        result = subprocess.run(command, cwd=directory, check=True, capture_output=True)
        # Only successful official-verifier output reaches the structural validator.
        statement = json.loads(result.stdout)
        validate_authenticated_payload(statement, bound, producer)
    return VerifiedBundle(binding_digest(bound))
