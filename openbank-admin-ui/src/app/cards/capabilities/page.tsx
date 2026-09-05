// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import Link from 'next/link'
import { Layers, ExternalLink } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import {
  getCardCapabilities,
  sandboxReadyNetworks,
  type CardCapability,
  type Availability,
} from '@/lib/cards/capabilities'

/**
 * Card Center — the capability matrix (ADR-0283 phase 3, issue #8811).
 *
 * A SERVER component: the registry is baked into the image and read with `fs`, so there is no
 * client fetch, no loading state and no way for the page to render a stale copy of the registry
 * that shipped with it.
 *
 * ## What this screen is for
 *
 * ADR-0283's promise is that a bank chooses its card network per product and can see what the
 * choice costs. This is where that is answered: which capabilities exist, what each network calls
 * them, whether a free sandbox exists or a contract is needed first, and — the column that keeps
 * the page honest — what this platform actually implements today.
 *
 * ## The one thing this page must never do
 *
 * Read like an integration status page. Every `bindings` cell says `simulator` right now, and the
 * page says so in prose as well as in the table, because a reader who mistakes the matrix for a
 * list of live integrations would plan against capabilities nobody has built. That is the same
 * discipline as giving a skipped delivery its own outcome value rather than sharing one with
 * success.
 */
export const dynamic = 'force-static'

const AVAILABILITY_LABEL: Record<Availability, string> = {
  sandbox: 'Free sandbox',
  contract: 'Contract required',
  none: 'Not offered',
}

const AVAILABILITY_TONE: Record<Availability, string> = {
  sandbox: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  contract: 'bg-amber-50 text-amber-700 border-amber-200',
  none: 'bg-slate-100 text-slate-600 border-slate-200',
}

function AvailabilityChip({ availability, product }: { availability: Availability; product: string }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-sm text-slate-800">{product}</span>
      <span
        className={`inline-flex w-fit items-center rounded border px-1.5 py-0.5 text-xs ${AVAILABILITY_TONE[availability]}`}
      >
        {AVAILABILITY_LABEL[availability]}
      </span>
    </div>
  )
}

function BindingsCell({ capability }: { capability: CardCapability }) {
  if (capability.bindings.length === 0) {
    return <span className="text-xs text-slate-500">none — see why</span>
  }
  return (
    <div className="flex flex-wrap gap-1">
      {capability.bindings.map(binding => (
        <span
          key={binding}
          className="inline-flex items-center rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-xs text-slate-700"
        >
          {binding}
        </span>
      ))}
    </div>
  )
}

export default function CardCapabilitiesPage() {
  const registry = getCardCapabilities()

  if (!registry.available) {
    // Deliberately NOT an empty table. An empty matrix renders as "this platform supports nothing",
    // which a reader cannot tell from the truth; a missing artifact is a different fact and says so.
    return (
      <div className="space-y-4">
        <PageHeader
          title="Card capability matrix"
          subtitle="What each card network offers, and what this platform binds"
          icon={<Layers className="h-6 w-6 text-slate-500" />}
        />
        <div className="rounded border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          The capability registry was not baked into this build, so there is nothing to show. This is
          a build problem, not an empty matrix: run{' '}
          <code className="rounded bg-amber-100 px-1">node scripts/generate-card-capabilities.mjs</code>{' '}
          and rebuild.
        </div>
      </div>
    )
  }

  const sandboxCount = registry.capabilities.filter(c => sandboxReadyNetworks(c).length > 0).length
  // A binding named after a network is a vendor adapter; `simulator` is the in-repo one.
  const vendorBound = registry.capabilities.filter(c =>
    c.bindings.some(b => registry.networks.some(n => n.id === b)),
  )
  const portedCount = registry.capabilities.filter(c => c.port !== null).length

  return (
    <div className="space-y-6">
      <PageHeader
        title="Card capability matrix"
        subtitle="What each card network offers, which port models it, and what this platform binds today"
        icon={<Layers className="h-6 w-6 text-slate-500" />}
        breadcrumb={
          <Link href="/cards" className="text-xs text-slate-500 hover:text-slate-700">
            Cards
          </Link>
        }
      />

      <div className="rounded border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
        <p>
          <strong>This is not an integration status page.</strong> The <em>Bindings</em> column says
          what this repository implements. A network column describes what the <em>network</em>{' '}
          offers and whether its developer sandbox is free to use, not that anything here calls it.
        </p>
        <p className="mt-2">
          {/* DERIVED, not asserted. This paragraph used to state that every binding was the
              simulator, which was true when it was written and went stale the moment the first
              vendor adapter landed — a sentence about the data, sitting next to the data, that
              nothing kept in step with it. */}
          {portedCount} of {registry.capabilities.length} capabilities have a port; {sandboxCount}{' '}
          have at least one network with a free sandbox, which is where an adapter can be built and
          exercised without a commercial agreement.{' '}
          {vendorBound.length > 0 ? (
            <>
              A vendor adapter exists for{' '}
              <strong>{vendorBound.map(c => c.label).join(', ')}</strong>. An adapter with no
              credential configured reports <code className="rounded bg-slate-100 px-1">NOT_BOUND</code>{' '}
              and makes no call — which is a different fact from a network being unreachable, and
              the two are never merged.
            </>
          ) : (
            <>Every binding today is the in-repo simulator.</>
          )}
        </p>
      </div>

      <div className="overflow-x-auto rounded border border-slate-200">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50">
            <tr>
              <th className="px-4 py-3 text-left font-medium text-slate-700">Capability</th>
              <th className="px-4 py-3 text-left font-medium text-slate-700">Port</th>
              {registry.networks.map(network => (
                <th key={network.id} className="px-4 py-3 text-left font-medium text-slate-700">
                  {network.label}
                </th>
              ))}
              <th className="px-4 py-3 text-left font-medium text-slate-700">Bindings here</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 bg-white">
            {registry.capabilities.map(capability => (
              <tr key={capability.id}>
                <td className="px-4 py-3 align-top">
                  <div className="font-medium text-slate-900">{capability.label}</div>
                  <p className="mt-1 max-w-xl text-xs text-slate-600">{capability.why}</p>
                </td>
                <td className="px-4 py-3 align-top">
                  {capability.port ? (
                    <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-800">
                      {capability.port}
                    </code>
                  ) : (
                    // A portless capability is a DECISION with a reason in the cell to its left,
                    // never an oversight — so it says "by design" rather than showing an empty cell.
                    <span className="text-xs text-slate-500">no port, by design</span>
                  )}
                </td>
                {registry.networks.map(network => {
                  const entry = capability.networks[network.id]
                  return (
                    <td key={network.id} className="px-4 py-3 align-top">
                      {entry ? (
                        <AvailabilityChip availability={entry.availability} product={entry.product} />
                      ) : (
                        <span className="text-xs text-slate-500">not registered</span>
                      )}
                    </td>
                  )
                })}
                <td className="px-4 py-3 align-top">
                  <BindingsCell capability={capability} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="rounded border border-slate-200 p-4">
        <h2 className="text-sm font-medium text-slate-900">Getting into each network&apos;s sandbox</h2>
        <ul className="mt-2 space-y-2 text-sm text-slate-700">
          {registry.networks.map(network => (
            <li key={network.id} className="flex flex-col gap-0.5">
              <a
                href={network.developerPortal}
                target="_blank"
                rel="noreferrer"
                className="inline-flex w-fit items-center gap-1 font-medium text-slate-900 hover:underline"
              >
                {network.label}
                <ExternalLink className="h-3 w-3" />
              </a>
              <span className="text-xs text-slate-600">{network.sandboxAuth}</span>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-xs text-slate-600">
          Credentials live in OpenBao and are read only by the adapter module that needs them. No
          service outside an adapter sees a network credential, and none is committed here.
        </p>
      </div>
    </div>
  )
}
