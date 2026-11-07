// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { parse as parseYaml } from 'yaml'
import { navigationAreas } from '@/lib/navigation/catalog'
import { Sidebar } from '@/components/layout/Sidebar'
import { DomainOwnershipProvider, type DomainOwnership } from '@/components/layout/DomainOwnershipContext'
import { DOMAIN_GROUPS, domainLabel, isDomainId } from '@/lib/governance/domains'
import { ROLES } from '@/lib/auth/roles'
import { buildRegistry } from '@/lib/services/registry'
import { readSidebarDestinations } from '../../e2e/helpers/navigation-inventory'

const state = vi.hoisted(() => ({ language: 'en' as 'en' | 'cs', roles: ['ROLE_ADMIN'], pathname: '/dashboard' }))
vi.mock('next/navigation', () => ({ usePathname: () => state.pathname }))
vi.mock('next-auth/react', () => ({ useSession: () => ({ data: { user: { id: 'domain-test', roles: state.roles } } }) }))
vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: state.language, t: (cs: string, en: string) => state.language === 'cs' ? cs : en }),
}))

// Read the authoritative declarations, independently of the sidebar's owner tags.
// A source ownership change must move the navigation without changing this fixture.
const ownershipByModule: DomainOwnership = Object.fromEntries(readdirSync(resolve(process.cwd(), '..'))
  .filter(name => name.startsWith('openbank-') && existsSync(resolve('..', name, 'governance.yaml')))
  .map(name => {
    const declaration = parseYaml(readFileSync(resolve('..', name, 'governance.yaml'), 'utf8'))
    if (!isDomainId(declaration.dataDomain)) throw new Error(`${name}: invalid source domain`)
    return [name, declaration.dataDomain]
  }))
const catalog = JSON.parse(readFileSync(resolve(process.cwd(), 'catalog.json'), 'utf8'))
const sourceOwnership: DomainOwnership = Object.fromEntries(buildRegistry(catalog.services)
  .map(service => [service.id, ownershipByModule[service.container] ?? 'unclassified']))

function mount(ownership = sourceOwnership) {
  return render(<DomainOwnershipProvider ownership={ownership}><Sidebar /></DomainOwnershipProvider>)
}

function destinations() {
  return [...new Set(within(screen.getByRole('navigation')).getAllByRole('link')
    .map(link => link.getAttribute('href')))].sort()
}

function selectArea(label: string) {
  fireEvent.click(screen.getByRole('button', { name: /Workspace area|Pracovní oblast/ }))
  fireEvent.click(within(document.getElementById('navigation-areas')!).getByRole('button', { name: label, exact: true }))
}

beforeEach(() => {
  localStorage.clear()
  state.language = 'en'
  state.roles = [ROLES.ADMIN]
  state.pathname = '/dashboard'
})
afterEach(cleanup)

describe('sidebar business-domain ownership', () => {
  it.each(['en', 'cs'] as const)('groups business routes by source ownership with canonical %s labels', language => {
    state.language = language
    mount()
    const examples = [
      ['/accounts', 'openbank-account-service'],
      ['/interest', 'openbank-interest-service'],
      ['/transactions', 'openbank-transaction-service'],
      ['/ledger', 'openbank-ledger-service'],
      ['/day-end', 'openbank-ledger-service'],
      ['/merchants', 'openbank-transaction-service'],
      ['/lending', 'openbank-lending-service'],
      ['/lending/risk', 'openbank-lending-service'],
      ['/balance-sheet/ledger-backfill', 'openbank-lending-service'],
      ['/balance-sheet/snapshots', 'openbank-risk-engine'],
      ['/balance-sheet/curve-sets', 'openbank-risk-engine'],
      ['/product-catalog', 'openbank-product-catalog'],
      ['/product-studio', 'openbank-product-catalog'],
      ['/fees', 'openbank-product-catalog'],
      ['/cards', 'openbank-card-issuance-service'],
      ['/disputes', 'openbank-dispute-service'],
      ['/pension', 'openbank-pension-service'],
      ['/pension/funds', 'openbank-pension-fund-service'],
      ['/treasury/deals', 'openbank-treasury-service'],
      ['/identity-cases', 'openbank-pid-service'],
      ['/kyc', 'openbank-kyc-service'],
      ['/campaigns', 'openbank-campaign-service'],
      ['/notifications', 'openbank-notification-service'],
      ['/communication', 'openbank-communication-service'],
      ['/loyalty', 'openbank-loyalty-service'],
      ['/consents', 'openbank-consent-service'],
      ['/document-templates', 'openbank-document-service'],
      ['/regulatory', 'openbank-finrep-service'],
    ]
    for (const [href, module] of examples) {
      selectArea(domainLabel(ownershipByModule[module], language))
      const region = screen.getByRole('region', { name: domainLabel(ownershipByModule[module], language) })
      expect(within(region).getAllByRole('link').map(link => link.getAttribute('href')), href).toContain(href)
    }
    const labels = screen.getAllByRole('region').map(region => region.getAttribute('aria-label'))
    expect(labels).toEqual(DOMAIN_GROUPS.map(domain => domainLabel(domain, language)).filter(label => labels.includes(label)))
  })

  it('retains every inventoried destination for the declared role union', () => {
    state.roles = Object.values(ROLES).filter(role => role !== ROLES.DEMO)
    mount()
    const found = new Set(destinations())
    for (const area of navigationAreas(state.roles, sourceOwnership)) {
      selectArea(area.nameEn)
      for (const href of destinations()) found.add(href)
    }
    expect([...found].sort()).toEqual([...new Set(readSidebarDestinations().map(item => item.href))].sort())
    expect(screen.queryByRole('region', { name: 'Unclassified', exact: true })).not.toBeInTheDocument()
  })

  it('uses reviewed ownership updates without changing the route or access permission', () => {
    state.roles = [ROLES.VIEWER]
    mount({ ...sourceOwnership, account: 'wealth' })
    selectArea(domainLabel('wealth', 'en'))
    const wealth = screen.getByRole('region', { name: domainLabel('wealth', 'en') })
    expect(within(wealth).getByRole('link', { name: 'Accounts', exact: true })).toHaveAttribute('href', '/accounts')
    expect(screen.queryByRole('region', { name: domainLabel('pensions', 'en') })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Pension contracts', exact: true })).not.toBeInTheDocument()
  })

  it.each([
    ['/fees', 'Fees', 'product-catalog', 'billing'],
    ['/cards', 'Cards', 'card-issuance', 'card-processing'],
  ])('follows the exact owning service for %s even when a sibling currently shares its domain', (href, label, owner, sibling) => {
    mount({ ...sourceOwnership, [owner]: 'wealth', [sibling]: 'treasury' })
    selectArea(domainLabel('wealth', 'en'))
    const owningRegion = screen.getByRole('region', { name: domainLabel('wealth', 'en') })
    expect(within(owningRegion).getByRole('link', { name: label, exact: true })).toHaveAttribute('href', href)
    selectArea(domainLabel('treasury', 'en'))
    const siblingRegion = screen.getByRole('region', { name: domainLabel('treasury', 'en') })
    expect(within(siblingRegion).queryByRole('link', { name: label, exact: true })).not.toBeInTheDocument()
  })

  it('shows missing ownership as Unclassified instead of assigning Technical platform', () => {
    const ownership = { ...sourceOwnership }
    delete ownership.account
    mount(ownership)
    selectArea(domainLabel('unclassified', 'en'))
    const unknown = screen.getByRole('region', { name: domainLabel('unclassified', 'en') })
    expect(within(unknown).getByRole('link', { name: 'Accounts', exact: true })).toHaveAttribute('href', '/accounts')
    const platform = screen.queryByRole('region', { name: domainLabel('platform', 'en') })
    if (platform) expect(within(platform).queryByRole('link', { name: 'Accounts', exact: true })).not.toBeInTheDocument()
  })

  it('does not expand role access when business links are regrouped', () => {
    state.roles = [ROLES.RISK]
    mount()
    const original = navigationAreas(state.roles, sourceOwnership).flatMap(area => area.items.map(item => item.href)).sort()
    expect(original).toContain('/balance-sheet/snapshots')
    expect(original).not.toContain('/balance-sheet/ledger-backfill')
    expect(original).not.toContain('/treasury/deals/new')
    expect(original).not.toContain('/pension')
    cleanup()
    mount(Object.fromEntries(Object.keys(sourceOwnership).map(module => [module, 'wealth'])) as DomainOwnership)
    expect(navigationAreas(state.roles, Object.fromEntries(Object.keys(sourceOwnership).map(module => [module, 'wealth'])) as DomainOwnership).flatMap(area => area.items.map(item => item.href)).sort()).toEqual(original)
    expect(screen.queryByRole('region', { name: domainLabel('pensions', 'en') })).not.toBeInTheDocument()
  })

  it('preserves task workspace shortcuts and announces only the business destination current', () => {
    state.roles = [ROLES.OPERATOR]
    state.pathname = '/accounts/example'
    mount()
    expect(screen.getAllByRole('link', { name: 'Accounts', exact: true })).toHaveLength(2)
    const current = screen.getAllByRole('link', { current: 'page' })
    expect(current).toHaveLength(1)
    expect(current[0]).toHaveAttribute('href', '/accounts')
    expect(screen.getByRole('region', { name: domainLabel('core', 'en') })).toContainElement(current[0])
  })
})
