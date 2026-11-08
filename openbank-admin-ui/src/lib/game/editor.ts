// SPDX-License-Identifier: Apache-2.0
export type Item = Record<string, number | string | boolean>
export type Course = {
  schema: number
  id: string
  theme: string
  levelEnd: number
  vaultX: number
  ground: Item[]
  platforms: Item[]
  coins: Item[]
  vampires: Item[]
  bats: Item[]
  piggies: number[]
  shields: Item[]
  slopes?: Item[]
  thieves?: Item[]
  dropZones?: Item[]
  rooftopObjects?: Item[]
  pigeons?: Item[]
  bridgePlatforms?: Item[]
  bridgeTraps?: Item[]
  pickpocketBoss?: boolean
  paperworkBossX?: number | null
}
export type Requirement = {
  kind: 'NONE' | 'PREVIOUS_LEVEL' | 'EARN_SOURCE'
  source: string | null
  count: number
  hintCs: string
  hintEn: string
}
export type Level = {
  id: string
  theme: string
  order: number
  titleCs: string
  titleEn: string
  state: 'OFF' | 'BETA' | 'LIVE'
  startsAt: string | null
  endsAt: string | null
  requirement: Requirement
  content?: Course | null
}
export type Catalog = { revision: number; levels: Level[] }
export const art = (name: string) => `/lion-quest/game_${name}.webp`
export const worlds = [
  {
    id: 'embankment',
    cs: 'Náplavka',
    en: 'Embankment',
    sky: 'bg1_sky',
    far: 'bg1_far',
    mid: 'bg1_mid',
    floor: 'walkway',
    hue: '#2cb6c9',
  },
  {
    id: 'rooftops',
    cs: 'Staroměstské střechy',
    en: 'Old Town rooftops',
    sky: 'l2_sky',
    far: 'l2_far',
    mid: 'l2_mid',
    floor: 'l2_near',
    hue: '#e6a34a',
  },
  {
    id: 'charles_bridge',
    cs: 'Karlův most',
    en: 'Charles Bridge',
    sky: 'bridge_dawn',
    far: 'bridge_statues',
    mid: 'bridge_parapet',
    floor: 'bridge_deck',
    hue: '#9b8fe2',
  },
]
export const components = [
  { key: 'coins', cs: 'Mince', en: 'Coin', asset: 'coin_f1', defaults: { y: 38, fake: false } },
  { key: 'vampires', cs: 'Upír', en: 'Vampire', asset: 'vamp_f01', defaults: { y: 70, boss: false } },
  { key: 'bats', cs: 'Létající nepřítel', en: 'Flying enemy', asset: 'crow_f01', defaults: { cy: 180, span: 90 } },
  { key: 'shields', cs: 'Ochranný štít', en: 'Shield', asset: 'ui_leaf_active', defaults: { y: 100 } },
  { key: 'platforms', cs: 'Plošina', en: 'Platform', asset: 'bridge_platform', defaults: { y: 120, w: 160 } },
  { key: 'thieves', cs: 'Zloděj', en: 'Thief', asset: 'l02_thief_movement', defaults: { y: 0 } },
  { key: 'pigeons', cs: 'Holub', en: 'Pigeon', asset: 'l02_pigeon', defaults: { y: 100 } },
  {
    key: 'bridgePlatforms',
    cs: 'Pohyblivá plošina',
    en: 'Moving platform',
    asset: 'bridge_platform',
    defaults: { y: 0, w: 180, dx: 200, dy: 0, period: 4, offset: 0, collapseAfter: 0 },
  },
  { key: 'bridgeTraps', cs: 'Past u sochy', en: 'Statue trap', asset: 'bridge_statues', defaults: { direction: 1 } },
  {
    key: 'rooftopObjects',
    cs: 'Střešní překážka',
    en: 'Rooftop obstacle',
    asset: 'l02_chimney',
    defaults: { y: 120, w: 100, baseY: 0, kind: 'CHIMNEY' },
  },
  { key: 'dropZones', cs: 'Padající překážka', en: 'Falling obstacle', asset: 'l02_pot', defaults: { y: 220 } },
] as const
export function parseCatalog(value: unknown): Catalog {
  const v = value as Catalog
  if (
    !v ||
    !Number.isSafeInteger(v.revision) ||
    v.revision < 0 ||
    !Array.isArray(v.levels) ||
    v.levels.length > 30 ||
    !v.levels.every(
      (l) =>
        l &&
        typeof l.id === 'string' &&
        typeof l.theme === 'string' &&
        Number.isInteger(l.order) &&
        typeof l.titleCs === 'string' &&
        typeof l.titleEn === 'string' &&
        ['OFF', 'BETA', 'LIVE'].includes(l.state) &&
        l.requirement &&
        ['NONE', 'PREVIOUS_LEVEL', 'EARN_SOURCE'].includes(l.requirement.kind),
    )
  )
    throw new Error('Invalid catalogue')
  return v
}
export function courseProblems(c: Course): string[] {
  const errors: string[] = []
  if (c.schema !== 1 || !Number.isFinite(c.levelEnd) || c.levelEnd < 1000 || c.levelEnd > 50000) errors.push('length')
  if (
    !Number.isFinite(c.vaultX) ||
    c.vaultX >= c.levelEnd ||
    !c.ground.some((g) => Number(g.x0) <= c.vaultX - 60 && Number(g.x1) >= c.vaultX + 60)
  )
    errors.push('finish')
  if (!c.ground.length || Number(c.ground[0].x0) > 80 || Number(c.ground.at(-1)?.x1) < c.levelEnd) errors.push('ground')
  for (const [i, g] of c.ground.entries()) {
    if (Number(g.x1) <= Number(g.x0)) errors.push('ground')
    if (i) {
      const gap = Number(g.x0) - Number(c.ground[i - 1].x1)
      if (c.theme === 'charles_bridge' ? gap < 140 || gap > 600 : gap !== 140) errors.push('gap')
    }
  }
  if (!c.coins.some((item) => !item.fake)) errors.push('coins')
  for (const value of Object.values(c))
    if (Array.isArray(value)) {
      if (value.length > 200) errors.push('count')
      for (const item of value)
        for (const n of typeof item === 'object' ? Object.values(item) : [item]) {
          if (typeof n === 'number' && (!Number.isFinite(n) || Math.abs(n) > 50000)) errors.push('coordinate')
        }
    }
  return [...new Set(errors)]
}
