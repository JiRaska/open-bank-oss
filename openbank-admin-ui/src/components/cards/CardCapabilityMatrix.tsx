// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { Layers, ExternalLink } from 'lucide-react'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'
// From `capability-registry`, NOT from `capabilities`: the latter imports `fs` and a client
// component that reaches it fails the build with `Module not found: Can't resolve 'fs'`.
import {
  sandboxReadyNetworks,
  type CardCapability,
  type CardCapabilityRegistry,
  type Availability,
} from '@/lib/cards/capability-registry'

/**
 * The Card Center matrix, rendered (ADR-0283 phase 3, issue #8811).
 *
 * ## Why the screen is split in two
 *
 * The registry is baked into the image and read with `fs`, which only a server component can do;
 * `useLanguage` is a client hook, which only a client component can call. So the page stays a
 * server component that reads the registry, and this component renders it. Neither half can do the
 * other's job, and merging them would mean choosing between a stale-proof read and a bilingual
 * screen — the admin UI requires both.
 *
 * ## The one thing this screen must never do
 *
 * Read like an integration status page. Every `bindings` cell says `simulator` today, and the copy
 * says so in prose as well as in the table, because a reader who mistakes the matrix for a list of
 * live integrations would plan against capabilities nobody has built. Same discipline as giving a
 * skipped delivery its own outcome value instead of sharing one with success.
 */
export function CardCapabilityMatrix({ registry }: { registry: CardCapabilityRegistry }) {
  const { t } = useLanguage()

  const availabilityLabel: Record<Availability, string> = {
    sandbox: t('Sandbox zdarma', 'Free sandbox'),
    contract: t('Nutná smlouva', 'Contract required'),
    none: t('Nenabízí se', 'Not offered'),
  }

  const title = t('Matice karetních schopností', 'Card capability matrix')

  if (!registry.available) {
    // Deliberately NOT an empty table. An empty matrix reads as "this platform supports nothing",
    // which a reader cannot tell from the truth; a missing artifact is a different fact, and the
    // screen says which one it is.
    return (
      <div className="space-y-4">
        <PageHeader
          title={title}
          subtitle={t(
            'Co která karetní síť nabízí a co z toho platforma implementuje',
            'What each card network offers, and what this platform binds',
          )}
          icon={<Layers className="h-6 w-6 text-slate-500" />}
        />
        <div className="rounded border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          {t(
            'Registr schopností nebyl zabudován do tohoto buildu, takže není co zobrazit. Nejde o prázdnou matici, ale o chybu buildu: spusťte',
            'The capability registry was not baked into this build, so there is nothing to show. This is a build problem, not an empty matrix: run',
          )}{' '}
          <code className="rounded bg-amber-100 px-1">{GENERATE_COMMAND}</code>{' '}
          {t('a build zopakujte.', 'and rebuild.')}
        </div>
      </div>
    )
  }

  const sandboxCount = registry.capabilities.filter(c => sandboxReadyNetworks(c).length > 0).length
  const portedCount = registry.capabilities.filter(c => c.port !== null).length

  return (
    <div className="space-y-6">
      <PageHeader
        title={title}
        subtitle={t(
          'Co která síť nabízí, který port to modeluje a co platforma dnes skutečně implementuje',
          'What each card network offers, which port models it, and what this platform binds today',
        )}
        icon={<Layers className="h-6 w-6 text-slate-500" />}
        breadcrumb={
          <Link href="/cards" className="text-xs text-slate-500 hover:text-slate-700">
            {t('Karty', 'Cards')}
          </Link>
        }
      />

      <div className="rounded border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
        <p>
          <strong>
            {t('Toto není přehled stavu integrací.', 'This is not an integration status page.')}
          </strong>{' '}
          {t(
            'Sloupec Implementace říká, co obsahuje tento repozitář — dnes je to u každé schopnosti vestavěný simulátor. Sloupec sítě popisuje, co nabízí síť a zda je její vývojářský sandbox volně dostupný, nikoli že to odsud někdo volá.',
            'The Bindings column says what this repository implements — today that is the in-repo simulator for every capability. A network column describes what the network offers and whether its developer sandbox is free to use, not that anything here calls it.',
          )}
        </p>
        <p className="mt-2">
          {t(
            `Port má ${portedCount} z ${registry.capabilities.length} schopností; ${sandboxCount} má alespoň jednu síť s volným sandboxem, kde lze adaptér postavit a vyzkoušet bez obchodní smlouvy.`,
            `${portedCount} of ${registry.capabilities.length} capabilities have a port; ${sandboxCount} have at least one network with a free sandbox, which is where an adapter can be built and exercised without a commercial agreement.`,
          )}
        </p>
      </div>

      <div className="overflow-x-auto rounded border border-slate-200">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50">
            <tr>
              <th className="px-4 py-3 text-left font-medium text-slate-700">
                {t('Schopnost', 'Capability')}
              </th>
              <th className="px-4 py-3 text-left font-medium text-slate-700">{t('Port', 'Port')}</th>
              {registry.networks.map(network => (
                <th key={network.id} className="px-4 py-3 text-left font-medium text-slate-700">
                  {network.label}
                </th>
              ))}
              <th className="px-4 py-3 text-left font-medium text-slate-700">
                {t('Implementace zde', 'Bindings here')}
              </th>
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
                    // A portless capability is a DECISION, with its reason in the cell to the left
                    // — never an oversight, so it says so rather than showing an empty cell.
                    <span className="text-xs text-slate-500">
                      {t('bez portu, záměrně', 'no port, by design')}
                    </span>
                  )}
                </td>
                {registry.networks.map(network => {
                  const entry = capability.networks[network.id]
                  return (
                    <td key={network.id} className="px-4 py-3 align-top">
                      {entry ? (
                        <AvailabilityChip
                          label={availabilityLabel[entry.availability]}
                          availability={entry.availability}
                          product={entry.product}
                        />
                      ) : (
                        <span className="text-xs text-slate-500">
                          {t('neevidováno', 'not registered')}
                        </span>
                      )}
                    </td>
                  )
                })}
                <td className="px-4 py-3 align-top">
                  <BindingsCell
                    capability={capability}
                    emptyLabel={t('žádná — viz proč', 'none — see why')}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="rounded border border-slate-200 p-4">
        <h2 className="text-sm font-medium text-slate-900">
          {t('Jak se dostat do sandboxu každé sítě', "Getting into each network's sandbox")}
        </h2>
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
          {t(
            'Přihlašovací údaje jsou v OpenBao a čte je jen ten adaptér, který je potřebuje. Žádná služba mimo adaptér údaje sítě nevidí a žádné nejsou v repozitáři.',
            'Credentials live in OpenBao and are read only by the adapter module that needs them. No service outside an adapter sees a network credential, and none is committed here.',
          )}
        </p>
      </div>
    </div>
  )
}

/**
 * The command that bakes the registry. Held as a constant and rendered as an expression rather
 * than as JSX text, because it is a shell command and not copy: it must read identically in both
 * languages, and the bilingual guard is right to flag a literal here.
 */
const GENERATE_COMMAND = 'node scripts/generate-card-capabilities.mjs'

const AVAILABILITY_TONE: Record<Availability, string> = {
  sandbox: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  contract: 'bg-amber-50 text-amber-700 border-amber-200',
  none: 'bg-slate-100 text-slate-600 border-slate-200',
}

function AvailabilityChip({
  availability,
  product,
  label,
}: {
  availability: Availability
  product: string
  label: string
}) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-sm text-slate-800">{product}</span>
      <span
        className={`inline-flex w-fit items-center rounded border px-1.5 py-0.5 text-xs ${AVAILABILITY_TONE[availability]}`}
      >
        {label}
      </span>
    </div>
  )
}

function BindingsCell({ capability, emptyLabel }: { capability: CardCapability; emptyLabel: string }) {
  if (capability.bindings.length === 0) {
    return <span className="text-xs text-slate-500">{emptyLabel}</span>
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
