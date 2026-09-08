// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

type LoadMoreControlProps = {
  loaded: number
  total: number
  progressLabel: string
  buttonLabel: string
  buttonAriaLabel: string
  controls: string
  onLoadMore: () => void
  /** Disable only when the surrounding result surface already announces the same count. */
  announceProgress?: boolean
}

/** Bounded-list footer that keeps result extent visible and announces incremental progress. */
export function LoadMoreControl({
  loaded,
  total,
  progressLabel,
  buttonLabel,
  buttonAriaLabel,
  controls,
  onLoadMore,
  announceProgress = true,
}: LoadMoreControlProps) {
  const hasMore = loaded < total

  return (
    <div
      style={{
        padding: '12px 16px',
        borderTop: '1px solid var(--border)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 12,
        flexWrap: 'wrap',
      }}
    >
      <span
        role={announceProgress ? 'status' : undefined}
        aria-live={announceProgress ? 'polite' : undefined}
        style={{ fontSize: 12, color: 'var(--text-tertiary)' }}
      >
        {progressLabel}
      </span>
      {hasMore && (
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          aria-label={buttonAriaLabel}
          aria-controls={controls}
          onClick={onLoadMore}
        >
          {buttonLabel}
        </button>
      )}
    </div>
  )
}
