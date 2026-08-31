import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

const stylesPath = [
  resolve(process.cwd(), 'src/components/workbench/immersive-workbench.css'),
  resolve(process.cwd(), 'ui/src/components/workbench/immersive-workbench.css'),
]
  .find((candidate) => existsSync(candidate))
if (!stylesPath) throw new Error('Unable to locate immersive workbench styles')
const styles = readFileSync(stylesPath, 'utf8').replace(/\s+/g, '')

function cssRule(selector: string): string {
  return styles.match(new RegExp(`${selector}\\s*\\{[^}]*\\}`, 's'))?.[0] ?? ''
}

describe('shared page layout contract', () => {
  it('keeps the execution rail in the 340–380px desktop shell column', () => {
    expect(cssRule('\\.immersive-workbench__workspace')).toContain('grid-template-columns:minmax(0,1fr)minmax(340px,380px)')
  })

  it('stacks the shell rail at the 1279px breakpoint', () => {
    expect(styles).toContain('@media(max-width:1279px){.immersive-workbench__workspace{grid-template-columns:1fr;}')
  })
})
