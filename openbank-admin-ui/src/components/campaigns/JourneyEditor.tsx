// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * The campaign builder as a canvas: entry → step → step, clicked rather than typed.
 *
 * The form it replaces asked a marketer for `template`, `variables` and `delaySeconds` — the
 * engine's vocabulary, in the engine's order. This shows the journey they are building while they
 * build it, which is what every tool in this category does and the reason ours read as a developer
 * screen.
 *
 * **On ADR-0221 D5.** That decision rejects "a drag-and-drop journey canvas", and the reasoning is
 * sound: a free-form 40-node graph is where campaign tools go to die. This is deliberately not that.
 * The flow is LINEAR and BOUNDED — the domain caps a journey at five steps (`Campaign.MAX_STEPS`),
 * and there is no arbitrary edge to draw, no branching to lay out, nothing to arrange. You add a
 * step, you edit it, you remove it. It is the wizard's step list rendered as the thing it describes,
 * which is what D5's "the wizard's step list covers the honest use cases" already wanted — it just
 * did not say it could be drawn.
 *
 * Deliberately still absent, because D5 and ADR-0176 D4 forbid them and the service enforces both:
 * no free-text body anywhere (only declared template variables), and no way to author a segment —
 * that is a pull request against the catalogue.
 */

export interface EditorStep {
  template: string
  variables: Record<string, string>
  delaySeconds: number
}

export const MAX_STEPS = 5

const NODE_W = 176
const NODE_H = 72
const GAP_X = 84
const ROW_Y = 70
const PAD = 24

export function JourneyEditor({
  steps,
  audience,
  audienceSize,
  selected,
  onSelect,
  onAdd,
  onRemove,
  templateLabels,
}: {
  steps: EditorStep[]
  /** `name@version`, or empty while the marketer has not chosen one. */
  audience: string
  audienceSize: number | null
  selected: number | null
  onSelect: (index: number | null) => void
  onAdd: () => void
  onRemove: (index: number) => void
  templateLabels: Record<string, string>
}) {
  const { t, language } = useLanguage()
  const n = (v: number) => v.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')

  const delayLabel = (s: number): string => {
    if (s <= 0) return t('ihned', 'immediately')
    const d = Math.floor(s / 86400)
    if (d >= 1) return t(`za ${d} d`, `after ${d} d`)
    const h = Math.floor(s / 3600)
    if (h >= 1) return t(`za ${h} h`, `after ${h} h`)
    return t(`za ${Math.floor(s / 60)} min`, `after ${Math.floor(s / 60)} min`)
  }

  const canAdd = steps.length < MAX_STEPS
  // Entry + steps + the add affordance, which occupies a slot so the canvas does not jump when a
  // step is added.
  const cols = 1 + steps.length + (canAdd ? 1 : 0)
  const width = PAD * 2 + cols * NODE_W + (cols - 1) * GAP_X
  const height = ROW_Y + NODE_H / 2 + 56

  const colX = (i: number) => PAD + i * (NODE_W + GAP_X)

  const edge = (fromIdx: number, toIdx: number, label: string) => {
    const x0 = colX(fromIdx) + NODE_W
    const x1 = colX(toIdx)
    const mid = (x0 + x1) / 2
    return (
      <g key={`e${fromIdx}`}>
        <path
          d={`M ${x0} ${ROW_Y} L ${x1} ${ROW_Y}`}
          stroke="var(--border-strong)" strokeWidth="1.6" fill="none" markerEnd="url(#je-arrow)"
        />
        <text x={mid} y={ROW_Y - 10} fontSize="11" textAnchor="middle" fill="var(--text-secondary)">
          {label}
        </text>
      </g>
    )
  }

  return (
    <div className="overflow-x-auto rounded-xl border" style={{ background: 'var(--surface)' }}>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        style={{ width: '100%', minWidth: Math.min(width, 760), height: 'auto', display: 'block' }}
        role="img"
        aria-label={t('Plátno pro sestavení kampaně', 'Campaign builder canvas')}
      >
        <defs>
          <marker id="je-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 Z" fill="var(--border-strong)" />
          </marker>
          <filter id="je-shadow" x="-20%" y="-20%" width="140%" height="140%">
            <feDropShadow dx="0" dy="1" stdDeviation="1.5" floodOpacity="0.10" />
          </filter>
        </defs>

        {/* Entry — the audience. Not editable here: a segment is code (ADR-0201 D1), so this node
            reports the choice made above rather than offering to author one. */}
        <g filter="url(#je-shadow)">
          <rect
            x={colX(0)} y={ROW_Y - NODE_H / 2} width={NODE_W} height={NODE_H} rx="14"
            fill="var(--surface-2)" stroke="var(--border-strong)" strokeWidth="1.2"
          />
          <text x={colX(0) + 14} y={ROW_Y - 14} fontSize="10" fill="var(--text-secondary)">
            {t('PUBLIKUM', 'AUDIENCE')}
          </text>
          <text x={colX(0) + 14} y={ROW_Y + 6} fontSize="13" fontWeight="600" fill="var(--text-primary)">
            {audience || t('zatím nevybráno', 'not chosen yet')}
          </text>
          <text x={colX(0) + 14} y={ROW_Y + 24} fontSize="11" fill="var(--text-secondary)">
            {audienceSize === null ? t('—', '—') : `${n(audienceSize)} ${t('lidí', 'people')}`}
          </text>
        </g>

        {steps.map((step, i) => {
          const x = colX(i + 1)
          const isSelected = selected === i
          return (
            <g key={i}>
              {edge(i, i + 1, delayLabel(step.delaySeconds))}
              <g
                filter="url(#je-shadow)"
                style={{ cursor: 'pointer' }}
                onClick={() => onSelect(isSelected ? null : i)}
                role="button"
                aria-label={t(`Upravit krok ${i + 1}`, `Edit step ${i + 1}`)}
                data-step={i}
                data-selected={isSelected ? 'true' : 'false'}
              >
                <rect
                  x={x} y={ROW_Y - NODE_H / 2} width={NODE_W} height={NODE_H} rx="14"
                  fill="var(--surface-2)"
                  stroke={isSelected ? 'var(--accent)' : 'var(--border-strong)'}
                  strokeWidth={isSelected ? 2 : 1.2}
                />
                <text x={x + 14} y={ROW_Y - 14} fontSize="10" fill="var(--text-secondary)">
                  {t('KROK', 'STEP')} {i + 1} · {t('e-mail', 'email')}
                </text>
                <text x={x + 14} y={ROW_Y + 8} fontSize="13" fontWeight="600" fill="var(--text-primary)">
                  {templateLabels[step.template] ?? step.template}
                </text>
              </g>

              {/* Remove sits on the node rather than in a toolbar: the thing it acts on is the thing
                  you are looking at, and a five-step journey never needs a bulk operation. */}
              <g
                style={{ cursor: 'pointer' }}
                onClick={() => onRemove(i)}
                role="button"
                aria-label={t(`Odebrat krok ${i + 1}`, `Remove step ${i + 1}`)}
                data-remove-step={i}
              >
                <circle cx={x + NODE_W - 14} cy={ROW_Y - NODE_H / 2 + 14} r="9" fill="var(--surface-3)" />
                <text
                  x={x + NODE_W - 14} y={ROW_Y - NODE_H / 2 + 18}
                  fontSize="13" textAnchor="middle" fill="var(--text-secondary)"
                >
                  ×
                </text>
              </g>
            </g>
          )
        })}

        {canAdd && (
          <g>
            {steps.length > 0 && edge(steps.length, steps.length + 1, '')}
            {steps.length === 0 && edge(0, 1, '')}
            <g
              style={{ cursor: 'pointer' }}
              onClick={onAdd}
              role="button"
              aria-label={t('Přidat krok', 'Add a step')}
              data-add-step="true"
            >
              <rect
                x={colX(steps.length + 1)} y={ROW_Y - NODE_H / 2}
                width={NODE_W} height={NODE_H} rx="14"
                fill="none" stroke="var(--border-strong)" strokeWidth="1.4" strokeDasharray="5 4"
              />
              <text
                x={colX(steps.length + 1) + NODE_W / 2} y={ROW_Y + 5}
                fontSize="13" textAnchor="middle" fill="var(--text-secondary)"
              >
                + {t('přidat krok', 'add a step')}
              </text>
            </g>
          </g>
        )}

        {!canAdd && (
          // Stated, not enforced silently: the cap is a domain rule (Campaign.MAX_STEPS), and a
          // marketer who cannot find the add button deserves to know why rather than assume a bug.
          <text
            x={width - PAD} y={ROW_Y + NODE_H / 2 + 30}
            fontSize="11" textAnchor="end" fill="var(--text-secondary)"
          >
            {t(
              `Maximum je ${MAX_STEPS} kroků — delší cesty se v praxi neudrží.`,
              `${MAX_STEPS} steps is the maximum — longer journeys do not survive contact with reality.`,
            )}
          </text>
        )}
      </svg>
    </div>
  )
}
