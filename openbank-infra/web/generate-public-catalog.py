#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render the existing admin catalog into the public platform page, never invent runtime status.

Run from any directory. Requires Node and the admin-ui's installed yaml dependency.
Only the marked section is generated. Business ownership comes from catalog.dataDomain;
labels and descriptions come from the shared domain taxonomy. Unclassified supporting
modules remain explicitly visible instead of being attributed to the technical platform.
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
TAXONOMY = ROOT / 'openbank-libs/governance/domain-taxonomy.json'


def load_domains():
    taxonomy = json.loads(TAXONOMY.read_text())
    if taxonomy.get('schema') != 'openbank.domain-taxonomy/v1':
        raise ValueError('Unsupported domain taxonomy schema')
    domains = taxonomy['domains']
    if len({d['id'] for d in domains}) != len(domains):
        raise ValueError('Domain taxonomy must contain unique domains')
    return domains


def render(catalog, revision, date):
    services = catalog['services']
    if not services or len({s['name'] for s in services}) != len(services):
        raise ValueError('Catalog must contain unique modules')
    if catalog.get('schema') != 'openbank.catalog/v1':
        raise ValueError('Unsupported catalog schema')
    domains = load_domains()
    by_domain = {d['id']: [] for d in domains}
    unclassified = []
    for service in services:
        domain = service.get('dataDomain')
        if domain is None:
            unclassified.append(service)
        elif domain not in by_domain:
            raise ValueError(f'Unknown domain for {service["name"]}: {domain}')
        else:
            by_domain[domain].append(service)
    areas = [(d['id'], d['labelEn'], d['description'], by_domain[d['id']])
             for d in domains if by_domain[d['id']]]
    if unclassified:
        areas.append(('unclassified', 'Unclassified modules',
                      'Modules without a declared business owner; this is not a technical-platform assignment.',
                      unclassified))
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
    source_paths = ['openbank-*/version.txt', 'openbank-*/build.gradle.kts', 'openbank-*/package.json', 'openbank-*/src/main/resources/openapi.yaml', 'openbank-libs/governance/rules.yaml', 'openbank-*/governance.yaml', 'openbank-libs/governance/domain-taxonomy.json', 'openbank-admin-ui/scripts/generate-catalog.mjs', 'openbank-infra/web/generate-public-catalog.py']
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
