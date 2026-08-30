import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const theme = readFileSync(resolve(process.cwd(), 'src/styles/showcase-theme.css'), 'utf8')
const homepage = readFileSync(resolve(process.cwd(), 'src/components/showcase/showcase-home.css'), 'utf8')

describe('showcase theme contract', () => {
  it.each([
    ['--showcase-graphite', '#06090f'],
    ['--showcase-graphite-2', '#0c111a'],
    ['--showcase-cyan', '#70e8ff'],
    ['--showcase-violet', '#8f5cff'],
    ['--showcase-ivory', '#fff0d2'],
    ['--showcase-amber', '#ffd27a'],
  ])('defines %s as %s', (token, value) => {
    expect(theme.replace(/\s+/g, '')).toContain(`${token}:${value}`)
  })

  it('keeps homepage tokens in the shared theme instead of redeclaring them', () => {
    expect(homepage).not.toContain('--showcase-graphite:')
  })
})
