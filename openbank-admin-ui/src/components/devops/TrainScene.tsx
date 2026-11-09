// SPDX-License-Identifier: Apache-2.0
'use client'

import { useId } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { TRAIN_LANES, type TrainLane, type TrainProgress } from './sdlc-content'
import styles from './SdlcGuide.module.css'

function Passenger({ x, lioness, mood }: { x: number; lioness: boolean; mood: number }) {
  return <svg className={styles.passenger} data-mood={mood} x={x} y="523" width="102" height="102" viewBox="0 0 512 512" overflow="hidden" aria-hidden="true">
    <image href="/brand/sdlc/train-passengers.png" x={-mood * 512} y={lioness ? -512 : 0} width="1536" height="1024" />
  </svg>
}

/** Paper props stay inside the cabin; passengers only appear once they board. */
function JourneyPaper({ x, checking, failed, label }: { x: number; checking: boolean; failed: boolean; label: string }) {
  const { t } = useLanguage()
  return <g transform={`translate(${x} 536)`} aria-hidden="true">
    <rect x="3" y="4" width="133" height="58" rx="3" fill="#000" opacity=".3" />
    <rect width="133" height="58" rx="3" fill="#e6d7b9" stroke="#b79862" />
    <path d="M99 3v52" stroke="#9e835b" strokeDasharray="2 3" />
    <text x="10" y="17" fill="#463824" fontSize="10" fontWeight="600" letterSpacing="1">{label}</text>
    {checking ? <>
      <text x="10" y="32" fill="#635239" fontSize="8" letterSpacing="1">{t('CI / REVIEW', 'CI / REVIEW')}</text>
      <path d="M10 42h47M10 47h33" stroke="#a18a64" />
      <circle cx="77" cy="36" r="10" fill="none" stroke={failed ? '#9d3c36' : '#39654c'} strokeWidth="1.5" />
      <path d={failed ? 'M73 32l8 8m0-8l-8 8' : 'M72 36l3 3 7-7'} fill="none" stroke={failed ? '#9d3c36' : '#39654c'} strokeWidth="1.5" />
      <text x="108" y="36" fill="#635239" fontSize="9">PR</text>
    </> : <>
      <path d="M10 27h50M10 33h38M10 39h44M10 45h26" stroke="#a18a64" />
      <path d="M71 42l14-19 3 3-14 19-5 3z" fill="#6c5431" />
      <text x="108" y="36" fill="#635239" fontSize="9">01</text>
    </>}
  </g>
}

// Running surfaces measured in masaryk-station-parallel.webp (1536 × 1024).
const TRACKS = [
  { lane: 'batch', railY: 634, x: 300 },
  { lane: 'fast', railY: 798, x: 445 },
  { lane: 'strict', railY: 971, x: 365 },
] as const
const TRAIN_SCALE = .72
const WHEEL_CONTACT_Y = 680

function LaneTrain({ lane, step, failed, playing, selected, railY, x: trackX }: {
  lane: TrainLane; step: number; failed: boolean; playing: boolean; selected: boolean; railY: number; x: number
}) {
  const { language, t } = useLanguage()
  const id = useId().replaceAll(':', '')
  const cars = lane === 'batch' ? 3 : 1
  const onboard = step >= 2
  const mood = failed || step === 3 ? 1 : step === 4 ? 2 : 0
  const engineX = cars * 300
  const moving = playing && onboard && step < 4 && !failed
  const status = failed ? t('Kontrola selhala', 'Check failed') : TRAIN_LANES[lane].stops[step][language]
  return <g data-lane={lane} data-step={step} data-selected={selected} data-moving={moving}>
    <defs>
      <linearGradient id={`${id}-glass`} x2="1" y2="1"><stop stopColor="#b8d4ec" stopOpacity=".1" /><stop offset=".4" stopColor="#b8d4ec" stopOpacity="0" /><stop offset="1" stopColor="#06101f" stopOpacity=".18" /></linearGradient>
      <filter id={`${id}-contact`} x="-10%" y="-200%" width="120%" height="500%"><feGaussianBlur stdDeviation="2" /></filter>
      {Array.from({ length: cars }, (_, i) => {
        const x = i * 300
        return <g key={i}>
          <clipPath id={`${id}-window-${i}`}><rect x={x + 59} y="522" width="183" height="78" rx="10" /></clipPath>
          <mask id={`${id}-frame-${i}`} maskUnits="userSpaceOnUse" x={x} y="458" width="300" height="225">
            <rect x={x} y="458" width="300" height="225" fill="white" />
            <rect x={x + 59} y="522" width="183" height="78" rx="10" fill="black" />
          </mask>
        </g>
      })}
    </defs>
    <g transform={`translate(${trackX} ${railY - TRAIN_SCALE * WHEEL_CONTACT_Y}) scale(${TRAIN_SCALE})`}>
      <g className={styles.trainPosition} style={{ transform: `translateX(${onboard ? step * 10 : 0}px)` }}>
        <rect x="18" y="679" width={engineX + 560} height="3" rx="2" fill="#000" opacity=".6" filter={`url(#${id}-contact)`} aria-hidden="true" />
        {Array.from({ length: cars }, (_, i) => {
          const x = i * 300
          // The coach tread is y=1041 in its 1086px source; +6.3 aligns it to y=680.
          return <g key={i} transform="translate(0 6.3)">
            <image href="/brand/sdlc/heritage-coach.webp" x={x} y="458" width="300" height="225" aria-hidden="true" />
            <g clipPath={`url(#${id}-window-${i})`} data-cabin={i} aria-hidden="true">
              <rect x={x + 81} y="568" width="138" height="37" rx="9" fill="#352a23" stroke="#6d5032" />
              <path d={`M${x + 88} 576h124M${x + 150} 571v29`} stroke="#997149" opacity=".35" />
              {onboard
                ? <Passenger x={x + 99} lioness={i % 2 === 1} mood={mood} />
                : <JourneyPaper x={x + 84} checking={step === 1 || failed} failed={failed} label={step === 1 || failed ? `PR ${String.fromCharCode(65 + i)}` : t('NÁVRH ZMĚNY', 'CHANGE PROPOSAL')} />}
              <rect x={x + 59} y="522" width="183" height="78" fill={`url(#${id}-glass)`} />
              <path d={`M${x + 66} 527h168`} stroke="#ffd59a" opacity=".16" />
            </g>
            <image href="/brand/sdlc/heritage-coach.webp" x={x} y="458" width="300" height="225" mask={`url(#${id}-frame-${i})`} aria-hidden="true" />
            <image href="/brand/sdlc/openbank-mark.svg" x={x + 136} y="610" width="28" height="18" aria-hidden="true" />
            <text x={x + 150} y="443" textAnchor="middle" fill="#e8f8ff" fontSize="20" fontWeight="600" letterSpacing="3">PR {String.fromCharCode(65 + i)}</text>
          </g>
        })}
        <g transform="translate(0 1.4)">
          <svg x={engineX} y="495" width="600" height="200" viewBox="0 0 1536 512" overflow="hidden" aria-hidden="true"><image href="/brand/sdlc/train-atlas-v3.webp" width="1536" height="1024" /></svg>
          <image href="/brand/sdlc/openbank-mark.svg" x={engineX + 91} y="603" width="32" height="21" aria-hidden="true" />
        </g>
      </g>
    </g>
    <g transform={`translate(42 ${railY - 130})`} aria-hidden="true">
      <rect width="235" height="91" rx="5" fill="#0b1928" fillOpacity=".92" stroke={selected ? '#46c7ff' : '#8b9ba7'} strokeOpacity={selected ? 1 : .45} />
      <text x="16" y="25" fill="#a8c6d8" fontSize="13" letterSpacing="2">{t('KOLEJ', 'TRACK')} {TRACKS.findIndex(track => track.lane === lane) + 1}</text>
      <text x="16" y="50" fill="#edf6fa" fontSize="20" fontWeight="600">{TRAIN_LANES[lane].title[language]}</text>
      <text x="16" y="74" fill={failed ? '#ffb1a8' : '#c1d4df'} fontSize="15">{status}</text>
    </g>
    <g transform={`translate(1462 ${railY - 110})`} aria-hidden="true">
      <path d="M14 79v31" stroke="#263743" strokeWidth="5" />
      <rect width="28" height="79" rx="12" fill="#0b1928" stroke="#748696" />
      <circle cx="14" cy="19" r="6" fill={failed ? '#ff7171' : '#293a46'} />
      <circle cx="14" cy="39" r="6" fill={!failed && step < 4 ? '#ffcb78' : '#293a46'} />
      <circle cx="14" cy="59" r="6" fill={!failed && step === 4 ? '#66e2b0' : '#293a46'} />
    </g>
  </g>
}

/** Independent CI lanes share a station; merging still obeys main's reservations. */
export function TrainScene({ lane, steps, playing, failed }: { lane: TrainLane; steps: TrainProgress; playing: boolean; failed: boolean }) {
  const { t } = useLanguage()
  const id = useId().replaceAll(':', '')
  const step = steps[lane]
  return <div className={styles.scene} data-playing={playing}>
    <svg viewBox="0 0 1536 1024" className={styles.sceneSvg} role="img" aria-labelledby={`${id}-title`} aria-describedby={`${id}-desc`}>
      <title id={`${id}-title`}>{t('Lví vláček změn', 'Lion change train')}</title>
      <desc id={`${id}-desc`}>{t(
        `Modelový příklad tří souběžných kolejí inspirovaný Masarykovým nádražím. Vybraná kolej: ${TRAIN_LANES[lane].title.cs}. ${lane === 'batch' ? 'Tři návrhy změn' : 'Jeden návrh změny'}. ${failed ? 'Neúspěšná kontrola zastavila tuto kolej.' : step === 4 ? 'Lvíčci oslavují úspěšné sloučení.' : step >= 2 ? 'Lvíčci nastoupili do vagónů.' : step === 1 ? 'Kontrola jízdenek představuje kontroly PR před nástupem.' : 'Návrh změny čeká na ověření před nástupem.'} Kontroly mohou běžet souběžně; sloučení do main se řídí pořadím a rezervacemi.`,
        `Illustrative example of three concurrent tracks inspired by Masaryk station. Selected lane: ${TRAIN_LANES[lane].title.en}. ${lane === 'batch' ? 'Three proposed changes' : 'One proposed change'}. ${failed ? 'A failed check has stopped this lane.' : step === 4 ? 'The lions celebrate a successful merge.' : step >= 2 ? 'The lions have boarded their coaches.' : step === 1 ? 'Ticket inspection represents PR checks before boarding.' : 'A proposed change awaits verification before boarding.'} Checks can run concurrently; merges into main follow ordering and reservations.`,
      )}</desc>
      <defs><linearGradient id={`${id}-shade`} x2="0" y2="1"><stop stopColor="#06101f" stopOpacity=".75" /><stop offset=".28" stopColor="#06101f" stopOpacity=".05" /><stop offset="1" stopColor="#06101f" stopOpacity=".12" /></linearGradient></defs>
      <image href="/brand/sdlc/masaryk-station-parallel.webp" width="1536" height="1024" />
      <rect width="1536" height="1024" fill={`url(#${id}-shade)`} />
      <g aria-hidden="true" className={styles.sceneMasthead}>
        <image href="/brand/sdlc/openbank-mark.svg" x="62" y="34" width="58" height="38" />
        <text x="138" y="59" fill="#e7f6ff" fontSize="20" letterSpacing="5">{t('OpenBank / CI EXPRESS', 'OpenBank / CI EXPRESS')}</text>
        <text x="64" y="105" fill="#c3d8eb" fontSize="17">{t('Praha · inspirace Masarykovým nádražím', 'Prague · inspired by Masaryk station')}</text>
        <text x="64" y="139" fill="#c3d8eb" fontSize="16">{t('Souběžné kontroly · řízené sloučení do main', 'Concurrent checks · governed merges into main')}</text>
      </g>
      {TRACKS.map(track => <LaneTrain key={track.lane} {...track} step={steps[track.lane]} failed={failed && lane === track.lane} playing={playing} selected={lane === track.lane} />)}
      <text x="64" y="1008" fill="#d1dce3" fontSize="13" letterSpacing="2" aria-hidden="true">{t('ILUSTRAČNÍ PROVOZ · NEJDE O ŽIVOU FRONTU', 'ILLUSTRATIVE OPERATIONS · NOT A LIVE QUEUE')}</text>
    </svg>
  </div>
}
