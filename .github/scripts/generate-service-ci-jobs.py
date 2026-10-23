#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate explicit Services CI callers; no matrix result represents multiple modules."""
import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = Path('.github/workflows/services-ci.yml')
CALLERS = ('  # BEGIN GENERATED SERVICE CI CALLERS', '  # END GENERATED SERVICE CI CALLERS')
NEEDS = ('    # BEGIN GENERATED SERVICE CI NEEDS', '    # END GENERATED SERVICE CI NEEDS')
LANES = {'pr': 12, 'other': 8}


def inventory(root):
    modules = sorted(p.parent.name for p in root.glob('openbank-*/build.gradle.kts'))
    if not modules or any(not re.fullmatch(r'openbank-[a-z0-9-]+', m) for m in modules):
        raise ValueError('empty or invalid canonical Gradle module inventory')
    return modules


def job_id(module, lane):
    return 'build_' + lane + '_' + module.replace('-', '_')


def expression(text):
    return '${{ ' + text + ' }}'


def caller_block(modules):
    rows = []
    for lane, width in LANES.items():
        for i, module in enumerate(modules):
            event = "github.event_name == 'pull_request'" if lane == 'pr' else "github.event_name != 'pull_request'"
            selected = f"({event}) && contains(fromJSON(needs.changes.outputs.services || '[]'), '{module}')"
            dependencies = ['changes', 'publication-ready']
            if i >= width:
                dependencies.append(job_id(modules[i - width], lane))
            condition = ("!cancelled() && needs.changes.result == 'success' && "
                         "needs.changes.outputs.any == 'true' && "
                         "(needs.publication-ready.result == 'success' || needs.publication-ready.result == 'skipped') && " + selected)
            rows += [f'  {job_id(module, lane)}:',
                     '    name: ' + expression(selected + f" && 'build ({module})' || 'Not selected ({lane}, {module})'"),
                     '    needs: [' + ', '.join(dependencies) + ']',
                     '    if: ' + expression(condition),
                     '    permissions:', '      contents: read', '      id-token: write',
                     '    uses: ./.github/workflows/_service-ci.yml',
                     '    with:', f'      service: {module}', '    secrets:',
                     '      PACT_BROKER_PASSWORD: ' + expression("(github.event_name == 'push' || github.event_name == 'workflow_dispatch') && github.ref == 'refs/heads/main' && secrets.PACT_BROKER_PASSWORD || ''"), '']
    return '\n'.join(rows).rstrip()


def needs_block(modules):
    ids = ['changes', 'publication-ready', 'verification-metadata']
    ids += [job_id(module, lane) for lane in LANES for module in modules]
    return '    needs:\n' + '\n'.join('      - ' + name for name in ids)


def replace_region(text, markers, content):
    start, end = markers
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError('generated region markers must occur exactly once')
    before, rest = text.split(start, 1)
    _, after = rest.split(end, 1)
    return before + start + '\n' + content + '\n' + end + after


def generated(text, modules):
    text = replace_region(text, CALLERS, caller_block(modules))
    return replace_region(text, NEEDS, needs_block(modules))



def validate_callee(root):
    import yaml

    document = yaml.safe_load((root / '.github/workflows/_service-ci.yml').read_text())
    jobs = document.get('jobs', {})
    if set(jobs) != {'build', 'contract', 'publish-evidence'}:
        raise ValueError('reusable platform result schema has an unknown job')
    # Only platform result states may cross the caller boundary. Child outputs could
    # contain sensitive data, so reject their introduction rather than exporting them.
    if any(job.get('outputs') for job in jobs.values()):
        raise ValueError('child outputs must not enter serialized platform results')
    trigger = document.get('on', document.get(True, {}))
    outputs = trigger.get('workflow_call', {}).get('outputs', {})
    if set(outputs) != {'platform_results'} or outputs['platform_results'].get('value') != '${{ toJSON(jobs) }}':
        raise ValueError('reusable platform result output is not the exact documented jobs context')

def self_test():
    template = '\n'.join([CALLERS[0], 'stale', CALLERS[1], NEEDS[0], 'stale', NEEDS[1]])
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for name in ['openbank-a', 'openbank-b']:
            (root / name).mkdir()
            (root / name / 'build.gradle.kts').touch()
        modules = inventory(root)
        first = generated(template, modules)
        assert first != template and generated(first, modules) == first
        (root / 'openbank-c').mkdir()
        (root / 'openbank-c/build.gradle.kts').touch()
        assert generated(first, inventory(root)) != first
        assert len([line for line in caller_block(modules).splitlines() if line.startswith('  build_')]) == 4
        try:
            generated('', modules)
        except ValueError:
            pass
        else:
            raise AssertionError('missing generation markers accepted')
    print('generator self-test: drift, new module, repeat and malformed regions pass')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    modules = inventory(ROOT)
    validate_callee(ROOT)
    path = ROOT / WORKFLOW
    source = path.read_text()
    expected = generated(source, modules)
    print(f'SUBJECTS={len(modules)}')
    if len(expected.encode()) > 500_000:
        raise ValueError('generated workflow exceeds conservative 500KB file bound')
    if args.check:
        if expected != source:
            print('Services CI caller graph drift; run python3 .github/scripts/generate-service-ci-jobs.py')
            return 1
    else:
        path.write_text(expected)
    return 0


if __name__ == '__main__':
    sys.exit(main())
