# SPDX-License-Identifier: Apache-2.0
"""Real verification executor. No release upload or command-line apply surface."""
import hashlib
import base64
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

# Published official Cosign v3.1.4 digests; no ambient executable is trusted.
SIGNING_KEY = 'awskms:///alias/openbank-cosign-signing'
COSIGN_LINUX_SHA256 = '424e2c97d0b45fd7a6b608a87f090f35633f4c23ece5f092d48bba11aaf4bbfa'
COSIGN_SHA256 = frozenset({
    COSIGN_LINUX_SHA256,
    '9f9b8cd557cf2e5b3df3373a00baa005a86b02391e3e06dd0ce37b3a1533d3a4',  # darwin-arm64
})


def pinned_binary(path, hashes):
    path = Path(path)
    if not path.is_absolute() or not path.is_file() or path.is_symlink():
        raise ValueError('trusted absolute regular executable required')
    with path.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    if digest not in hashes:
        raise ValueError('verification executable digest mismatch')
    return str(path)


def verify_official_statement(cosign, directory, sbom_name, provenance_name, immutable_provenance):
    """Return only the payload from the exact native bundle verified by Cosign.

    No legacy representation conversion, custom trust root, or insecure fallback.
    Certificate identity, issuer, claims, timestamp, SCT and transparency checks
    use the official verifier's normal policy. The caller still checks all repair
    subject/producer/run/input identities on the authenticated returned statement.
    """
    if (directory / provenance_name).read_bytes() != immutable_provenance:
        raise ValueError('provenance file differs from immutable artifact')
    subprocess.run([cosign, 'verify-blob-attestation', '--bundle', provenance_name,
        '--certificate-identity', BUILDER, '--certificate-oidc-issuer',
        'https://token.actions.githubusercontent.com', '--type', 'slsaprovenance02',
        '--check-claims=true', sbom_name], cwd=directory, check=True, capture_output=True)
    # Parse SAME immutable bytes only after the complete cryptographic check passed.
    envelope = json.loads(immutable_provenance)
    if envelope.get('mediaType') != 'application/vnd.dev.sigstore.bundle.v0.3+json':
        raise ValueError('unsupported official provenance bundle format')
    dsse = envelope.get('dsseEnvelope')
    if not isinstance(dsse, dict) or dsse.get('payloadType') != 'application/vnd.in-toto+json':
        raise ValueError('unsupported authenticated payload type')
    return json.loads(base64.b64decode(dsse['payload'], validate=True))


def verify_bundle(bound, bundle, producer, *, cosign,
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
        statement = verify_official_statement(cosign, directory, tag + '.cdx.json',
            tag + '.cdx.json.intoto.jsonl', bundle[tag + '.cdx.json.intoto.jsonl'])
        validate_authenticated_payload(statement, bound, producer)
    return VerifiedBundle(binding_digest(bound))
