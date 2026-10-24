#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Require individual platform job results, without artifact or REST roster authority.

The filename and caller IDs are independent of attempts: GitHub carries forward actual
successful dependencies on partial reruns. A selected cancelled/missing build is never
substituted with a success artifact from an earlier attempt.
"""
import argparse
import importlib.util
import json
import os
import sys
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('service_jobs', Path(__file__).with_name('generate-service-ci-jobs.py'))
service_jobs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(service_jobs)


RENDERER_PROOF_PATHS = {
    '.github/workflows/services-ci.yml',
    '.github/scripts/generate-service-ci-jobs.py',
    '.github/scripts/check-service-build-results.py',
    '.github/scripts/test-check-service-build-results.py',
}


def renderer_selected(paths, event):
    """Select renderer proofs independently of the Gradle module inventory."""
    if event not in {'pull_request', 'push', 'schedule', 'workflow_dispatch'}:
        raise ValueError('unknown renderer selection event')
    if (not isinstance(paths, (list, tuple)) or any(
            not isinstance(path, str) or not path or path.startswith('/')
            or any(part in {'', '.', '..'} for part in path.split('/'))
            or any(ord(char) < 32 for char in path) for path in paths)):
        raise ValueError('invalid renderer changed-file inventory')
    return (event in {'schedule', 'workflow_dispatch'}
            or any(path.startswith('openbank-document-renderer/')
                   or path in RENDERER_PROOF_PATHS for path in paths))


def verify(services, needs, event, ref, modules=None):
    modules = service_jobs.inventory(service_jobs.ROOT) if modules is None else modules
    if (not isinstance(services, list) or any(not isinstance(s, str) for s in services)
            or len(services) != len(set(services)) or not set(services) <= set(modules)):
        return ['invalid or foreign selected-service inventory']
    if event not in {'pull_request', 'push', 'schedule', 'workflow_dispatch'} or not isinstance(needs, dict):
        return ['unknown event or unavailable dependency results']
    lane = 'pr' if event == 'pull_request' else 'other'
    expected = {service_jobs.job_id(module, branch) for branch in service_jobs.LANES for module in modules}
    observed = {key for key in needs if key.startswith('build_')}
    problems = []
    if observed != expected:
        problems.append('explicit caller roster is missing, foreign, or incomplete')
    changes = needs.get('changes', {})
    if not isinstance(changes, dict) or changes.get('result') != 'success':
        problems.append('service scope has no successful detection result')
    else:
        scope = changes.get('outputs', {})
        if not isinstance(scope, dict) or scope.get('any') != ('true' if services else 'false'):
            problems.append('selected inventory and any-build scope disagree')
        renderer = scope.get('renderer-changed') if isinstance(scope, dict) else None
        if not isinstance(renderer, str) or renderer not in {'true', 'false'}:
            problems.append('renderer scope is missing or invalid')
        else:
            record = needs.get('renderer-tests')
            wanted = 'success' if renderer == 'true' else 'skipped'
            if not isinstance(record, dict) or record.get('result') != wanted:
                problems.append(f'renderer-tests: selected scope requires {wanted}')
    contract_required = event in {'push', 'workflow_dispatch'} and ref == 'refs/heads/main'
    for branch in service_jobs.LANES:
        for module in modules:
            key = service_jobs.job_id(module, branch)
            record = needs.get(key)
            if not isinstance(record, dict):
                problems.append(f'{key}: missing platform result')
                continue
            selected = branch == lane and module in services
            if not selected:
                if record.get('result') != 'skipped':
                    problems.append(f'{key}: an unselected caller was not skipped')
                continue
            outputs = record.get('outputs')
            if (record.get('result') != 'success' or not isinstance(outputs, dict)
                    or set(outputs) != {'platform_results'}):
                problems.append(f'{key}: selected caller did not succeed with outputs')
                continue
            try:
                jobs = json.loads(outputs.get('platform_results', ''), object_pairs_hook=unique_object)
            except (ValueError, TypeError):
                problems.append(f'{key}: unavailable or malformed platform result object')
                continue
            expected_jobs = {'build'} if branch == 'pr' else {'build', 'contract', 'publish-evidence'}
            if not isinstance(jobs, dict) or set(jobs) != expected_jobs:
                problems.append(f'{key}: platform job roster does not match the reusable workflow')
                continue
            valid = True
            for job in jobs.values():
                if (not isinstance(job, dict) or set(job) != {'result', 'outputs'}
                        or job.get('outputs') != {}
                        or not isinstance(job.get('result'), str)
                        or job.get('result') not in {'success', 'failure', 'cancelled', 'skipped'}):
                    valid = False
            if not valid:
                problems.append(f'{key}: invalid platform schema or unexpected child outputs')
                continue
            if jobs['build']['result'] != 'success':
                problems.append(f'{key}: individual build has no successful verdict')
            if branch != 'pr':
                wanted_contract = 'success' if contract_required else 'skipped'
                if jobs['contract']['result'] != wanted_contract:
                    problems.append(f'{key}: individual contract result must be {wanted_contract}')
    return problems


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON member')
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--select-renderer', action='store_true')
    args = parser.parse_args()
    try:
        if args.select_renderer:
            selected = renderer_selected(os.environ['FILES'].splitlines(), os.environ['GITHUB_EVENT_NAME'])
            print('renderer-changed=' + str(selected).lower())
            return 0
        services = json.loads(os.environ['SERVICES_JSON'], object_pairs_hook=unique_object)
        needs = json.loads(os.environ['NEEDS_JSON'], object_pairs_hook=unique_object)
        problems = verify(services, needs, os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_REF'])
    except (KeyError, ValueError, OSError) as error:
        print(f'cannot determine service verdict: {error.__class__.__name__}')
        return 1
    for problem in problems:
        print(problem)
    if problems:
        return 1
    print(f'Verified {len(services)} selected services from individual platform results')
    return 0


if __name__ == '__main__':
    sys.exit(main())
