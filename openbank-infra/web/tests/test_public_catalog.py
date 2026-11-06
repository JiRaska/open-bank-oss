# SPDX-License-Identifier: Apache-2.0
"""Offline ownership integration checks for the generated public inventory."""
import importlib.util
import pathlib
import unittest

GENERATOR = pathlib.Path(__file__).resolve().parents[1] / 'generate-public-catalog.py'
spec = importlib.util.spec_from_file_location('public_catalog', GENERATOR)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def service(short, domain):
    return {'name': f'openbank-{short}', 'short': short, 'hasOpenapi': True,
            'dataDomain': domain}


def catalog(*services):
    return {'schema': 'openbank.catalog/v1', 'services': list(services)}


class PublicDomainCatalogTest(unittest.TestCase):
    def render(self, *services):
        return module.render(catalog(*services), 'a' * 40, '2026-11-06')

    def test_pensions_wealth_and_treasury_are_separate_from_platform(self):
        rendered = self.render(service('pension-service', 'pensions'),
                               service('pension-fund-service', 'pensions'),
                               service('wealth-service', 'wealth'),
                               service('treasury-service', 'treasury'),
                               service('devops-agent', 'platform'))
        for domain, expected in [('pensions', ['pension-service', 'pension-fund-service']),
                                 ('wealth', ['wealth-service']),
                                 ('treasury', ['treasury-service']),
                                 ('platform', ['devops-agent'])]:
            section = rendered.split(f'id="{domain}"', 1)[1].split('</article>', 1)[0]
            actual = [name for name in ['pension-service', 'pension-fund-service',
                                       'wealth-service', 'treasury-service', 'devops-agent']
                      if f'>{name}</a>' in section]
            self.assertCountEqual(actual, expected)

    def test_membership_and_labels_come_from_canonical_inputs(self):
        rendered = self.render(service('new-investment-context', 'wealth'))
        domain = next(d for d in module.load_domains() if d['id'] == 'wealth')
        self.assertIn(f'<h3>{domain["labelEn"]}</h3>', rendered)
        self.assertIn(domain['description'], rendered)
        self.assertEqual(rendered.count('>new-investment-context</a>'), 1)

    def test_undeclared_module_is_explicitly_unclassified(self):
        rendered = self.render(service('support-library', None))
        self.assertIn('id="unclassified"', rendered)
        self.assertNotIn('id="platform"', rendered)

    def test_unknown_domain_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown domain'):
            self.render(service('pension-service', 'invented'))

    def test_duplicate_module_and_invalid_path_are_rejected(self):
        item = service('wealth-service', 'wealth')
        with self.assertRaisesRegex(ValueError, 'unique modules'):
            self.render(item, item)
        with self.assertRaisesRegex(ValueError, 'Invalid module path'):
            self.render(service('../bad', 'platform'))


if __name__ == '__main__':
    unittest.main()
