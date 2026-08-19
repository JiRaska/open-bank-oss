// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Sensors index — the device sensors and device capabilities the customer app
// uses, grouped into five families with a subpage each. Server component
// (ADR-0056); every entry is curated in src/lib/docs/sensors.ts and carries the
// openbank-app path that implements it.
//
// Companion to /docs/customer-app: that page answers "what state is the app in",
// this one answers "which signals does it read, and what does the customer do to
// trigger them".

import Link from 'next/link'
import { cookies } from 'next/headers'
import {
  ChevronLeft, Radar, Activity, Bluetooth, Sun, ShieldCheck, Command, ChevronRight,
} from 'lucide-react'
import { FAMILY_ORDER, FAMILY_META, SENSORS, sensorsByFamily, statusCounts } from '@/lib/docs/sensors'
import { STATUS_META, type Status } from '@/lib/docs/status'

export const dynamic = 'force-dynamic'

const FAMILY_ICON: Record<string, React.ElementType> = {
  motion: Activity,
  proximity: Bluetooth,
  environment: Sun,
  privacy: ShieldCheck,
  shortcuts: Command,
}

export default async function SensorsIndexPage() {
  const lang = (await cookies()).get('openbank-admin-lang')?.value === 'cs' ? 'cs' : 'en'
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)
  const counts = statusCounts()

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/docs" style={{ color: 'inherit', textDecoration: 'none' }}>{t('Dokumentace', 'Docs')}</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Senzory', 'Sensors')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Radar size={18} style={{ color: 'var(--accent)' }} />
            {t('Senzory zákaznické aplikace', 'Customer-app sensors')}
          </h1>
          <p style={{ fontSize: 13, color: 'var(--text-secondary)', maxWidth: '62rem', lineHeight: 1.6, marginTop: 6 }}>
            {t(
              'Které signály zařízení aplikace čte, jaký problém tím řeší, jak si funkci zákazník vyvolá, jestli k ní vede zkratka, kde v aplikaci je a jak se nastaví. Implementace žije v samostatném repozitáři openbank-app (KMP/Compose, ADR-0074); u každé položky je uvedený zdrojový soubor.',
              'Which device signals the app reads, what each one solves, how the customer invokes it, whether a shortcut leads to it, where it lives in the app and how it is configured. The implementation lives in the separate openbank-app repository (KMP/Compose, ADR-0074); every entry names its source file.',
            )}
          </p>
        </div>
      </div>

      <div className="card" style={{ padding: 18, marginBottom: 18, borderLeft: '3px solid var(--accent)' }}>
        <h2 style={{ fontSize: 13, fontWeight: 650, margin: '0 0 6px' }}>
          {t('Pravidlo, které platí pro celý tento povrch', 'The rule governing this whole surface')}
        </h2>
        <p style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.6, margin: 0 }}>
          {t(
            'Měření smí VYBRAT, NAVRHNOUT nebo PŘIDAT tření — nikdy autorizovat. Žádné čtení ze senzoru nic neodemyká, neschvaluje platbu ani neposouvá hranici autentizace: UWB vybere příjemce, ale platbu podepisuje SCA; otočení displejem dolů umí jen zamknout, nikdy odemknout; rozpoznání cesty do zahraničí kartu nezapne, jen to nabídne. Nejhorší falešně pozitivní výsledek tak stojí jeden biometrický dotaz navíc.',
            'A measurement may SELECT, SUGGEST or ADD friction — never authorise. No sensor reading unlocks anything, approves a payment or moves an auth boundary: UWB selects a payee but SCA signs the payment; the face-down gesture can only lock, never unlock; travel detection never enables a card, it only offers. The worst false positive therefore costs one extra biometric prompt.',
          )}
        </p>
      </div>

      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 18 }}>
        {(['live', 'partial', 'planned'] as Status[]).map(s => (
          <div key={s} className="card" style={{ padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ width: 9, height: 9, borderRadius: '50%', background: STATUS_META[s].color }} />
            <span style={{ fontSize: 18, fontWeight: 650 }}>{counts[s]}</span>
            <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{STATUS_META[s].label[lang]}</span>
          </div>
        ))}
        <div className="card" style={{ padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 18, fontWeight: 650 }}>{SENSORS.length}</span>
          <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('celkem', 'total')}</span>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {FAMILY_ORDER.map(family => {
          const meta = FAMILY_META[family]
          const entries = sensorsByFamily(family)
          const Icon = FAMILY_ICON[family]
          return (
            <Link key={family} href={`/docs/sensors/${family}`} className="card" style={{ padding: 18, textDecoration: 'none', color: 'inherit', display: 'block' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                <Icon size={18} style={{ color: 'var(--accent)' }} />
                <h2 style={{ fontSize: 14.5, fontWeight: 650, margin: 0 }}>{meta.title[lang]}</h2>
                <ChevronRight size={15} style={{ marginLeft: 'auto', color: 'var(--text-tertiary)' }} />
              </div>
              <p style={{ fontSize: 12.5, color: 'var(--text-secondary)', lineHeight: 1.55, margin: '0 0 10px' }}>
                {meta.blurb[lang]}
              </p>
              <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {entries.map(e => (
                  <li key={e.id} className="badge badge-neutral" style={{ fontSize: 11 }}>
                    {e.title[lang]}
                  </li>
                ))}
              </ul>
            </Link>
          )
        })}
      </div>

      <div style={{ marginTop: 20, display: 'flex', gap: 14, flexWrap: 'wrap', fontSize: 12 }}>
        <Link href="/docs/customer-app" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--accent)', textDecoration: 'none' }}>
          {t('Dossier zákaznické aplikace (ADR-0074)', 'Customer-app dossier (ADR-0074)')}
        </Link>
        <Link href="/docs/qrlesspay" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--accent)', textDecoration: 'none' }}>
          {t('QRlessPay — protokol platby poblíž (ADR-0095)', 'QRlessPay — nearby-payment protocol (ADR-0095)')}
        </Link>
        <Link href="/docs" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--text-secondary)', textDecoration: 'none' }}>
          <ChevronLeft size={13} /> {t('Zpět na dokumentaci', 'Back to docs')}
        </Link>
      </div>
    </div>
  )
}
