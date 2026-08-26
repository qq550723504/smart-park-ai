import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

const stylesPath = [resolve(process.cwd(), 'src/styles.css'), resolve(process.cwd(), 'ui/src/styles.css')]
  .find((candidate) => existsSync(candidate))
if (!stylesPath) throw new Error('Unable to locate ui/src/styles.css')
const styles = readFileSync(stylesPath, 'utf8').replace(/\s+/g, '')

function cssRule(selector: string): string {
  return styles.match(new RegExp(`${selector}\\s*\\{[^}]*\\}`, 's'))?.[0] ?? ''
}

describe('shared page layout contract', () => {
  it('makes every page fill the workspace column beside the execution rail', () => {
    const sharedMainRule = styles.match(/\.workspace\s*>\s*\.main-content\s*\{[^}]*\}/s)?.[0] ?? ''
    expect(sharedMainRule).toContain('width:100%')
    expect(sharedMainRule).toContain('margin:0')
  })

  it('keeps the shared page container from imposing a shrink-to-fit width', () => {
    const sharedMainRule = cssRule('\\.workspace\\s*>\\s*\\.main-content')
    expect(sharedMainRule).toContain('min-width:0')
    expect(sharedMainRule).toContain('max-width:none')
  })

  it('keeps the execution rail beside pages only above the shared stacking breakpoint', () => {
    expect(cssRule('\\.workspace')).toContain('grid-template-columns:minmax(0,1fr)minmax(320px,360px)')
    expect(styles).toContain('@media(max-width:1250px){.workspace{grid-template-columns:1fr}')
  })
})
