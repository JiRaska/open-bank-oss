// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { courseItem, type Course } from '@/lib/game/editor'
const course: Course = {
  schema: 1,
  id: 'example',
  theme: 'charles_bridge',
  levelEnd: 2000,
  vaultX: 1700,
  ground: [{ x0: -400, x1: 2000 }],
  platforms: [],
  coins: [{ x: 150, y: 38 }],
  vampires: [],
  bats: [],
  piggies: [0],
  shields: [],
  bridgePlatforms: [{ x: 600, y: 0, w: 180 }],
}
describe('native course inspector', () => {
  it('shows omitted native defaults without rewriting original authored data', () => {
    const coin = courseItem(course, 'coins', 0)!
    expect(coin.fake).toBe(false)
    coin.fake = true
    expect(course.coins[0].fake).toBeUndefined()
    expect(courseItem(course, 'bridgePlatforms', 0)).toMatchObject({ period: 4, dx: 0, dy: 0, collapseAfter: 0 })
    expect(course.bridgePlatforms![0].period).toBeUndefined()
  })
  it('keeps a zero-position deposit selectable and never invents missing components', () => {
    expect(courseItem(course, 'piggies', 0)).toEqual({ x: 0 })
    expect(courseItem(course, 'coins', 99)).toBeNull()
    expect(courseItem(course, 'levelEnd', 0)).toBeNull()
  })
})
