# SPDX-License-Identifier: Apache-2.0
"""Bind Admin UI's baked service SBOMs to a successful Security run and source inputs.

The Security producer records a digest of every dependency-affecting tracked input.
The consumer checks that fingerprint at the image source commit, verifies the
GitHub artifact digest and successful main workflow run, then stages only named,
validated CycloneDX documents.  The local image builder verifies the staged
manifest again before freezing its signed context.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import subprocess
import sys
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

PROVENANCE = Path('openbank-admin-ui/sbom-source-provenance.json')
SHA = re.compile(r'^[0-9a-f]{40}$')
REPORT = re.compile(r'^(openbank-[a-z0-9-]+)-sbom\.cdx\.json$')


def fail(message: str) -> None:
    raise ValueError(message)


def git(root: Path, *args: str) -> bytes:
    return subprocess.run(['git', *args], cwd=root, check=True, capture_output=True).stdout


def source_sha(root: Path) -> str:
    value = git(root, 'rev-parse', 'HEAD').decode().strip()
    if not SHA.fullmatch(value):
        fail('invalid source commit')
    return value


def gradle_modules(root: Path) -> set[str]:
    return {raw.decode().split('/')[0] for raw in git(root, 'ls-files', '-z').split(b'\0')
            if raw and raw.endswith(b'/build.gradle.kts') and raw.count(b'/') == 1
            and raw.startswith(b'openbank-')}


def modules(root: Path) -> set[str]:
    """Modules whose build applies CycloneDX directly or through the service convention.

    Unreleased library submodules without that task are excluded by this explicit
    source declaration. A future module must declare one of these plugins before
    its report enters the governed bundle.
    """
    result = set()
    for name in gradle_modules(root):
        script = (root / name / 'build.gradle.kts').read_text()
        if 'alias(libs.plugins.cyclonedx)' in script or 'id("openbank.quarkus-service")' in script:
            result.add(name)
    if not result:
        fail('no CycloneDX modules')
    return result


def dependency_path(path: str) -> bool:
    name = Path(path).name
    return (path.startswith(('build-logic/', 'gradle/')) or
            name in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
                     'gradle.lockfile', 'libs.versions.toml', 'version.txt') or
            name.endswith(('.gradle.kts', '.gradle', '.lockfile')) or
            (path.startswith('openbank-') and name == 'Dockerfile'))


def input_digest(root: Path) -> str:
    digest = hashlib.sha256()
    paths = sorted(p.decode() for p in git(root, 'ls-files', '-z').split(b'\0')
                   if p and dependency_path(p.decode()))
    if not paths:
        fail('no dependency inputs')
    for path in paths:
        data = (root / path).read_bytes()
        digest.update(path.encode() + b'\0' + hashlib.sha256(data).digest())
    return digest.hexdigest()


def inputs_changed(root: Path, before: str, after: str) -> bool:
    if not SHA.fullmatch(before) or not SHA.fullmatch(after):
        fail('invalid commit for dependency-input diff')
    changed = git(root, 'diff', '--name-only', '-z', before, after).split(b'\0')
    return any(dependency_path(raw.decode()) or raw.decode() in (
        '.github/scripts/admin-ui-sbom-provenance.py', '.github/workflows/security.yml')
               for raw in changed if raw)


def check_sbom(name: str, data: bytes, known: set[str]) -> None:
    match = REPORT.fullmatch(name)
    if not match or match.group(1) not in known:
        fail('unknown SBOM module or filename')
    try:
        doc = json.loads(data)
    except (ValueError, UnicodeDecodeError) as exc:
        raise ValueError('invalid SBOM JSON') from exc
    if not isinstance(doc, dict) or doc.get('bomFormat') != 'CycloneDX':
        fail('not a CycloneDX SBOM')
    if not isinstance(doc.get('components'), list) or not doc['components']:
        fail('empty or missing SBOM components')
    if not isinstance(doc.get('specVersion'), str) or not doc['specVersion']:
        fail('missing CycloneDX specVersion')
    component = (doc.get('metadata') or {}).get('component')
    module = match.group(1)
    if not isinstance(component, dict) or component.get('name') != module:
        fail('CycloneDX metadata component does not match module')
    purl = component.get('purl')
    if not isinstance(purl, str) or not purl.startswith(f'pkg:maven/com.openbank/{module}@'):
        fail('CycloneDX metadata purl does not match module')
    project_path = urllib.parse.parse_qs(urllib.parse.urlsplit(purl).query).get('project_path')
    if project_path != [f':{module}']:
        fail('CycloneDX project path does not match module')


def inventory(reports: dict[str, bytes], known: set[str]) -> dict[str, str]:
    expected = {f'{module}-sbom.cdx.json' for module in known}
    result = {}
    for name, data in sorted(reports.items()):
        check_sbom(name, data, known)
        result[name] = hashlib.sha256(data).hexdigest()
    if set(reports) != expected:
        fail(f'incomplete SBOM set: expected {len(expected)}, got {len(reports)}')
    return result


def producer(root: Path, reports_dir: Path, output: Path) -> None:
    known = modules(root)
    reports = {p.name: p.read_bytes() for p in reports_dir.glob('*-sbom.cdx.json')}
    evidence = {'schema': 1, 'source_sha': source_sha(root),
                'dependency_input_sha256': input_digest(root),
                'reports': inventory(reports, known)}
    output.write_text(json.dumps(evidence, sort_keys=True, indent=2) + '\n')


def http(url: str, token: str) -> bytes:
    class StripCrossHostAuth(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, code, message, headers, newurl):
            redirected = super().redirect_request(request, code, message, headers, newurl)
            if redirected and urllib.parse.urlsplit(newurl).netloc != urllib.parse.urlsplit(request.full_url).netloc:
                redirected.remove_header('Authorization')
            return redirected

    request = urllib.request.Request(url, headers={
        'Authorization': f'Bearer {token}', 'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.build_opener(StripCrossHostAuth()).open(request, timeout=30) as response:
        return response.read()


def json_api(url: str, token: str) -> dict:
    value = json.loads(http(url, token))
    if not isinstance(value, dict):
        fail('invalid GitHub API document')
    return value


def artifact_candidates(repo: str, token: str) -> list[dict]:
    found = []
    # The API does not promise creation order. Read every page before sorting.
    for page in range(1, 101):
        data = json_api(f'https://api.github.com/repos/{repo}/actions/artifacts?name=per-service-sboms&per_page=100&page={page}', token)
        items = data.get('artifacts')
        if not isinstance(items, list):
            fail('invalid artifact listing')
        found.extend(a for a in items if isinstance(a, dict) and not a.get('expired'))
        if len(items) < 100:
            break
    else:
        fail('artifact pagination exceeded 100 pages')
    return sorted(found, key=lambda a: a.get('created_at', ''), reverse=True)


def zip_reports(raw: bytes, known: set[str]) -> tuple[dict, dict[str, bytes]]:
    result = {}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or 'provenance.json' not in names:
            fail('duplicate files or missing producer provenance')
        for name in names:
            if '/' in name or '\\' in name or name not in ('provenance.json',) and not REPORT.fullmatch(name):
                fail('unexpected artifact member')
            info = archive.getinfo(name)
            if info.is_dir() or info.file_size > 100 * 1024 * 1024:
                fail('invalid artifact member size')
            result[name] = archive.read(name)
    proof = json.loads(result.pop('provenance.json'))
    if not isinstance(proof, dict):
        fail('invalid producer provenance')
    actual = inventory(result, known)
    if proof.get('schema') != 1 or proof.get('reports') != actual:
        fail('producer report digest mismatch')
    return proof, result


def validate_candidate(root: Path, repo: str, token: str, artifact: dict,
                       target_sha: str, target_input: str, known: set[str]) -> tuple[dict, dict[str, bytes]] | None:
    run_id = (artifact.get('workflow_run') or {}).get('id')
    if (artifact.get('name') != 'per-service-sboms' or
            not isinstance(run_id, int) or not isinstance(artifact.get('id'), int)):
        return None
    base = f'https://api.github.com/repos/{repo}'
    run = json_api(f'{base}/actions/runs/{run_id}', token)
    if (run.get('id') != run_id or run.get('path') != '.github/workflows/security.yml' or
            run.get('head_branch') != 'main' or run.get('event') not in ('push', 'schedule', 'workflow_dispatch') or
            run.get('status') != 'completed' or run.get('conclusion') != 'success'):
        return None
    producer_sha = run.get('head_sha')
    if not isinstance(producer_sha, str) or not SHA.fullmatch(producer_sha):
        return None
    if (artifact.get('workflow_run') or {}).get('head_sha') not in (None, producer_sha):
        return None
    if subprocess.run(['git', 'merge-base', '--is-ancestor', producer_sha, target_sha],
                      cwd=root, capture_output=True, check=False).returncode != 0:
        return None
    declared = artifact.get('digest')
    if not isinstance(declared, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', declared):
        fail('GitHub artifact has no SHA-256 digest')
    raw = http(f'{base}/actions/artifacts/{artifact["id"]}/zip', token)
    if hashlib.sha256(raw).hexdigest() != declared.removeprefix('sha256:'):
        fail('GitHub artifact digest mismatch')
    proof, reports = zip_reports(raw, known)
    if (proof.get('source_sha') != producer_sha or
            proof.get('dependency_input_sha256') != target_input):
        return None
    return ({'schema': 1, 'kind': 'github-security-run', 'source_sha': target_sha,
             'producer_sha': producer_sha, 'producer_run_id': run_id,
             'artifact_id': artifact['id'], 'artifact_digest': declared,
             'dependency_input_sha256': target_input,
             'reports': proof['reports']}, reports)


def stage(root: Path, repo: str, token: str) -> None:
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo) or not token:
        fail('invalid GitHub repository or missing token')
    target_sha = source_sha(root)
    target_input = input_digest(root)
    known = modules(root)
    for candidate in artifact_candidates(repo, token):
        selected = validate_candidate(root, repo, token, candidate, target_sha, target_input, known)
        if selected is None:
            continue
        proof, reports = selected
        # Remove ignored outputs from previous local runs only after every remote
        # validation succeeds. They must never silently supplement the artifact.
        for module in gradle_modules(root):
            (root / module / 'build/reports/bom.json').unlink(missing_ok=True)
        for name, data in reports.items():
            module = REPORT.fullmatch(name).group(1)
            path = root / module / 'build/reports/bom.json'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        (root / PROVENANCE).write_text(json.dumps(proof, sort_keys=True, indent=2) + '\n')
        verify(root)
        print(f'Verified {len(reports)} SBOMs from Security run {proof["producer_run_id"]}')
        return
    fail('no successful main Security artifact matching current dependency inputs')


def local(root: Path) -> None:
    known = modules(root)
    reports = {}
    for module in known:
        path = root / module / 'build/reports/bom.json'
        if path.is_file():
            reports[f'{module}-sbom.cdx.json'] = path.read_bytes()
    proof = {'schema': 1, 'kind': 'local-sbomAll', 'source_sha': source_sha(root),
             'dependency_input_sha256': input_digest(root), 'reports': inventory(reports, known)}
    (root / PROVENANCE).write_text(json.dumps(proof, sort_keys=True, indent=2) + '\n')
    verify(root)


def verify(root: Path, *, github_only: bool = False,
           repo: str | None = None, token: str | None = None) -> None:
    proof = json.loads((root / PROVENANCE).read_bytes())
    if github_only and proof.get('kind') != 'github-security-run':
        fail('--no-sbom requires a verified Security artifact')
    if (proof.get('schema') != 1 or proof.get('kind') not in ('github-security-run', 'local-sbomAll') or
            proof.get('source_sha') != source_sha(root) or
            proof.get('dependency_input_sha256') != input_digest(root)):
        fail('SBOM provenance is stale for this source commit')
    known = modules(root)
    reports = {}
    for module in gradle_modules(root):
        path = root / module / 'build/reports/bom.json'
        if path.is_file():
            reports[f'{module}-sbom.cdx.json'] = path.read_bytes()
    if inventory(reports, known) != proof.get('reports'):
        fail('staged SBOMs differ from verified provenance')
    if github_only:
        if not repo or not token or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
            fail('remote SBOM verification needs repository and token')
        artifact_id = proof.get('artifact_id')
        if not isinstance(artifact_id, int):
            fail('missing artifact id in staged proof')
        artifact = json_api(f'https://api.github.com/repos/{repo}/actions/artifacts/{artifact_id}', token)
        selected = validate_candidate(root, repo, token, artifact, source_sha(root),
                                      input_digest(root), known)
        if selected is None or selected[0] != proof:
            fail('staged proof differs from live Security artifact')


def main() -> None:
    parser = argparse.ArgumentParser()
    subs = parser.add_subparsers(dest='mode', required=True)
    for mode in ('produce', 'stage', 'local', 'verify', 'inputs-changed'):
        sub = subs.add_parser(mode)
        sub.add_argument('--root', type=Path, required=True)
        if mode == 'produce':
            sub.add_argument('--reports', type=Path, required=True)
            sub.add_argument('--output', type=Path, required=True)
        if mode == 'stage':
            sub.add_argument('--repo', required=True)
            sub.add_argument('--token', required=True)
        if mode == 'verify':
            sub.add_argument('--github-only', action='store_true')
            sub.add_argument('--repo')
            sub.add_argument('--token')
        if mode == 'inputs-changed':
            sub.add_argument('--before', required=True)
            sub.add_argument('--after', required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.mode == 'produce':
        producer(root, args.reports, args.output)
    elif args.mode == 'stage':
        stage(root, args.repo, args.token)
    elif args.mode == 'local':
        local(root)
    elif args.mode == 'inputs-changed':
        sys.exit(0 if inputs_changed(root, args.before, args.after) else 1)
    else:
        verify(root, github_only=args.github_only, repo=args.repo, token=args.token)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as exc:
        print(f'::error title=SBOM provenance::{exc}', file=sys.stderr)
        sys.exit(2)
