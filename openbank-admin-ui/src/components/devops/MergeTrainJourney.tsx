// SPDX-License-Identifier: Apache-2.0
'use client'

import { useEffect, useState } from 'react'
import { ArrowLeft, ArrowRight, Check, CircleHelp, Pause, Play, RotateCcw } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useFlowAnimation } from '@/components/topology/useFlowAnimation'
import { TRAIN_LANES, type TrainLane, type TrainProgress } from './sdlc-content'
import { TrainScene } from './TrainScene'
import styles from './SdlcGuide.module.css'

export function MergeTrainJourney() {
  const { language, t } = useLanguage()
  const [lane, setLane] = useState<TrainLane>('batch')
  const [steps, setSteps] = useState<TrainProgress>({ batch: 0, fast: 1, strict: 2 })
  const step = steps[lane]
  const [failed, setFailed] = useState(false)
  const [playing, setPlaying] = useFlowAnimation()
  const selected = TRAIN_LANES[lane]
  const complete = Object.values(steps).every(value => value === 4)
  useEffect(() => {
    if (!playing || complete) return
    const cadence: Record<TrainLane, number> = { batch: 8000, fast: 5000, strict: 10000 }
    const timers = (Object.keys(TRAIN_LANES) as TrainLane[]).map(key => window.setInterval(() => {
      setSteps(current => ({ ...current, [key]: Math.min(4, current[key] + 1) }))
    }, cadence[key]))
    return () => timers.forEach(timer => window.clearInterval(timer))
  }, [playing, complete])
  const move = (value: number) => { setPlaying(false); setFailed(false); setSteps(current => ({ ...current, [lane]: value })) }
  return <section className={styles.trainSection} aria-labelledby="train-title" onFocusCapture={event => { if (!(event.target as HTMLElement).closest('[data-play-control]')) setPlaying(false) }}>
    <div className={styles.trainBoard} data-train-board>
      <div className={styles.trainToolbar}>
        <div><h2 id="train-title">{t('Více kolejí. Kontroly běží souběžně.', 'Several tracks. Checks run concurrently.')}</h2><p>{t('Vyberte vlak přímo na koleji a projděte jeho cestu.', 'Select a train on its track and explore its journey.')}</p></div>
        <button className={styles.button} type="button" data-play-control aria-pressed={playing} onClick={() => { if (Object.values(steps).every(value => value === 4)) setSteps({ batch: 0, fast: 1, strict: 2 }); setFailed(false); setPlaying(current => !current) }}>
          {playing ? <Pause size={16} aria-hidden="true" /> : <Play size={16} aria-hidden="true" />}{playing ? t('Pozastavit', 'Pause') : t('Přehrát cestu', 'Play journey')}
        </button>
      </div>
      <TrainScene lane={lane} steps={steps} playing={playing} failed={failed} onSelectLane={key => { setLane(key); setPlaying(false); setFailed(false) }} />
      <ol className={styles.stations} aria-label={t('Stanice cesty změny', 'Stations on the change journey')}>
        {selected.stops.map((stop, index) => <li key={index}><button type="button" aria-current={step === index ? 'step' : undefined} onClick={() => move(index)}><span>{index < step ? <Check size={14} aria-hidden="true" /> : index + 1}</span>{stop[language]}</button></li>)}
      </ol>
      <div className={styles.journeyDetail} role="region" aria-label={t('Co se právě děje', 'What is happening now')} aria-live="polite">
        <div className={styles.journeySummary}>
          <h3>{failed ? t('Zastavíme a zjistíme proč', 'Stop and find out why') : `${step + 1} / 5 · ${selected.stops[step][language]}`}</h3>
          <p>{failed ? t('Neúspěšná kontrola tuto kolej zastaví. Ostatní koleje mají vlastní průběh.', 'A failed check stops this track. Other tracks keep their own progress.') : selected.summaries[step][language]}</p>
        </div>
        <details className={styles.journeyExplanation}>
          <summary>{t('Jak to funguje?', 'How does it work?')}</summary>
          <p>{selected.description[language]}</p>
          <p>{failed ? lane === 'batch' ? t('Při skutečném selhání se dávka půlí a znovu ověřuje, až lze problém izolovat. Menší skupiny se zkoušejí v dalších jízdách. Samotné zrušení běhu či nevyhodnocená kontrola nejsou důkazem vady změny: následuje omezené opakování a případně nový vlak.', 'A real failure halves the batch for rechecking until the problem can be isolated. Smaller groups are checked on subsequent rides. Cancellation or an unevaluated check alone is not evidence of a bad change: a bounded retry and possibly a new train follow.') : t('Tato změna se nesloučí, dokud nesplní podmínky své cesty. Vlastník opraví příčinu a nové kontroly znovu ověří změněnou revizi. Neprůkazný běh není automaticky chyba autora.', 'This change cannot merge until it satisfies its route’s conditions. The owner fixes the cause and fresh checks verify the new revision. An inconclusive run is not automatically the author’s fault.') : selected.steps[step][language]}</p>
        </details>
      </div>
      <div className={styles.controls}>
        <button className={styles.button} type="button" disabled={step === 0 && !failed} onClick={() => move(Math.max(0, step - 1))}><ArrowLeft size={16} aria-hidden="true" />{t('Zpět', 'Back')}</button>
        <button className={`${styles.button} ${styles.primaryButton}`} type="button" disabled={step === 4 && !failed} onClick={() => move(failed ? step : Math.min(4, step + 1))}>{failed ? t('Zpět k cestě', 'Back to the journey') : t('Další stanice', 'Next station')}<ArrowRight size={16} aria-hidden="true" /></button>
        <button className={styles.button} type="button" aria-pressed={failed} onClick={() => { setPlaying(false); setFailed(current => !current) }}><CircleHelp size={16} aria-hidden="true" />{t('Co když kontrola selže?', 'What if a check fails?')}</button>
        <button className={styles.textButton} type="button" onClick={() => move(0)}><RotateCcw size={14} aria-hidden="true" />{t('Od začátku', 'Start again')}</button>
      </div>
      <p className={styles.trainCaption}>{t('Vagón = PR · Koleje ověřují souběžně, zápisy do main mají řízené pořadí. Ilustrační provoz.', 'Coach = PR · Tracks check concurrently; writes to main follow governed ordering. Illustrative operations.')}</p>
    </div>
    <details className={styles.details}>
      <summary>{t('Co znázorňují vlaky a jejich souběh?', 'What do the trains and their concurrency represent?')}</summary>
      <p>{t('Modelový příběh, ne živá fronta GitHubu. Vagón je PR, lokomotiva znázorňuje odbavení botem a cílová stanice je hlavní větev main. Dávkový vlak má společný ověřovací PR, rychlá kolej ho nepotřebuje.', 'An illustrative story, not the live GitHub queue. A coach is a PR, the engine represents bot settlement and the destination is the main branch. A batch train has a shared test PR; the fast lane does not need one.')}</p>
      <p>{t('Každá kolej má vlastní průběh a může ověřovat změny současně s ostatními. Přepnutím koleje pouze zvolíte detail; její rozpracovaný stav zůstává. CI přísné koleje a dávkového carrieru se může v povoleném okně překrýt. Zápisy do main ale probíhají řízeně v pořadí: rezervace může zadržet i jinak připravenou rychlou kolej. Tempa v animaci jsou ilustrační.', 'Each track has its own progress and can check changes alongside the others. Switching tracks selects a detail and preserves its progress. Strict-lane CI and a batch carrier can overlap within the allowed window. Writes to main still follow governed ordering: a reservation can hold an otherwise ready fast lane. Animation timings are illustrative.')}</p>
      <p>{t('Emoce jsou ilustrace stavu. Žádné tlačítko zde neovládá skutečné PR ani nasazení.', 'Emotions illustrate the state. No button here controls real PRs or deployments.')}</p>
    </details>
  </section>
}
