// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { createHash } from 'node:crypto'
import { mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { ROLES } from '../src/lib/auth/roles'
import { signInWithRoles } from './helpers/auth'
import { waitForSettledShell } from './helpers/shell'
import { DYNAMIC_ROUTE_CLASSIFICATIONS, readAppPageRoutes, readSidebarDestinations } from './helpers/navigation-inventory'

const sidebarDestinations = readSidebarDestinations()
const uniqueDestinations = [...new Map(sidebarDestinations.map(item => [item.href, item])).values()]
const externalDestinations = uniqueDestinations.filter(item => item.external).map(item => item.href).sort()
const appDestinations = uniqueDestinations.filter(item => !item.external).map(item => item.href).sort()

type NavigationOutcome = 'not-run' | 'attempted' | 'render-observed' | 'navigation-failed' | 'classified-only'
type DataOutcome = 'not-verified' | 'unavailable' | 'access-blocked' | 'not-exercised'

function navigationEvidence() {
  if (uniqueDestinations.length > 512 || uniqueDestinations.some(({ href }) =>
    href.length > 256 || !/^\/[a-zA-Z0-9/_-]*$/.test(href) || href.includes('..'))) {
    throw new Error('Navigation evidence requires a bounded static route inventory')
  }
  return {
    schema: 'openbank.admin-ui.navigation-evidence/v1',
    scope: 'local-render-smoke',
    // Environment metadata describes this CI source, never an observed deployed build.
    sourceSha: /^[a-f0-9]{40}$/.test(process.env.GITHUB_SHA ?? '') ? process.env.GITHUB_SHA : null,
    providerDataVerified: false,
    operationsVerified: false,
    attemptsCompleted: false,
    testAssertions: 'incomplete' as 'incomplete' | 'completed',
    globalFailureCounts: null as null | { pageErrors: number; routeResponses: number; unexpectedWrites: number },
    targets: [...uniqueDestinations].sort((a, b) => a.href < b.href ? -1 : a.href > b.href ? 1 : 0).map(item => ({
      target: item.href,
      category: item.external ? 'protected-tool' as const : 'internal' as const,
      navigation: (item.external ? 'classified-only' : 'not-run') as NavigationOutcome,
      data: (item.external ? 'not-exercised' : 'not-verified') as DataOutcome,
    })),
  }
}

function evidenceFilename(info: { project: { name: string }; retry: number; workerIndex: number; parallelIndex: number }) {
  const project = info.project.name.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 64) || 'unnamed'
  const projectHash = createHash('sha256').update(info.project.name).digest('hex').slice(0, 12)
  const indexes = [info.retry, info.workerIndex, info.parallelIndex]
  if (indexes.some(value => !Number.isSafeInteger(value) || value < 0)) throw new Error('Invalid evidence execution indexes')
  return `sidebar-navigation-${project}-${projectHash}-retry${info.retry}-worker${info.workerIndex}-parallel${info.parallelIndex}.json`
}

function persistNavigationEvidence(
  evidence: ReturnType<typeof navigationEvidence>, outputDirectory: string, filename: string,
  failures: { pageErrors: string[]; routeResponses: string[]; unexpectedWrites: string[] },
) {
  evidence.globalFailureCounts = { pageErrors: failures.pageErrors.length, routeResponses: failures.routeResponses.length, unexpectedWrites: failures.unexpectedWrites.length }
  mkdirSync(outputDirectory, { recursive: true })
  const outputPath = join(outputDirectory, filename)
  const bytes = JSON.stringify(evidence, null, 2) + '\n'
  if (Buffer.byteLength(bytes) > 256 * 1024) throw new Error('Navigation evidence exceeds bounded size')
  writeFileSync(outputPath + '.tmp', bytes, { mode: 0o600 })
  renameSync(outputPath + '.tmp', outputPath)
}

test('sidebar destinations are classified against the App Router inventory', () => {
  const appRoutes = readAppPageRoutes()
  const missingPages = appDestinations.filter(href => !appRoutes.includes(href))
  const wronglyExternal = externalDestinations.filter(href => appRoutes.includes(href))
  const dynamicRoutes = appRoutes.filter(route => route.includes('['))
  const unclassifiedDynamic = dynamicRoutes.filter(route => !DYNAMIC_ROUTE_CLASSIFICATIONS[route])
  const staleDynamic = Object.keys(DYNAMIC_ROUTE_CLASSIFICATIONS).filter(route => !dynamicRoutes.includes(route))

  expect(missingPages, 'Every in-app sidebar target needs a matching static page.tsx').toEqual([])
  expect(wronglyExternal, 'An external tool target must not also resolve as an App Router page').toEqual([])
  expect(unclassifiedDynamic, 'New dynamic pages need an explicit fixture/route classification').toEqual([])
  expect(staleDynamic, 'Remove classifications for dynamic pages that no longer exist').toEqual([])
  expect(externalDestinations).toEqual(['/tools/alertmanager', '/tools/grafana', '/tools/pyrra'])
})

test('navigation evidence preserves the complete source set without claiming data or tool coverage', () => {
  const evidence = navigationEvidence()
  expect(evidence.scope).toBe('local-render-smoke')
  expect(evidence.providerDataVerified).toBe(false)
  expect(evidence.operationsVerified).toBe(false)
  expect(evidence.attemptsCompleted).toBe(false)
  expect(evidence.globalFailureCounts).toBeNull()
  expect(evidence.targets.filter(item => item.category === 'internal').map(item => item.target).sort()).toEqual(appDestinations)
  expect(evidence.targets.filter(item => item.category === 'internal').every(item => item.navigation === 'not-run' && item.data === 'not-verified')).toBe(true)
  expect(evidence.targets.filter(item => item.category === 'protected-tool').map(item => item.target).sort()).toEqual(externalDestinations)
  expect(evidence.targets.filter(item => item.category === 'protected-tool').every(item => item.navigation === 'classified-only' && item.data === 'not-exercised')).toBe(true)
  const info = { project: { name: '../browser/project' }, retry: 0, workerIndex: 0, parallelIndex: 0 }
  const name = evidenceFilename(info)
  expect(name).toMatch(/^sidebar-navigation-[a-zA-Z0-9_-]+-retry0-worker0-parallel0\.json$/)
  expect(name).not.toContain('..')
  expect(evidenceFilename({ ...info, project: { name: '.._browser_project' } })).not.toBe(name)
  expect(evidenceFilename({ ...info, retry: 1 })).not.toBe(name)
  expect(evidenceFilename({ ...info, workerIndex: 1 })).not.toBe(name)
  expect(() => evidenceFilename({ ...info, retry: -1 })).toThrow()
})

test('partial navigation checkpoints retain failing counts without response or session data', () => {
  const directory = mkdtempSync(join(tmpdir(), 'navigation-evidence-'))
  try {
    const evidence = navigationEvidence()
    const filename = evidenceFilename({ project: { name: 'fixture' }, retry: 0, workerIndex: 0, parallelIndex: 0 })
    const failures = { pageErrors: [] as string[], routeResponses: [] as string[], unexpectedWrites: [] as string[] }
    persistNavigationEvidence(evidence, directory, filename, failures)
    evidence.targets.find(item => item.category === 'internal')!.navigation = 'attempted'
    failures.pageErrors.push('response-body-secret')
    failures.routeResponses.push('session-secret')
    // Equivalent to a timeout/setup failure checkpoint: the overall run never completed.
    persistNavigationEvidence(evidence, directory, filename, failures)
    const bytes = readFileSync(join(directory, filename), 'utf8')
    const partial = JSON.parse(bytes)
    expect(partial.attemptsCompleted).toBe(false)
    expect(partial.testAssertions).toBe('incomplete')
    expect(partial.globalFailureCounts).toEqual({ pageErrors: 1, routeResponses: 1, unexpectedWrites: 0 })
    expect(partial.targets.filter((item: { navigation: string }) => item.navigation === 'attempted')).toHaveLength(1)
    expect(partial.targets.some((item: { navigation: string }) => item.navigation === 'not-run')).toBe(true)
    expect(bytes).not.toContain('secret')
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})

test('navigation smoke: every in-app sidebar click settles on its route', async ({ page, context, baseURL }, testInfo) => {
  test.setTimeout(8 * 60 * 1000)

  const evidence = navigationEvidence()
  const outputDirectory = resolve(process.cwd(), 'build/test-results/e2e')
  const pageErrors: string[] = []
  const badRouteResponses: string[] = []
  const unexpectedWriteRequests: string[] = []
  const persistEvidence = () => persistNavigationEvidence(evidence, outputDirectory, evidenceFilename(testInfo), {
    pageErrors, routeResponses: badRouteResponses, unexpectedWrites: unexpectedWriteRequests,
  })
  // Persist the complete not-run inventory before the first navigation. Incremental
  // atomic checkpoints survive a later timeout; finally also handles setup failures.
  persistEvidence()
  try {
    // These roles expose the complete menu. ROLE_DEMO is deliberately omitted because
    // Alertmanager is correctly locked for that public account.
    const allNavigationRoles = Object.values(ROLES).filter(role => role !== ROLES.DEMO && role !== ROLES.API)
    await signInWithRoles(context, baseURL!, allNavigationRoles)

    let activeHref = '/dashboard'
    page.on('pageerror', error => pageErrors.push(`${activeHref}: ${error.message}`))
    page.on('request', request => {
      const url = new URL(request.url())
      if (url.origin === new URL(baseURL!).origin && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
        const readOnlyAgentDiscovery = url.pathname === '/api/agent/mcp' && (() => {
          try {
            const method = (request.postDataJSON() as { method?: unknown } | null)?.method
            return method === 'initialize' || method === 'tools/list'
          } catch {
            return false
          }
        })()
        const statelessPreview = url.pathname === '/api/svc/document-service/api/v1/documents/templates/preview'
        if (url.pathname !== '/api/telemetry/traces' && !readOnlyAgentDiscovery && !statelessPreview) {
          unexpectedWriteRequests.push(`${activeHref}: ${request.method()} ${url.pathname}`)
        }
      }
    })
    // Keep the smoke deterministic and prevent page-mounted POSTs from reaching application
    // APIs. Only telemetry, the stateless document preview, and MCP discovery are allowed; every
    // other same-origin write receives 405 and is reported by unexpectedWriteRequests above.
    await page.route('**/api/**', async route => {
      const request = route.request()
      const url = new URL(request.url())
      if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
        if (url.pathname === '/api/telemetry/traces') return route.fulfill({ status: 204 })
        if (url.pathname === '/api/svc/document-service/api/v1/documents/templates/preview') {
          return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"preview_fixture_unavailable"}' })
        }
        if (url.pathname === '/api/agent/mcp') {
          let method: unknown
          let id: unknown
          try {
            const body = request.postDataJSON() as { method?: unknown; id?: unknown } | null
            method = body?.method
            id = body?.id ?? null
          } catch {
            method = undefined
          }
          if (method === 'initialize') {
            return route.fulfill({
              status: 200,
              contentType: 'application/json',
              body: JSON.stringify({ jsonrpc: '2.0', id, result: { serverInfo: { name: 'smoke-agent', version: '0' }, protocolVersion: '2024-11-05' } }),
            })
          }
          if (method === 'tools/list') {
            return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ jsonrpc: '2.0', id, result: { tools: [] } }) })
          }
        }
        return route.fulfill({ status: 405, contentType: 'application/json', body: '{"error":"read_only_navigation_smoke"}' })
      }
      return route.continue()
    })
    page.on('response', response => {
      const url = new URL(response.url())
      if (url.origin === new URL(baseURL!).origin && url.pathname === activeHref && response.status() >= 400) {
        badRouteResponses.push(`${activeHref}: HTTP ${response.status()}`)
      }
    })

    await page.goto('/dashboard')
    await waitForSettledShell(page)

    const sidebar = page.locator('nav.ob-sidebar-nav')
    const expectedMenuHrefs = uniqueDestinations.map(item => item.href).sort()
    const waitForCompleteSidebar = async () => {
      await expect.poll(() => sidebar.locator('a[href]').evaluateAll(elements =>
        [...new Set(elements.map(element => element.getAttribute('href')).filter((href): href is string => Boolean(href)))].sort(),
      ), { timeout: 20_000, message: 'authenticated navigation must expose every source destination' })
        .toEqual(expectedMenuHrefs)
    }
    await waitForCompleteSidebar()

    const main = page.locator('#main-content')
    const failures: string[] = []
    for (const href of appDestinations) {
      activeHref = href
      const targetEvidence = evidence.targets.find(item => item.target === href)!
      targetEvidence.navigation = 'attempted'
      persistEvidence()
      try {
        await test.step(`click ${href}`, async () => {
          const link = sidebar.locator(`a[href="${href}"]`).first()
          expect(await sidebar.locator(`a[href="${href}"]`).count(), `sidebar link ${href}`).toBeGreaterThan(0)
          const previousMainText = (await main.innerText()).trim()
          await page.evaluate(() => {
            ;(window as Window & { __obShellStableFrames?: number }).__obShellStableFrames = 0
          })
          await link.click({ timeout: 8_000 })
          await expect.poll(() => new URL(page.url()).pathname, { timeout: 12_000, message: `URL after clicking ${href}` })
            .toBe(href)
          await waitForSettledShell(page)
          await expect(sidebar.locator(`a[href="${href}"][aria-current="page"]`).first(), `${href} active navigation state`)
            .toBeVisible()
          await expect(main, `${href} main content`).toBeVisible()
          await expect.poll(() => main.innerText().then(text => text.trim()), {
            timeout: 12_000,
            message: `${href} must replace the prior route's main content`,
          }).not.toBe(previousMainText)

          const h1 = main.getByRole('heading', { level: 1 }).first()
          if (await h1.count()) {
            await expect(h1, `${href} primary heading`).toBeVisible()
            expect((await h1.innerText()).trim(), `${href} heading text`).not.toBe('')
          } else {
            expect((await main.innerText()).trim().length, `${href} meaningful main content`).toBeGreaterThan(15)
          }

          await expect(page.getByRole('alert').filter({
            hasText: /This screen failed to render|Tuto obrazovku se nepodařilo zobrazit|The console failed to load|Konzoli se nepodařilo načíst/i,
          }), `${href} render error boundary`).toHaveCount(0)
          await expect(main.getByRole('heading', {
            name: /^(404|page not found|this page could not be found\.?|stránka nebyla nalezena)$/i,
          }), `${href} not-found page`).toHaveCount(0)
        })
        targetEvidence.navigation = 'render-observed'
        // Rendered fallback/role messages are not evidence of working provider data.
        if (await main.getByText(/^(Access denied|Přístup odepřen|Session expired|Vypršela relace)$/i).count()) {
          targetEvidence.data = 'access-blocked'
        } else if (await main.getByText(/is not responding|is not deployed in this environment|Deployment status is unknown|Failed to load|neodpovídá|není v tomto prostředí nasazená|Stav nasazení nelze ověřit|Načtení selhalo/i).count()) {
          targetEvidence.data = 'unavailable'
        }
      } catch (error) {
        targetEvidence.navigation = 'navigation-failed'
        failures.push(`${href}: ${error instanceof Error ? error.message : String(error)}`)
        // Recover using a full navigation so one broken page does not hide later targets.
        try {
          activeHref = '/dashboard'
          await page.goto('/dashboard')
          await waitForSettledShell(page)
          await waitForCompleteSidebar()
        } catch (recoveryError) {
          failures.push(`dashboard recovery after ${href}: ${recoveryError instanceof Error ? recoveryError.message : String(recoveryError)}`)
          break
        }
      } finally {
        persistEvidence()
      }
    }

    evidence.attemptsCompleted = evidence.targets.filter(item => item.category === 'internal').every(item => item.navigation !== 'not-run' && item.navigation !== 'attempted')
    expect(badRouteResponses, 'A clicked App Router page must not return an HTTP error').toEqual([])
    expect(pageErrors, 'A sidebar navigation must not produce uncaught browser errors').toEqual([])
    expect(unexpectedWriteRequests, 'Navigation must not attempt business mutations or unclassified POSTs').toEqual([])
    expect(failures, 'Every in-app sidebar destination must load through a real click').toEqual([])
    evidence.testAssertions = 'completed'
  } finally {
    persistEvidence()
  }
})
