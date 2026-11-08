#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate explicit Services CI callers; no matrix result represents multiple modules."""
import argparse
import re
import statistics
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = Path('.github/workflows/services-ci.yml')
CALLERS = ('  # BEGIN GENERATED SERVICE CI CALLERS', '  # END GENERATED SERVICE CI CALLERS')
NEEDS = ('    # BEGIN GENERATED SERVICE CI NEEDS', '    # END GENERATED SERVICE CI NEEDS')
LANES = {'pr': 16, 'other': 8}
CALLEES = {'pr': '.github/workflows/_service-ci-pr.yml', 'other': '.github/workflows/_service-ci.yml'}


# Median PR build seconds from nine full-fleet carrier runs, Nov 2–3, 2026.
# Refresh offline on older runs and replay on later runs before changing these weights.
PR_BUILD_SECONDS = {
    "openbank-account-service": 639,
    "openbank-agent-service": 434,
    "openbank-aml-service": 423,
    "openbank-anacredit-service": 303,
    "openbank-analytics-sink": 286,
    "openbank-ap2-service": 271,
    "openbank-audit-service": 468,
    "openbank-authz-policy-auditor": 297,
    "openbank-balance-service": 496,
    "openbank-billing-service": 469,
    "openbank-campaign-service": 374,
    "openbank-card-issuance-service": 348,
    "openbank-card-processing-service": 494,
    "openbank-case-coordinator-agent": 369,
    "openbank-clearing-service": 413,
    "openbank-clearing-simulator": 252,
    "openbank-client-account": 161,
    "openbank-client-product-catalog": 149,
    "openbank-client-transaction": 196,
    "openbank-communication-service": 298,
    "openbank-consent-service": 432,
    "openbank-context-service": 496,
    "openbank-control-liveness-sentinel": 277,
    "openbank-copilot-service": 349,
    "openbank-customer-edge": 482,
    "openbank-delegation-service": 591,
    "openbank-devops-agent": 256,
    "openbank-dispute-service": 432,
    "openbank-docs-truth-agent": 293,
    "openbank-document-service": 607,
    "openbank-domestic-payment": 497,
    "openbank-engagement-service": 371,
    "openbank-finops-agent": 267,
    "openbank-finrep-service": 255,
    "openbank-flaky-test-hunter": 297,
    "openbank-fraud-service": 543,
    "openbank-fx-service": 465,
    "openbank-governance-auditor": 296,
    "openbank-incentive-service": 279,
    "openbank-interest-service": 421,
    "openbank-kyb-service": 345,
    "openbank-kyc-service": 542,
    "openbank-ledger-service": 683,
    "openbank-lending-service": 692,
    "openbank-libs": 126,
    "openbank-libs-benchmarks": 91,
    "openbank-libs-detekt-rules": 142,
    "openbank-libs-domain": 196,
    "openbank-libs-finance": 165,
    "openbank-libs-fincrime": 160,
    "openbank-libs-iso20022": 168,
    "openbank-libs-lending": 171,
    "openbank-libs-runtime": 273,
    "openbank-libs-temporal": 135,
    "openbank-libs-testing": 200,
    "openbank-loyalty-service": 319,
    "openbank-mcp-service": 289,
    "openbank-notification-service": 449,
    "openbank-onboarding-service": 303,
    "openbank-party-service": 533,
    "openbank-pension-fund-service": 338,
    "openbank-pension-service": 791,
    "openbank-pid-service": 398,
    "openbank-product-catalog": 583,
    "openbank-products": 195,
    "openbank-psd2-service": 437,
    "openbank-referral-service": 353,
    "openbank-release-steward": 292,
    "openbank-risk-engine": 416,
    "openbank-sanctions-service": 347,
    "openbank-sca-service": 642,
    "openbank-sdd-service": 438,
    "openbank-security-scanner": 276,
    "openbank-sepa-instant": 471,
    "openbank-sepa-payment": 486,
    "openbank-settlement-service": 474,
    "openbank-simulation": 416,
    "openbank-standing-order-service": 392,
    "openbank-statement-service": 415,
    "openbank-swift-service": 607,
    "openbank-tax-reporting-service": 496,
    "openbank-tpp-registry-service": 369,
    "openbank-transaction-service": 422,
    "openbank-treasury-service": 713,
    "openbank-vop-service": 280,
    "openbank-wealth-service": 364
}


def inventory(root):
    modules = sorted(p.parent.name for p in root.glob('openbank-*/build.gradle.kts'))
    if not modules or any(not re.fullmatch(r'openbank-[a-z0-9-]+', m) for m in modules):
        raise ValueError('empty or invalid canonical Gradle module inventory')
    return modules


def job_id(module, lane):
    return 'build_' + lane + '_' + module.replace('-', '_')


def expression(text):
    return '${{ ' + text + ' }}'


def pr_predecessors(modules, width, weights):
    # Longest estimated builds first, with stable tie-breaking and a neutral
    # estimate for new modules. The inventory, not the timing sample, selects jobs.
    default = statistics.median(weights.values())
    seconds = {module: weights.get(module, default) for module in modules}
    loads = [0] * width
    tails = [None] * width
    predecessors = {}
    for module in sorted(modules, key=lambda name: (-seconds[name], name)):
        slot = min(range(width), key=loads.__getitem__)
        predecessors[module] = tails[slot]
        tails[slot] = module
        loads[slot] += seconds[module]
    return predecessors


def caller_block(modules):
    rows = []
    for lane, width in LANES.items():
        if lane == 'pr':
            predecessors = pr_predecessors(modules, width, PR_BUILD_SECONDS)
        else:
            predecessors = dict(zip(modules[width:], modules, strict=False))
        for module in modules:
            event = "github.event_name == 'pull_request'" if lane == 'pr' else "github.event_name != 'pull_request'"
            selected = f"({event}) && contains(fromJSON(needs.changes.outputs.services || '[]'), '{module}')"
            dependencies = ['changes', 'publication-ready']
            if predecessors.get(module):
                dependencies.append(job_id(predecessors[module], lane))
            condition = ("!cancelled() && needs.changes.result == 'success' && "
                         "needs.changes.outputs.any == 'true' && "
                         "(needs.publication-ready.result == 'success' || needs.publication-ready.result == 'skipped') && " + selected)
            rows += [f'  {job_id(module, lane)}:',
                     '    name: ' + expression(selected + f" && 'build ({module})' || 'Not selected ({lane}, {module})'"),
                     '    needs: [' + ', '.join(dependencies) + ']',
                     '    if: ' + expression(condition),
                     '    permissions:', '      contents: read', '      actions: read', '      id-token: write',
                     f'    uses: ./{CALLEES[lane]}',
                     '    with:', f'      service: {module}']
            # `secrets: inherit` on the non-PR lane ONLY. In a called reusable workflow an
            # ENVIRONMENT secret (GRADLE_CACHE_WRITE_PASSWORD of `gradle-cache-writer`, ADR-0363)
            # resolves to '' unless the caller inherits (actions/runner issue 4453): measured
            # 2026-11-05, main pushes ran with an empty write password and never wrote the cache.
            # Inherit adds nothing for non-main events: _service-ci.yml reads the write secret
            # only inside the main-push environment and PACT_BROKER_PASSWORD only in its
            # `contract` job, whose `if:` is the same main-only expression this map used.
            # The PR lane keeps the explicit map, which yields '' on pull_request: PR code must
            # never receive more secrets than it does today.
            if lane == 'other':
                rows += ['    secrets: inherit', '']
            else:
                rows += ['    secrets:',
                         '      PACT_BROKER_PASSWORD: ' + expression("(github.event_name == 'push' || github.event_name == 'workflow_dispatch') && github.ref == 'refs/heads/main' && secrets.PACT_BROKER_PASSWORD || ''"), '']
    return '\n'.join(rows).rstrip()


def needs_block(modules):
    ids = ['changes', 'publication-ready', 'verification-metadata', 'renderer-tests', 'deploy-source-freshness']
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



def pr_callee_source(root):
    import copy

    import yaml
    full = yaml.safe_load((root / CALLEES['other']).read_text())
    trigger = full.get('on', full.get(True, {}))
    projection = {
        'name': '_service-ci-pr',
        'on': {'workflow_call': copy.deepcopy(trigger['workflow_call'])},
        **{key: copy.deepcopy(value) for key, value in full.items()
           if key not in {'name', 'on', True, 'jobs'}},
        'jobs': {'build': copy.deepcopy(full['jobs']['build'])},
    }
    # The omitted jobs are never applicable to pull_request: their exact source
    # predicates stay guarded by validate_callee. No executed verification is dropped.
    return ('# Generated by generate-service-ci-jobs.py from the canonical _service-ci.yml build job.\n'
            '# Edit the canonical source; this PR projection is never hand-edited.\n'
            + yaml.safe_dump(projection, sort_keys=False, width=120))


def validate_callee(root, check_projection=True):
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
    if jobs['contract'].get('if') != "(github.event_name == 'push' || github.event_name == 'workflow_dispatch') && github.ref == 'refs/heads/main'":
        raise ValueError('contract must remain inapplicable to pull requests')
    if jobs['publish-evidence'].get('if') != "always() && github.event_name == 'push' && github.ref == 'refs/heads/main' && inputs.ref == ''":
        raise ValueError('evidence publication must remain inapplicable to pull requests')
    if check_projection:
        actual = yaml.safe_load((root / CALLEES['pr']).read_text())
        if actual != yaml.safe_load(pr_callee_source(root)):
            raise ValueError('PR callee is not the exact canonical build/interface projection')

def self_test():
    template = '\n'.join([CALLERS[0], 'stale', CALLERS[1], NEEDS[0], 'stale', NEEDS[1]])
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for name in ['openbank-a', 'openbank-b']:
            (root / name).mkdir()
            (root / name / 'build.gradle.kts').touch()
        modules = inventory(root)
        first = generated(template, modules)
        # The exact-head deploy verifier must remain in the aggregate DAG after
        # regeneration; an explicit step cannot read a job omitted from needs.
        assert '      - deploy-source-freshness' in needs_block(modules)
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
    validate_callee(ROOT, check_projection=args.check)
    path = ROOT / WORKFLOW
    source = path.read_text()
    expected = generated(source, modules)
    expected_pr = pr_callee_source(ROOT)
    pr_path = ROOT / CALLEES['pr']
    print(f'SUBJECTS={len(modules)}')
    if max(len(expected.encode()), len(expected_pr.encode())) > 500_000:
        raise ValueError('generated workflow exceeds conservative 500KB file bound')
    if args.check:
        if expected != source or pr_path.read_text() != expected_pr:
            print('Services CI caller graph drift; run python3 .github/scripts/generate-service-ci-jobs.py')
            return 1
    else:
        path.write_text(expected)
        pr_path.write_text(expected_pr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
