// SPDX-License-Identifier: Apache-2.0
'use client'

import { ArrowRight, BookOpenCheck, Building2, Landmark, ShieldCheck, UserRound } from 'lucide-react'
import { ExplorerGuide } from '@/components/brand/ExplorerGuide'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import styles from './DelegationEducation.module.css'

type Copy = { cs: string; en: string }

type Scenario = {
  id: 'sole-trader' | 'sme' | 'corporate'
  label: Copy
  eyebrow: Copy
  summary: Copy
  example: Copy
  today: Copy[]
  next: Copy[]
}

export const DELEGATION_SCENARIOS: readonly Scenario[] = [
  {
    id: 'sole-trader',
    label: { cs: 'FOP / OSVČ', en: 'Sole trader' },
    eyebrow: { cs: 'Jedna osoba, více pracovních kontextů', en: 'One person, several working contexts' },
    summary: {
      cs: 'Začněte čtecím přístupem pro účetní a pohyb peněz oddělte do samostatného, užšího grantu.',
      en: 'Start with read access for an accountant and keep money movement in a separate, narrower grant.',
    },
    example: {
      cs: 'FOP → účetní → podnikatelský účet → zůstatky a transakce → do 30. 9. 2026',
      en: 'Sole trader → accountant → business account → balances and transactions → until 30 Sep 2026',
    },
    today: [
      {
        cs: 'Konzole oddělí vlastníka produktu od člověka s delegovaným přístupem.',
        en: 'The console separates the product owner from the person holding delegated access.',
      },
      {
        cs: 'U každého grantu ukáže referenci zdroje, zkopírovaná práva, stav, platnost a evidované stropy; produktový detail doplní, pokud je dostupný.',
        en: 'For every grant it shows the resource reference, copied rights, status, validity and recorded ceilings; product detail is added when available.',
      },
      {
        cs: 'Čtecí role lze vysvětlit odděleně od rolí, které zamýšlejí provádět operace.',
        en: 'Read roles can be explained separately from roles intended to perform operations.',
      },
    ],
    next: [
      {
        cs: 'Výslovné rozlišení, zda FOP jedná v osobní, nebo podnikatelské kapacitě.',
        en: 'An explicit distinction between the person acting privately and as a business.',
      },
      {
        cs: 'Pravidelná obnova mandátu a upozornění na dlouho nepoužívaný přístup.',
        en: 'Periodic mandate renewal and alerts for long-unused access.',
      },
    ],
  },
  {
    id: 'sme',
    label: { cs: 'SME', en: 'SME' },
    eyebrow: { cs: 'Jasná dělba práce', en: 'Clear division of work' },
    summary: {
      cs: 'Účetní, pokladník a držitel dodatkové karty potřebují samostatné granty — název role sám nic nepovoluje.',
      en: 'An accountant, cashier and additional cardholder need separate grants — a role name grants nothing by itself.',
    },
    example: {
      cs: 'Firma → účetní → firemní účet → pouze čtení → časově omezeno',
      en: 'Company → accountant → company account → read only → time-limited',
    },
    today: [
      {
        cs: 'Šablony sjednotí slovník rolí, ale každý grant zůstává samostatným snapshotem práv.',
        en: 'Presets align role vocabulary, while every grant remains an independent rights snapshot.',
      },
      {
        cs: 'Delegace účtu a evidence držitele dodatkové karty se zobrazují jako dva různé zdroje autority.',
        en: 'An account delegation and an additional-cardholder record are shown as different authority sources.',
      },
      {
        cs: 'Detail grantu ukáže jeho stav; auditní časová osa doplní evidovanou historii, pokud k ní má operátor oprávnění.',
        en: 'The grant detail shows its status; the audit timeline adds recorded history when the operator is authorized to see it.',
      },
    ],
    next: [
      {
        cs: 'Firemní členství a mandáty se spravovaným životním cyklem rolí.',
        en: 'Company membership and mandates with a managed role lifecycle.',
      },
      {
        cs: 'Maker–checker pro účtové platby a vícečlenné schvalování. Tyto režimy dnes ještě nelze nabídnout jako účinnou delegaci.',
        en: 'Maker–checker for account payments and multi-party approval. These modes cannot yet be offered as effective delegation.',
      },
      {
        cs: 'Pravidelná recertifikace přístupů vedoucím nebo vlastníkem firmy.',
        en: 'Periodic access recertification by a manager or company owner.',
      },
    ],
  },
  {
    id: 'corporate',
    label: { cs: 'Korporace', en: 'Corporate' },
    eyebrow: { cs: 'Řízení přístupů ve velkém', en: 'Access governance at scale' },
    summary: {
      cs: 'Jemnozrnné granty jsou společný základ; organizační mandáty a portfoliové řízení jsou zatím cílový model.',
      en: 'Fine-grained grants are the common foundation; organisational mandates and portfolio governance remain a target model.',
    },
    example: {
      cs: 'Cílový model: korporace → treasury tým → portfolio účtů → oddělené navržení a schválení',
      en: 'Target model: corporate → treasury team → account portfolio → separated proposal and approval',
    },
    today: [
      {
        cs: 'Konzole umí dohledat jemnozrnný grant ke konkrétnímu zdroji a s auditním oprávněním i jeho evidovanou historii.',
        en: 'The console can trace a fine-grained grant to one resource and, with audit permission, its recorded history.',
      },
      {
        cs: 'Vlastní kombinace práv zůstává viditelná bez domýšlení firemní role z pouhého názvu.',
        en: 'A custom rights set stays visible without inferring a company role from its name.',
      },
    ],
    next: [
      {
        cs: 'Autoritativní vazba organizace → člen → statutární nebo interní mandát.',
        en: 'An authoritative organisation → member → statutory or internal mandate relationship.',
      },
      {
        cs: 'Skupiny účtů, pravidla oddělení povinností a vynucené schválení N-z-M.',
        en: 'Account groups, segregation-of-duties rules and enforced N-of-M approval.',
      },
      {
        cs: 'Portfoliový přehled, kampaně recertifikace a export důkazů pro compliance.',
        en: 'Portfolio views, recertification campaigns and compliance evidence export.',
      },
    ],
  },
] as const

const SCENARIO_ICONS = {
  'sole-trader': UserRound,
  sme: Building2,
  corporate: Landmark,
}

export function DelegationEducation() {
  const { t, language } = useLanguage()
  const localize = (copy: Copy) => copy[language]

  return (
    <>
      <ExplorerGuide
        compact
        priority
        mascot="lioness"
        eyebrow={t('Průvodce oprávněními', 'Access guide')}
        title={t('Nejdřív porozumět. Potom teprve rozhodovat.', 'Understand first. Decide second.')}
        action={(
          <nav className={styles.guideActions} aria-label={t('Rychlé odkazy v modulu', 'Quick links in this module')}>
            <a className={styles.guideAction} href="#delegation-party-search">
              {t('Prověřit konkrétní stranu', 'Review a specific party')} <ArrowRight size={13} aria-hidden="true" />
            </a>
            <a className={styles.guideActionSecondary} href="#delegation-role-catalog">
              {t('Prohlédnout šablony rolí', 'Review role presets')}
            </a>
          </nav>
        )}
      >
        {t(
          'Vlastník není role z katalogu. Dispoziční role je pouze šablona; skutečný delegovaný přístup určuje konkrétní grant, jeho stav, zdroj, práva a podmínky. Životní cyklus delegací zde pouze čtete — správci mohou měnit jen katalog šablon.',
          'An owner is not a catalog role. A delegation role is only a preset; actual delegated access comes from a specific grant, its status, resource, rights and conditions. Delegation lifecycles are read-only here — administrators can change only the preset catalog.',
        )}
      </ExplorerGuide>

      <section className={`card ${styles.education}`} aria-labelledby="delegation-education-title">
        <header className={styles.header}>
          <span className={styles.headerIcon} aria-hidden="true"><BookOpenCheck size={19} /></span>
          <div>
            <p className={styles.eyebrow}>{t('Jedna rozhodovací věta', 'One decision sentence')}</p>
            <h2 id="delegation-education-title" className={styles.title}>
              {t(
                'Kdo může nad čím dělat co, do kdy, v jakém rozsahu — a odkud to víme?',
                'Who may do what, over which resource, until when, within what bounds — and how do we know?',
              )}
            </h2>
          </div>
        </header>

        <details className={styles.learningDetails}>
          <summary className={styles.learningSummary}>
            <span>{t('Otevřít celý model a příklady pro jednotlivé segmenty', 'Open the full model and segment examples')}</span>
            <span className={styles.learningHint}>{t('5 kroků · FOP · SME · korporace', '5 steps · sole trader · SME · corporate')}</span>
            <span className={styles.learningChevron} aria-hidden="true">⌄</span>
          </summary>
          <div className={styles.learningBody}>
            <ol className={styles.model} aria-label={t('Jak číst dispoziční přístup', 'How to read delegated access')}>
              <ModelStep number="1" title={t('Zdroj autority', 'Authority source')}>
                {t('Vlastnictví z produktu, evidence držitele karty, nebo delegační grant.', 'Product ownership, a cardholder record, or a delegation grant.')}
              </ModelStep>
              <ModelStep number="2" title={t('Jedna strana a zdroj', 'One party and resource')}>
                {t('Grant váže jednoho příjemce ke konkrétnímu produktu nebo sdílenému objektu.', 'A grant binds one recipient to a specific product or shared object.')}
              </ModelStep>
              <ModelStep number="3" title={t('Přesná práva', 'Exact rights')}>
                {t('Grant nese snapshot práv; název role je jen lidské vysvětlení. O skutečném povolení rozhoduje produktová služba.', 'The grant carries a rights snapshot; the role name is only a human explanation. The product service makes the actual authorization decision.')}
              </ModelStep>
              <ModelStep number="4" title={t('Podmínky grantu', 'Grant conditions')}>
                {t('Každý grant nese vlastní stav, časové okno a evidované limity.', 'Every grant carries its own status, time window and recorded limits.')}
              </ModelStep>
              <ModelStep number="5" title={t('Důkaz a historie', 'Evidence and history')}>
                {t('Detail ukáže aktuální stav; audit doplní evidované události, pokud k nim máte oprávnění.', 'The detail shows current status; audit adds recorded events when you are authorized to see them.')}
              </ModelStep>
            </ol>

            <aside className={styles.truthNote} aria-labelledby="delegation-truth-title">
              <ShieldCheck size={17} aria-hidden="true" />
              <div>
                <h3 id="delegation-truth-title">{t('Jak číst „účinné“ právo', 'How to read an “effective” right')}</h3>
                <p>
                  {t(
                    'Zaškrtnutí v katalogu znamená „součást šablony“, ne důkaz podpory ve všech produktových kanálech. Aktivní grant v časovém okně je kandidát na přístup; konečné povolení vždy provádí produktová služba.',
                    'A catalog check means “included in the preset”, not proof of support in every product channel. An active grant within its time window is an access candidate; the product service always makes the final authorization decision.',
                  )}
                </p>
              </div>
            </aside>

            <div className={styles.scenarioHeading}>
              <div>
                <p className={styles.eyebrow}>{t('Připraveno růst bez nové logiky práv', 'Ready to grow without a new rights model')}</p>
                <h3>{t('Příklady pro FOP, SME a korporace', 'Examples for sole traders, SMEs and corporates')}</h3>
              </div>
              <span className={styles.educationOnly}>{t('Pouze edukace', 'Education only')}</span>
            </div>
            <p className={styles.scenarioIntro}>
              {t(
                'Tyto situace pomáhají navrhnout nejmenší potřebný přístup. Nic automaticky nevybírají, nemění typ strany, způsobilost ani skutečná práva.',
                'These scenarios help design least-privilege access. They select nothing automatically and do not change party type, eligibility or actual rights.',
              )}
            </p>

            <div className={styles.scenarios}>
              {DELEGATION_SCENARIOS.map(scenario => {
                const Icon = SCENARIO_ICONS[scenario.id]
                const panelId = `delegation-scenario-${scenario.id}`
                const titleId = `${panelId}-title`
                return (
                  <details key={scenario.id} className={styles.scenario} aria-labelledby={titleId}>
                    <summary aria-controls={panelId}>
                      <span className={styles.scenarioIcon} aria-hidden="true"><Icon size={17} /></span>
                      <span className={styles.scenarioSummary}>
                        <span id={titleId} className={styles.scenarioLabel}>{localize(scenario.label)}</span>
                        <span className={styles.scenarioEyebrow}>{localize(scenario.eyebrow)}</span>
                        <span className={styles.scenarioDescription}>{localize(scenario.summary)}</span>
                      </span>
                      <span className={styles.chevron} aria-hidden="true">⌄</span>
                    </summary>
                    <div id={panelId} className={styles.scenarioBody}>
                      <p className={styles.example}><span>{t('Příklad', 'Example')}</span>{localize(scenario.example)}</p>
                      <div className={styles.readinessGrid}>
                        <ReadinessList
                          title={t('Co konzole umí vysvětlit dnes', 'What the console can explain today')}
                          context={localize(scenario.label)}
                          tone="current"
                          items={scenario.today.map(localize)}
                        />
                        <ReadinessList
                          title={t('Další kontrolní vrstva — není aktivní', 'Next control layer — not active')}
                          context={localize(scenario.label)}
                          tone="future"
                          items={scenario.next.map(localize)}
                        />
                      </div>
                    </div>
                  </details>
                )
              })}
            </div>
          </div>
        </details>
      </section>
    </>
  )
}

function ModelStep({ number, title, children }: { number: string; title: string; children: string }) {
  return (
    <li>
      <span className={styles.stepNumber} aria-hidden="true">{number}</span>
      <div><strong>{title}</strong><span>{children}</span></div>
    </li>
  )
}

function ReadinessList({ title, context, tone, items }: { title: string; context: string; tone: 'current' | 'future'; items: string[] }) {
  return (
    <section className={styles.readiness} data-tone={tone} aria-label={`${context}: ${title}`}>
      <h4>{title}</h4>
      <ul>{items.map(item => <li key={item}>{item}</li>)}</ul>
    </section>
  )
}
