// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import Link from 'next/link'
import { ArrowRight, FileSearch, Hand, Network, ShieldCheck, UsersRound } from 'lucide-react'
import styles from './AgentMeshExplainer.module.css'

type Language = 'cs' | 'en'

function MeshStep({ icon: Icon, number, title, detail }: {
  icon: typeof Network
  number: string
  title: string
  detail: string
}) {
  return (
    <li className={styles.step}>
      <span className={styles.stepNumber}>{number}</span>
      <span className={styles.icon}><Icon size={18} /></span>
      <strong>{title}</strong>
      <span>{detail}</span>
    </li>
  )
}

export function AgentMeshExplainer({ language }: { language: Language }) {
  const t = (cs: string, en: string) => language === 'cs' ? cs : en

  return (
    <section id="ai-mesh" className={styles.mesh} aria-labelledby="ai-mesh-title">
      <div className={styles.heading}>
        <div>
          <span className={styles.eyebrow}><Network size={13} /> {t('Spolupráce agentů', 'Agent collaboration')}</span>
          <h2 id="ai-mesh-title">{t('Co je AI mesh?', 'What is the AI mesh?')}</h2>
          <p>{t(
            'Není to jeden „superagent“ ani automat, který rozhoduje sám. Je to řízený způsob, jak si specializovaní agenti předají jeden případ a připraví společný podklad pro člověka.',
            'It is neither a single “super-agent” nor an automation that decides by itself. It is a governed way for specialist agents to work on one case and prepare a shared brief for a person.',
          )}</p>
        </div>
        <span className={styles.humanFirst}><Hand size={13} /> {t('Člověk má poslední slovo', 'A human has the final say')}</span>
      </div>

      <ol className={styles.flow} aria-label={t('Jak AI mesh spolupracuje', 'How the AI mesh collaborates')}>
        <MeshStep number="1" icon={FileSearch}
          title={t('Přijde podnět', 'A signal arrives')}
          detail={t('Například alert, nález nebo požadavek na prověření.', 'For example, an alert, finding, or request for review.')} />
        <MeshStep number="2" icon={Network}
          title={t('Koordinátor drží případ', 'A coordinator holds the case')}
          detail={t('Hlídá společný kontext, rozpočet, termín a okamžik zastavení.', 'It holds shared context, budget, deadline, and the stop condition.')} />
        <MeshStep number="3" icon={UsersRound}
          title={t('Specialisté přispějí', 'Specialists contribute')}
          detail={t('Každý přidá jen svůj pohled a důkazy v mezích svého charteru.', 'Each adds only its own view and evidence within its charter.')} />
        <MeshStep number="4" icon={Hand}
          title={t('Člověk rozhodne', 'A human decides')}
          detail={t('Dostane jeden návrh, zdroje i případné nesouhlasy. Agenti do business služby přímo nezapisují.', 'They receive one proposal, sources, and any dissent. Agents never write directly to a business service.')} />
      </ol>

      <div className={styles.footer}>
        <div className={styles.truth}>
          <ShieldCheck size={16} />
          <span>{t(
            'Bezpečnostní pravidlo: diagram popisuje navržený způsob spolupráce. Skutečné zapojení specialisty se vždy ověřuje samostatným runtime allowlistem — nejde o živý přehled připojených agentů.',
            'Safety rule: this diagram describes the designed collaboration model. A specialist’s actual admission is always checked by a separate runtime allowlist — this is not a live view of connected agents.',
          )}</span>
        </div>
        <Link href="/iaops/cases" className={styles.link}>
          {t('Prohlédnout case vlákna', 'Browse case threads')} <ArrowRight size={13} />
        </Link>
      </div>
    </section>
  )
}
