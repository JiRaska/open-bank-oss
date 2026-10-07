#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render the existing admin catalog into the public platform page, never invent runtime status.

Run from any directory. Requires Node and the admin-ui's installed yaml dependency.
Only the marked section is generated. Business-area labels are editorial navigation,
not the governance dataDomain taxonomy. Unknown/new modules remain visible under shared.
"""
import argparse
import html
import json
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SITE = pathlib.Path(__file__).resolve().parent / 'landing'
REPO = 'https://github.com/JiRaska/open-bank-oss'
AREAS = [
    ('core', 'Accounts & ledger', 'Account lifecycle, balances, bookkeeping and interest.',
     ['account-service', 'balance-service', 'ledger-service', 'transaction-service', 'interest-service']),
    ('payments', 'Payments & settlement', 'Domestic and international rails, recurring payments and settlement.',
     ['sepa-payment', 'sepa-instant', 'domestic-payment', 'swift-service', 'sdd-service', 'standing-order-service', 'clearing-service', 'clearing-simulator', 'settlement-service', 'vop-service', 'ap2-service', 'fx-service']),
    ('identity', 'Identity & onboarding', 'People, companies, digital identity, delegated access and authentication.',
     ['party-service', 'pid-service', 'kyb-service', 'kyc-service', 'onboarding-service', 'sca-service', 'delegation-service']),
    ('cards', 'Cards & disputes', 'Card lifecycle, processing and disputes.',
     ['card-issuance-service', 'card-processing-service', 'dispute-service']),
    ('lending', 'Lending & credit risk', 'Lending workflows and the shared risk engine.',
     ['lending-service', 'risk-engine', 'libs-lending']),
    ('treasury', 'Treasury & wealth', 'Treasury and wealth-management modules.',
     ['treasury-service', 'wealth-service']),
    ('compliance', 'Financial crime & audit', 'Screening, fraud signals, sanctions and audit evidence.',
     ['aml-service', 'sanctions-service', 'fraud-service', 'audit-service']),
    ('reporting', 'Regulatory reporting', 'Financial reporting, credit reporting and tax reporting.',
     ['finrep-service', 'anacredit-service', 'tax-reporting-service']),
    ('products', 'Products & pricing', 'Product definitions, customer products and billing.',
     ['product-catalog', 'client-product-catalog', 'billing-service']),
    ('engagement', 'Customer engagement', 'Context, campaigns, incentives, loyalty and referrals.',
     ['context-service', 'engagement-service', 'campaign-service', 'incentive-service', 'loyalty-service', 'referral-service']),
    ('open-banking', 'Open banking', 'Consent, PSD2 interfaces and third-party provider registration.',
     ['consent-service', 'psd2-service', 'tpp-registry-service', 'developer-portal']),
    ('communication', 'Communication & documents', 'Notifications, communication, documents and statements.',
     ['communication-service', 'notification-service', 'document-service', 'document-renderer', 'statement-service']),
    ('channels', 'Customer & operator channels', 'The customer edge, admin portal and API gateway.',
     ['customer-edge', 'admin-ui', 'api-gateway']),
    ('operations', 'Operations & AI', 'Governed tools, specialist agents, assurance and platform operations.',
     ['agent-service', 'mcp-service', 'copilot-service', 'case-coordinator-agent', 'devops-agent', 'finops-agent', 'docs-truth-agent', 'governance-auditor', 'authz-policy-auditor', 'control-liveness-sentinel', 'release-steward', 'flaky-test-hunter', 'security-scanner']),
    ('analytics', 'Analytics & simulation', 'Analytical ingestion and simulation tooling.',
     ['analytics-sink', 'simulation']),
]

def render(catalog, revision, date):
    services = catalog['services']
    if not services or len({s['name'] for s in services}) != len(services):
        raise ValueError('Catalog must contain unique modules')
    if catalog.get('schema') != 'openbank.catalog/v1':
        raise ValueError('Unsupported catalog schema')
    by_short = {s['short']: s for s in services}
    assigned = set()
    areas = []
    for area, title, desc, names in AREAS:
        present = []
        for name in names:
            if name in assigned:
                raise ValueError(f'Duplicate area assignment: {name}')
            if name in by_short:
                present.append(by_short[name])
                assigned.add(name)
        if present:
            areas.append((area, title, desc, present))
    other = [s for s in services if s['short'] not in assigned]
    if other:
        areas.append(('shared', 'Shared libraries & delivery', 'Shared code, contracts, infrastructure and other supporting modules.', other))
    e = html.escape
    api_count = sum(bool(s['hasOpenapi']) for s in services)
    result = [f'<p class="catalog-meta">Code snapshot: {e(date)} · <a href="{REPO}/tree/{e(revision)}">{e(revision[:10])}</a> · {len(services)} repository modules · {api_count} OpenAPI specifications. Derived by the admin catalog generator; not live deployment or health data.</p>', '<div class="domain-grid">']
    for area, title, desc, modules in areas:
        result.append(f'<article class="domain-group" id="{area}"><h3>{e(title)}</h3><p>{e(desc)}</p><ul class="module-list">')
        for s in sorted(modules, key=lambda m: m['short']):
            name = s['name']
            if '/' in name or not name.startswith('openbank-'):
                raise ValueError(f'Invalid module path: {name}')
            api = f' <a class="api-link" href="{REPO}/blob/{e(revision)}/{e(name)}/src/main/resources/openapi.yaml" aria-label="{e(s["short"])} OpenAPI specification">API ↗</a>' if s['hasOpenapi'] else ''
            result.append(f'<li><a href="{REPO}/tree/{e(revision)}/{e(name)}">{e(s["short"])}</a>{api}</li>')
        result.append('</ul></article>')
    result.append('</div>')
    return '\n'.join(result)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=pathlib.Path, help='Use a pre-generated admin catalog')
    parser.add_argument('--check', action='store_true', help='Fail instead of writing if generated output differs')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        catalog_path = args.catalog or pathlib.Path(tmp) / 'catalog.json'
        if not args.catalog:
            subprocess.run(['node', str(ROOT / 'openbank-admin-ui/scripts/generate-catalog.mjs'), '--repo', str(ROOT), '--out', str(catalog_path)], check=True)
        catalog = json.loads(catalog_path.read_text())
    # Track the catalog inputs, not an unrelated CSS commit: publishing doesn't stale itself.
    source_paths = ['openbank-*/version.txt', 'openbank-*/build.gradle.kts', 'openbank-*/package.json', 'openbank-*/src/main/resources/openapi.yaml', 'openbank-libs/governance/rules.yaml']
    revision = subprocess.check_output(['git', 'log', '-1', '--format=%H', '--', *source_paths], cwd=ROOT, text=True).strip()
    date = subprocess.check_output(['git', 'show', '-s', '--format=%cs', revision], cwd=ROOT, text=True).strip()
    target = SITE / 'platform.html'
    text = target.read_text()
    start, end = '<!-- CATALOG:START -->', '<!-- CATALOG:END -->'
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError('Missing or ambiguous catalog markers')
    before, rest = text.split(start)
    _, after = rest.split(end)
    output = before + start + '\n' + render(catalog, revision, date) + '\n' + end + after
    if args.check:
        if output != text:
            raise SystemExit('Public catalog is stale: run generate-public-catalog.py')
    else:
        target.write_text(output)
    print(f'Public catalog: {len(catalog["services"])} modules; {"verified" if args.check else "rendered"}.')

if __name__ == '__main__':
    main()
