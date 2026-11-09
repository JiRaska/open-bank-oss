// SPDX-License-Identifier: Apache-2.0
'use client'

import Link from 'next/link'
import { GitMerge, Route, TrainFront } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import styles from './SdlcGuide.module.css'

export function SdlcGuideNav({ current }: { current: 'overview' | 'ci' | 'cd' }) {
  const { t } = useLanguage()
  return (
    <nav className={styles.guideNav} aria-label={t('Průvodce SDLC', 'SDLC guide')}>
      <Link href="/devops/sdlc" aria-current={current === 'overview' ? 'page' : undefined}><Route size={18} aria-hidden="true" />{t('Celý životní cyklus', 'Whole lifecycle')}</Link>
      <Link href="/devops/sdlc/ci" aria-current={current === 'ci' ? 'page' : undefined}><TrainFront size={18} aria-hidden="true" />{t('CI · Vláčky změn', 'CI · Change trains')}</Link>
      <Link href="/devops/sdlc/cd" aria-current={current === 'cd' ? 'page' : undefined}><GitMerge size={18} aria-hidden="true" />{t('CD · Nasazování', 'CD · Deployment')}</Link>
    </nav>
  )
}

export function SdlcContext({ current }: { current: 'ci' | 'cd' }) {
  const { t } = useLanguage()
  return <ol className={styles.context} aria-label={t('Kde jsme v SDLC', 'Where we are in the SDLC')}>
    <li>{t('Nápad a vývoj', 'Idea and development')}</li>
    <li aria-current={current === 'ci' ? 'step' : undefined}><Link href="/devops/sdlc/ci">{t('CI, review a spojení změn', 'CI, review and merge')}</Link></li>
    <li>{t('Obraz aplikace', 'Application image')}</li>
    <li aria-current={current === 'cd' ? 'step' : undefined}><Link href="/devops/sdlc/cd">{t('CD a nasazení', 'CD and deployment')}</Link></li>
    <li>{t('Provoz a nové poznatky ↺', 'Operation and learning ↺')}</li>
  </ol>
}
