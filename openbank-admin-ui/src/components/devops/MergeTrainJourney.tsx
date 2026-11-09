// SPDX-License-Identifier: Apache-2.0
'use client'

import { useEffect, useId, useState } from 'react'
import { ArrowLeft, ArrowRight, Check, CircleHelp, Pause, Play, RotateCcw, ShieldCheck } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { useFlowAnimation } from '@/components/topology/useFlowAnimation'
import { TRAIN_LANES, type TrainLane } from './sdlc-content'
import styles from './SdlcGuide.module.css'

function Passenger({ x, y, size = 96, lioness = false, mood = 0 }: { x: number; y: number; size?: number; lioness?: boolean; mood?: number }) {
  return <svg className={styles.passenger} data-mood={mood} x={x} y={y} width={size} height={size} viewBox="0 0 512 512" overflow="hidden" aria-hidden="true">
    <image href="/brand/sdlc/train-passengers.png" x={-mood * 512} y={lioness ? -512 : 0} width="1536" height="1024" />
  </svg>
}

function TrainScene({ lane, step, playing, failed }: { lane: TrainLane; step: number; playing: boolean; failed: boolean }) {
  const { t } = useLanguage()
  const id = useId().replaceAll(':', '')
  const cars = lane === 'batch' ? 3 : 1
  const onboard = step >= 2
  const mood = failed || step === 3 ? 1 : step === 4 ? 2 : 0
  const moving = playing && onboard && step < 4 && !failed
  return <div className={styles.scene} data-moving={moving} data-playing={playing} data-failed={failed}>
    <svg viewBox="0 0 1000 350" className={styles.sceneSvg} role="img" aria-labelledby={`${id}-title`} aria-describedby={`${id}-desc`}>
      <title id={`${id}-title`}>{t('Lví vláček změn', 'Lion change train')}</title>
      <desc id={`${id}-desc`}>{t(
        `Modelový příklad. ${cars === 3 ? 'Tři návrhy změn' : 'Jeden návrh změny'}. ${failed ? 'Lvíčci čekají na vyřešení neúspěšné kontroly.' : step === 4 ? 'Lvíčci oslavují úspěšné sloučení.' : onboard ? 'Lvíčci nastoupili do vagónů.' : 'Lvíčci čekají na nástupišti.'}`,
        `Illustrative example. ${cars === 3 ? 'Three proposed changes' : 'One proposed change'}. ${failed ? 'The lions wait for a failed check to be resolved.' : step === 4 ? 'The lions celebrate a successful merge.' : onboard ? 'The lions have boarded their coaches.' : 'The lions wait on the platform.'}`,
      )}</desc>
      <defs>
        <linearGradient id={`${id}-sky`} x2="0" y2="1"><stop stopColor="var(--sdlc-sky-top)" /><stop offset="1" stopColor="var(--sdlc-sky-bottom)" /></linearGradient>
        <linearGradient id={`${id}-body`} x2="0" y2="1"><stop stopColor="var(--sdlc-train-top)" /><stop offset="1" stopColor="var(--sdlc-train-bottom)" /></linearGradient>
        <pattern id={`${id}-sleepers`} width="38" height="22" patternUnits="userSpaceOnUse"><path d="M4 2v18" stroke="var(--sdlc-track)" strokeWidth="8" /></pattern>
      </defs>
      <rect width="1000" height="350" fill={`url(#${id}-sky)`} />
      <circle cx="855" cy="64" r="30" fill="var(--sdlc-sun)" />
      <g className={styles.clouds} fill="var(--sdlc-cloud)"><path d="M75 75c-5-25 33-40 49-19 28-17 54 9 42 25H75z" /><path d="M460 47c-6-22 28-36 43-17 24-15 46 8 36 22h-79z" /></g>
      <g fill="var(--sdlc-skyline)"><path d="M0 188h65v-31h22v-26h12v26h22v31h65v-53h15v-31h11v31h18v53h32v-23h62v23h121v-41h23v-31h12v31h24v41h77v-25h95v25h67v-45h16v-25h12v25h18v45h97v-30h34v30h118v162H0z" /></g>
      <path d="M0 220Q180 190 320 224t280-2 400-5v133H0z" fill="var(--sdlc-ground)" />
      <rect x="90" y="242" width="790" height="16" rx="4" fill="var(--sdlc-platform)" />
      <g transform="translate(92 121)"><rect width="148" height="39" rx="7" fill="var(--sdlc-sign)" /><text x="74" y="25" textAnchor="middle" fill="var(--sdlc-sign-text)" fontSize="16" fontWeight="600">{t('Nástupiště PR', 'PR platform')}</text><path d="M20 39v70m108-70v70" stroke="var(--sdlc-platform)" strokeWidth="5" /></g>
      {!onboard && Array.from({ length: step === 0 ? 1 : cars }, (_, i) => <Passenger key={i} x={272 + i * 100} y={147} size={100} lioness={i % 2 === 1} mood={failed ? 1 : 0} />)}
      <g className={styles.trainPosition} style={{ transform: `translateX(${onboard ? step * 12 : 0}px)` }}>
        <g className={styles.trainBounce}>
          {Array.from({ length: cars }, (_, i) => {
            const x = 180 + i * 170
            return <g key={i}>
              <path d={`M${x - 14} 272h190`} stroke="var(--sdlc-wheel)" strokeWidth="6" />
              <rect x={x} y="172" width="158" height="106" rx="13" fill={`url(#${id}-body)`} />
              <rect x={x + 8} y="169" width="142" height="12" rx="6" fill="var(--sdlc-roof)" />
              <rect x={x + 16} y="186" width="126" height="64" rx="8" fill="var(--sdlc-window)" />
              {onboard && <Passenger x={x + 30} y={155} size={98} lioness={i % 2 === 1} mood={mood} />}
              <path d={`M${x + 9} 252h140`} stroke="var(--sdlc-train-stripe)" strokeWidth="5" />
              <text x={x + 79} y="270" textAnchor="middle" fill="var(--sdlc-train-ink)" fontSize="13" fontWeight="600">PR {String.fromCharCode(65 + i)}</text>
              {[32, 126].map(wheel => <g key={wheel} className={styles.wheel}><circle cx={x + wheel} cy="284" r="15" fill="var(--sdlc-wheel)" /><circle cx={x + wheel} cy="284" r="7" fill="var(--sdlc-hub)" /><path d={`M${x + wheel - 9} 284h18m-9-9v18`} stroke="var(--sdlc-wheel-spoke)" strokeWidth="2" /></g>)}
            </g>
          })}
          <g transform={`translate(${190 + cars * 170} 0)`}>
            <path d="M-10 272h27" stroke="var(--sdlc-wheel)" strokeWidth="6" />
            <rect x="0" y="159" width="73" height="119" rx="12" fill={`url(#${id}-body)`} />
            <rect x="-7" y="151" width="88" height="14" rx="6" fill="var(--sdlc-roof)" />
            <rect x="12" y="173" width="47" height="50" rx="8" fill="var(--sdlc-window)" />
            <Passenger x={5} y={157} size={65} lioness mood={step === 4 ? 2 : 0} />
            <rect x="64" y="215" width="99" height="63" rx="18" fill={`url(#${id}-body)`} />
            <path d="M123 215v-33h23v33" fill="var(--sdlc-roof)" />
            <path d="M164 253l29 27h-34" fill="var(--sdlc-train-stripe)" />
            <circle cx="161" cy="233" r="9" fill="var(--sdlc-sun)" />
            <path d="M7 254h147" stroke="var(--sdlc-train-stripe)" strokeWidth="5" />
            <text x="38" y="245" textAnchor="middle" fill="var(--sdlc-train-ink)" fontSize="12" fontWeight="600">CI</text>
            {[32, 110].map((wheel, i) => <g key={wheel} className={styles.wheel}><circle cx={wheel} cy="281" r={i ? 23 : 18} fill="var(--sdlc-wheel)" /><circle cx={wheel} cy="281" r={i ? 13 : 9} fill="var(--sdlc-hub)" /><path d={`M${wheel - 12} 281h24m-12-12v24`} stroke="var(--sdlc-wheel-spoke)" strokeWidth="3" /></g>)}
            <g className={styles.steam} fill="var(--sdlc-cloud)"><circle cx="135" cy="164" r="10" /><circle cx="125" cy="143" r="15" /><circle cx="106" cy="120" r="19" /></g>
          </g>
        </g>
      </g>
      <rect y="303" width="1000" height="22" fill={`url(#${id}-sleepers)`} />
      <path d="M0 306h1000M0 321h1000" stroke="var(--sdlc-track)" strokeWidth="4" />
      <g transform="translate(914 192)"><path d="M0 0v109" stroke="var(--sdlc-wheel)" strokeWidth="6" /><rect x="-18" y="-29" width="36" height="70" rx="10" fill="var(--sdlc-sign)" /><circle cy="-10" r="10" fill={failed ? 'var(--danger)' : 'var(--sdlc-light-off)'} /><circle cy="21" r="10" fill={step === 4 && !failed ? 'var(--success)' : 'var(--sdlc-light-off)'} /></g>
    </svg>
  </div>
}

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
