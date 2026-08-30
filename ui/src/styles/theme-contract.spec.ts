import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const theme = readFileSync(resolve(process.cwd(), 'src/styles/showcase-theme.css'), 'utf8')
const homepage = readFileSync(resolve(process.cwd(), 'src/components/showcase/showcase-home.css'), 'utf8')
const workflowCss = readFileSync(resolve(process.cwd(), 'src/styles/workflow.css'), 'utf8')
const customerCss = readFileSync(resolve(process.cwd(), 'src/components/customer-service.css'), 'utf8')
const compact = (css: string) => css.replace(/\s+/g, '')

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

  it('keeps workflow and customer scenes free of legacy light surfaces', () => {
    const forbiddenLegacySurfaces = [
      '#edf3f2', '#f0f7f5', '#f9fbfa', '#f3faf8', '#eef8f5',
      'rgba(251,253,252,.94)', 'rgba(255,255,255,.92)',
    ]

    for (const color of forbiddenLegacySurfaces) {
      expect(compact(workflowCss)).not.toContain(color)
      expect(compact(customerCss)).not.toContain(color)
    }
  })

  it('uses dark scene surfaces for workflow nodes and visitor messages', () => {
    const workflowNode = compact(workflowCss).match(/\.workflow-node\.node-inner\{([^}]*)\}/)?.[1]
    const visitorMessage = compact(customerCss).match(/\.chat-message\.userp\{([^}]*)\}/)?.[1]

    expect(workflowNode).toContain('background:rgba(8,12,20,0.72)')
    expect(workflowNode).not.toMatch(/background:(white|#fff)/)
    expect(visitorMessage).toContain('background:linear-gradient(135deg,var(--showcase-cyan),#9befff)')
    expect(visitorMessage).not.toMatch(/background:(white|#fff)/)
  })
})
