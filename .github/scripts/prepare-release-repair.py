#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only plan or no-release-upload generation for one explicit repair tag."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

from release_evidence_repair import plan, require_member_size
from release_repair_api import ReadOnlyGitHub, require_main_inclusion
from release_repair_crypto import SIGNING_KEY, COSIGN_LINUX_SHA256


def tool(directory, name, url, digest):
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / name
    with urllib.request.urlopen(url) as response, target.open('wb') as output:
        shutil.copyfileobj(response, output)
    with target.open('rb') as source:
        actual = hashlib.file_digest(source, 'sha256').hexdigest()
    if actual != digest:
        target.unlink()
        raise ValueError('official verification tool digest mismatch')
    target.chmod(0o700)
    return str(target.resolve())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['plan', 'generate'])
    args = parser.parse_args()
    root = Path.cwd().resolve()
    env = os.environ
    if (env['GITHUB_EVENT_NAME'] != 'workflow_dispatch' or env['GITHUB_REF'] != 'refs/heads/main'
            or env['GITHUB_WORKFLOW_SHA'] != env['REPAIR_WORKFLOW_REVISION']
            or subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() != env['REPAIR_WORKFLOW_REVISION']):
        raise ValueError('repair must execute approved exact main workflow source')
    spec = importlib.util.spec_from_file_location('exact_source', root / '.github/scripts/prepare-release-evidence.py')
    helper = importlib.util.module_from_spec(spec); spec.loader.exec_module(helper)
    repository = env['GITHUB_REPOSITORY']
    api = ReadOnlyGitHub(repository, lambda tag: helper.remote_commit(root, tag))
    request = dict(tag=env['REPAIR_TAG'], commit=env['REPAIR_COMMIT'],
                   release_id=int(env['REPAIR_RELEASE_ID']), workflow_revision=env['REPAIR_WORKFLOW_REVISION'])
    require_main_inclusion(api, request['commit'], request['workflow_revision'])
    planned = plan(api, **request)
    component, separator, version = request['tag'].rpartition('-v')
    config = json.loads((root / 'release-please-config.json').read_text())['packages']
    modules = [module for module, entry in config.items() if entry.get('component') == component]
    if not separator or not version or len(modules) != 1:
        raise ValueError('tag must map to exactly one canonical release component')
    out = root / '.repair-bundle'; out.mkdir(exist_ok=False)
    envelope = dict(request=planned, repository=repository, producerRunId=int(env['GITHUB_RUN_ID']),
                    producerRunAttempt=int(env['GITHUB_RUN_ATTEMPT']), operation=args.operation)
    (out / 'repair-plan.json').write_text(json.dumps(envelope, sort_keys=True))
    if args.operation == 'plan':
        return
    workflow_ref = repository + '/.github/workflows/backfill-release-evidence.yml@refs/heads/main'
    if env.get('GITHUB_WORKFLOW_REF') != workflow_ref:
        raise ValueError('repair producer differs from actual workflow context')
    env['RELEASE_EVIDENCE_WORKFLOW_REF'] = workflow_ref
    if helper.prepare(root, modules[0], component, version, request['tag'], expected_commit=request['commit']) != request['commit']:
        raise ValueError('generated source differs from approved release commit')
    # Validate ALL unsigned documents before the first signing operation.
    for suffix in ['.cdx.json', '.slsa.json', '.vex.json', '.evidence.json']:
        require_member_size((root / (request['tag'] + suffix)).stat().st_size)
    cosign = tool(Path(env['RUNNER_TEMP']) / 'repair-tools', 'cosign',
        'https://github.com/sigstore/cosign/releases/download/v3.1.4/cosign-linux-amd64',
        COSIGN_LINUX_SHA256)
    for suffix in ['.cdx.json', '.slsa.json', '.vex.json', '.evidence.json']:
        name = request['tag'] + suffix
        subprocess.run([cosign, 'sign-blob', '--yes', '--new-bundle-format=false',
            '--use-signing-config=false', '--tlog-upload=true', '--key', SIGNING_KEY,
            '--output-signature', name + '.sig', name], check=True)
        require_member_size((root / (name + '.sig')).stat().st_size)
        subprocess.run([cosign, 'verify-blob', '--key', SIGNING_KEY, '--signature', name + '.sig', name], check=True)
        for asset in [name, name + '.sig']:
            shutil.copyfile(root / asset, out / asset)
    helper.require_remote(root, request['tag'], request['commit'])
    if api.assets(request['release_id']):
        raise ValueError('release assets changed during generation')
    envelope['sha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir() if p.name != 'repair-plan.json'}
    (out / 'repair-plan.json').write_text(json.dumps(envelope, sort_keys=True))
    sbom = request['tag'] + '.cdx.json'
    subject = base64.b64encode((envelope['sha256'][sbom] + '  ' + sbom + '\n').encode()).decode()
    with open(env['GITHUB_OUTPUT'], 'a') as output:
        output.write('subjects=' + subject + '\n')
        output.write('sizes=' + json.dumps({p.name: p.stat().st_size for p in out.iterdir()}, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
