// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ReferralLanding, REFERRAL_TOKEN_STORAGE_KEY } from '@/app/r/[token]/ReferralLanding'

// vi.mock is hoisted, so the token travels through a hoisted mutable holder.
const route = vi.hoisted(() => ({ token: 'AbCdEfGhIjKlMnOp' })) // 16 chars, base64url alphabet
vi.mock('next/navigation', () => ({ useParams: () => ({ token: route.token }) }))

const VALID_TOKEN = route.token

afterEach(() => {
  route.token = VALID_TOKEN
  sessionStorage.clear()
})

describe('referral (MGM) landing page', () => {
  it('renders the invite screen for a valid token and stores it for post-login attribution', () => {
    render(<ReferralLanding />)

    expect(screen.getByRole('heading', { level: 1, name: /you've been invited to openbank/i })).toBeInTheDocument()
    expect(screen.getByText(/rewards you both once you open your account/i)).toBeInTheDocument()
    expect(sessionStorage.getItem(REFERRAL_TOKEN_STORAGE_KEY)).toBe(VALID_TOKEN)

    const cta = screen.getByRole('link', { name: /continue to sign up/i })
    expect(cta).toHaveAttribute('href', `/auth/login?ref=${VALID_TOKEN}`)

    const login = screen.getByRole('link', { name: /already a customer\? log in/i })
    expect(login).toHaveAttribute('href', `/auth/login?ref=${VALID_TOKEN}`)
  })

  it.each([
    'tooshort',                     // 8 chars — below the 16 minimum
    'a'.repeat(129),                // above the 128 maximum
    'AbCdEfGhIjKlMnOp!',            // outside the base64url alphabet
    'AbCdEfGhIjKlMnOp=padding',     // base64url tokens never carry padding
  ])('renders the invalid-link state for %j and does NOT touch sessionStorage', (token) => {
    route.token = token
    render(<ReferralLanding />)

    expect(screen.getByText(/invalid or expired invitation link/i)).toBeInTheDocument()
    expect(sessionStorage.getItem(REFERRAL_TOKEN_STORAGE_KEY)).toBeNull()
    expect(screen.queryByRole('link', { name: /continue to sign up/i })).not.toBeInTheDocument()
  })

  it('accepts a token anywhere in the 16–128 range, including URL-safe characters', () => {
    const token = `${'-_'.repeat(8)}${'A9z'.repeat(37)}` // 127 chars, full base64url alphabet
    expect(token).toHaveLength(127)
    route.token = token
    render(<ReferralLanding />)

    expect(screen.getByRole('link', { name: /continue to sign up/i }))
      .toHaveAttribute('href', `/auth/login?ref=${token}`)
    expect(sessionStorage.getItem(REFERRAL_TOKEN_STORAGE_KEY)).toBe(token)
  })
})
