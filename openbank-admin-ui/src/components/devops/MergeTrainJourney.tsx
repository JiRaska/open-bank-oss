// SPDX-License-Identifier: Apache-2.0
'use client'

import { useEffect, useState } from 'react'
import { ArrowLeft, ArrowRight, Check, CircleHelp, Pause, Play, RotateCcw, ShieldCheck } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useFlowAnimation } from '@/components/topology/useFlowAnimation'
import { TRAIN_LANES, type TrainLane } from './sdlc-content'
import { TrainScene } from './TrainScene'
import styles from './SdlcGuide.module.css'

export function MergeTrainJourney() {
  const { language, t } = useLanguage()
  const [lane, setLane] = useState<TrainLane>('batch')
  const [step, setStep] = useState(0)
  const [failed, setFailed] = useState(false)
  const [playing, setPlaying] = useFlowAnimation()
  const selected = TRAIN_LANES[lane]
  useEffect(() => {
    if (!playing || failed || step === 4) return
    const timer = window.setTimeout(() => setStep(current => Math.min(4, current + 1)), 8000)
    return () => window.clearTimeout(timer)
  }, [playing, failed, step])
  const move = (value: number) => { setPlaying(false); setFailed(false); setStep(value) }
  return <section className={styles.trainSection} aria-labelledby="train-title" onFocusCapture={event => { if (!(event.target as HTMLElement).closest('[data-play-control]')) setPlaying(false) }}>
    <div className={styles.sectionTop}>
      <div><span className={styles.eyebrow}>{t('Nádraží změn', 'Change station')}</span><h2 id="train-title">{t('Každý lvíček veze jednu změnu', 'Every lion carries a change')}</h2></div>
      <button className={styles.button} type="button" data-play-control aria-pressed={playing} onClick={() => { if (step === 4) setStep(0); setFailed(false); setPlaying(current => !current) }}>
        {playing ? <Pause size={16} aria-hidden="true" /> : <Play size={16} aria-hidden="true" />}{playing ? t('Pozastavit', 'Pause') : t('Přehrát cestu', 'Play journey')}
      </button>
    </div>
    <p className={styles.muted}>{t('Modelový příběh, ne živá fronta GitHubu. Vagón je PR, lokomotiva znázorňuje odbavení botem a cílová stanice je hlavní větev main. Dávkový vlak má společný ověřovací PR, rychlá kolej ho nepotřebuje.', 'An illustrative story, not the live GitHub queue. A coach is a PR, the engine represents bot settlement and the destination is the main branch. A batch train has a shared test PR; the fast lane does not need one.')}</p>
    <div className={styles.laneChoices} aria-label={t('Vyberte kolej', 'Choose a lane')}>
      {(Object.keys(TRAIN_LANES) as TrainLane[]).map(key => <button key={key} type="button" aria-pressed={lane === key} onClick={() => { setLane(key); move(0) }}>
        <strong>{TRAIN_LANES[key].title[language]}</strong><span>{TRAIN_LANES[key].subtitle[language]}</span>
      </button>)}
    </div>
    <p className={styles.routeDescription}>{selected.description[language]}</p>
    <TrainScene lane={lane} step={step} playing={playing} failed={failed} />
    <ol className={styles.stations} aria-label={t('Stanice cesty změny', 'Stations on the change journey')}>
      {selected.stops.map((stop, index) => <li key={index}><button type="button" aria-current={step === index ? 'step' : undefined} onClick={() => move(index)}><span>{index < step ? <Check size={14} aria-hidden="true" /> : index + 1}</span>{stop[language]}</button></li>)}
    </ol>
    <div className={styles.journeyDetail} role="region" aria-label={t('Co se právě děje', 'What is happening now')} aria-live="polite">
      <span className={styles.eyebrow}>{failed ? t('Zkouška nevyšla', 'The check did not pass') : `${step + 1} / 5 · ${selected.stops[step][language]}`}</span>
      <h3>{failed ? t('Zastavíme a zjistíme proč', 'Stop and find out why') : step === 4 ? t('Dojeli jsme. Nasazení teprve navazuje.', 'We have arrived. Deployment comes next.') : selected.stops[step][language]}</h3>
      <p>{failed ? lane === 'batch' ? t('Při skutečném selhání se dávka půlí a znovu ověřuje, až lze problém izolovat. Menší skupiny se zkoušejí v dalších jízdách. Samotné zrušení běhu či nevyhodnocená kontrola nejsou důkazem vady změny: následuje omezené opakování a případně nový vlak.', 'A real failure halves the batch for rechecking until the problem can be isolated. Smaller groups are checked on subsequent rides. Cancellation or an unevaluated check alone is not evidence of a bad change: a bounded retry and possibly a new train follow.') : t('Tato změna se nesloučí, dokud nesplní podmínky své cesty. Vlastník opraví příčinu a nové kontroly znovu ověří změněnou revizi. Neprůkazný běh není automaticky chyba autora.', 'This change cannot merge until it satisfies its route’s conditions. The owner fixes the cause and fresh checks verify the new revision. An inconclusive run is not automatically the author’s fault.') : selected.steps[step][language]}</p>
    </div>
    <div className={styles.controls}>
      <button className={styles.button} type="button" disabled={step === 0 && !failed} onClick={() => move(Math.max(0, step - 1))}><ArrowLeft size={16} aria-hidden="true" />{t('Zpět', 'Back')}</button>
      <button className={`${styles.button} ${styles.primaryButton}`} type="button" disabled={step === 4 && !failed} onClick={() => move(failed ? step : Math.min(4, step + 1))}>{failed ? t('Zpět k cestě', 'Back to the journey') : t('Další stanice', 'Next station')}<ArrowRight size={16} aria-hidden="true" /></button>
      <button className={styles.button} type="button" aria-pressed={failed} onClick={() => { setPlaying(false); setFailed(current => !current) }}><CircleHelp size={16} aria-hidden="true" />{t('Co když kontrola selže?', 'What if a check fails?')}</button>
      <button className={styles.textButton} type="button" onClick={() => move(0)}><RotateCcw size={14} aria-hidden="true" />{t('Od začátku', 'Start again')}</button>
    </div>
    <p className={styles.caption}><ShieldCheck size={15} aria-hidden="true" />{t('Emoce jsou ilustrace stavu. Žádné tlačítko zde neovládá skutečné PR ani nasazení.', 'Emotions illustrate the state. No button here controls real PRs or deployments.')}</p>
  </section>
}
