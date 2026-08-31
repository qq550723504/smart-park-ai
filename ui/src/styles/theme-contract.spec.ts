import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const theme = readFileSync(resolve(process.cwd(), 'src/styles/showcase-theme.css'), 'utf8')
const homepage = readFileSync(resolve(process.cwd(), 'src/components/showcase/showcase-home.css'), 'utf8')
const analyticsCss = readFileSync(resolve(process.cwd(), 'src/components/analytics/analytics.css'), 'utf8')
const shellCss = readFileSync(resolve(process.cwd(), 'src/components/workbench/immersive-workbench.css'), 'utf8')
const workbenchPrimitivesCss = readFileSync(resolve(process.cwd(), 'src/styles/workbench-primitives.css'), 'utf8')
const workflowCss = readFileSync(resolve(process.cwd(), 'src/styles/workflow.css'), 'utf8')
const customerCss = readFileSync(resolve(process.cwd(), 'src/components/customer-service.css'), 'utf8')
const readIfPresent = (path: string) => existsSync(path) ? readFileSync(path, 'utf8') : ''
const collaborationCss = readIfPresent(resolve(process.cwd(), 'src/components/expert-collaboration.css'))
const voiceCss = readIfPresent(resolve(process.cwd(), 'src/components/voice/voice-assistant.css'))
const executionRailCss = readIfPresent(resolve(process.cwd(), 'src/components/execution/execution-rail.css'))
const legacyStylesPath = resolve(process.cwd(), 'src', ['styles', 'css'].join('.'))
const operationsWorkbench = readFileSync(resolve(process.cwd(), 'src/components/OperationsWorkbench.vue'), 'utf8')
const customerConsole = readFileSync(resolve(process.cwd(), 'src/components/CustomerServiceConsole.vue'), 'utf8')
const collaborationPage = readFileSync(resolve(process.cwd(), 'src/components/ExpertCollaborationPage.vue'), 'utf8')
const voicePage = readFileSync(resolve(process.cwd(), 'src/components/voice/VoiceAssistantPage.vue'), 'utf8')
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

  it('owns collaboration and voice rules in their scenes and removes the monolithic stylesheet', () => {
    expect(compact(collaborationCss)).toContain('.collaboration-main{')
    expect(compact(collaborationCss)).toContain('.expert-card{')
    expect(compact(voiceCss)).toContain('.voice-page{')
    expect(compact(voiceCss)).toContain('.voice-mic-button{')
    expect(compact(voiceCss)).toContain('.voice-tools.evidence-list{')
    expect(existsSync(legacyStylesPath)).toBe(false)
  })

  it('keeps evidence chips isolated within their scenes', () => {
    const voiceEvidenceChip = compact(voiceCss).match(/\.voice-tools\.evidence-listspan\{([^}]*)\}/)?.[1]

    expect(collaborationCss).toContain('.collaboration-main .evidence-list {')
    expect(collaborationCss).toContain('.collaboration-main .evidence-list span {')
    expect(collaborationCss).not.toMatch(/(?:^|\n)\.evidence-list(?:\s|\{)/)
    expect(voiceEvidenceChip).toContain('font-family:ui-monospace,monospace')
    expect(voiceEvidenceChip).toContain('padding:4px6px')
    expect(voiceEvidenceChip).toContain('color:var(--showcase-cyan)')
    expect(voiceEvidenceChip).toContain('background:var(--showcase-cyan-soft)')
  })

  it('stacks collaboration and voice layouts for the full tablet range', () => {
    expect(compact(collaborationCss)).toContain('@media(max-width:1279px){.collaboration-layout{grid-template-columns:1fr;}')
    expect(compact(voiceCss)).toContain('@media(max-width:1279px){.voice-layout{grid-template-columns:1fr;}')
  })

  it('keeps collaboration, voice, and execution scenes free of legacy light surfaces', () => {
    const forbiddenLegacySurfaces = [
      '#edf3f2', '#f0f7f5', '#f9fbfa', '#f3faf8', '#eef8f5',
      'rgba(251,253,252,.94)', 'rgba(255,255,255,.92)',
    ]

    for (const color of forbiddenLegacySurfaces) {
      expect(compact(collaborationCss)).not.toContain(color)
      expect(compact(voiceCss)).not.toContain(color)
      expect(compact(executionRailCss)).not.toContain(color)
    }
  })

  it('centralizes shared workbench primitives outside scene stylesheets', () => {
    const sharedSelectors = [
      '.immersive-workbench.main-content{',
      '.immersive-workbench.hero-row{',
      '.immersive-workbench.hero-rowh2{',
      '.immersive-workbench.hero-rowh2em{',
      '.immersive-workbench.hero-copy{',
      '.immersive-workbench.hero-metrics{',
      '.immersive-workbench.section-heading.compact{',
      '.immersive-workbench.section-headingh2{',
      '.immersive-workbench.count-badge{',
      '.immersive-workbench.live-indicator{',
    ]

    for (const selector of sharedSelectors) {
      expect(compact(workbenchPrimitivesCss)).toContain(selector)
    }
    expect(compact(workbenchPrimitivesCss)).toMatch(/@keyframesworkbench-pulse\b/)
    expect(compact(workbenchPrimitivesCss)).toContain('animation:workbench-pulse')
    const mobilePrimitives = compact(workbenchPrimitivesCss).match(/@media\(max-width:650px\)\{([\s\S]*)\}$/)?.[1]
    expect(mobilePrimitives).toContain('.immersive-workbench.main-content{padding:34px16px;')
    expect(mobilePrimitives).toContain('.immersive-workbench.hero-rowh2{font-size:38px;')

    for (const selector of [
      '.immersive-workbench.main-content{', '.immersive-workbench.hero-row{',
      '.immersive-workbench.hero-rowh2{', '.immersive-workbench.hero-rowh2em{',
      '.immersive-workbench.hero-copy{', '.hero-metrics{', '.section-heading.compact{',
      '.section-headingh2{', '.count-badge{', '.live-indicator{',
    ]) {
      expect(compact(workflowCss)).not.toContain(selector)
      expect(compact(customerCss)).not.toContain(selector)
    }
    expect(compact(workflowCss)).not.toMatch(/@media\(max-width:650px\)\{[^@]*\.immersive-workbench\.main-content\{/)
    expect(compact(workflowCss)).not.toMatch(/@media\(max-width:650px\)\{[^@]*\.immersive-workbench\.hero-rowh2\{/)
    expect(compact(workflowCss)).not.toContain('workflow-pulse')
  })

  it('loads each scene stylesheet from its owning component', () => {
    expect(operationsWorkbench).toMatch(/import\s+['"]\.\.\/styles\/workbench-primitives\.css['"]/)
    expect(operationsWorkbench).toMatch(/import\s+['"]\.\.\/styles\/workflow\.css['"]/)
    expect(operationsWorkbench).not.toMatch(new RegExp(['styles', 'css'].join('\\.')))
    expect(customerConsole).toMatch(/import\s+['"]\.\/customer-service\.css['"]/)
    expect(collaborationPage).toMatch(/import\s+['"]\.\/expert-collaboration\.css['"]/)
    expect(voicePage).toMatch(/import\s+['"]\.\/voice-assistant\.css['"]/)
  })

  it('uses shared dark surfaces for analytics controls and results', () => {
    const compactAnalytics = compact(analyticsCss)

    expect(compactAnalytics).toContain('color:var(--showcase-ivory)')
    expect(compactAnalytics).toContain('background:rgba(8,12,20,0.76)')
    expect(compactAnalytics).toContain('background:linear-gradient(135deg,var(--showcase-cyan),#9befff)')
    expect(compactAnalytics).toContain('background:var(--showcase-cyan-soft)')
    expect(compactAnalytics).toContain('background:rgba(90,25,21,0.3)')
    for (const legacySurface of ['#fff', '#f3faf8', '#eef8f5', '#f0f7f5', '#fff1ef', '#fff5f3']) {
      expect(compactAnalytics).not.toContain(legacySurface)
    }
  })

  it('keeps responsive and accessibility contracts at the shell boundaries', () => {
    const compactShell = compact(shellCss)
    const compactTheme = compact(theme)

    expect(compactShell).toContain('@media(max-width:1279px)')
    expect(compactShell).toContain('@media(max-width:767px)')
    expect(compactShell).toContain('@media(prefers-reduced-motion:reduce)')
    expect(compactTheme).toContain(':focus-visible')
    expect(compactShell).toContain('.immersive-workbench__topbar{grid-template-columns:1fr;padding:14px12px;}')
    expect(compactShell).toMatch(/\.immersive-workbench__nav\{[^}]*width:100%;[^}]*overflow-x:auto;[^}]*white-space:nowrap;[^}]*scrollbar-width:thin;/)
    expect(compactShell).toContain('.immersive-workbench.hero-metrics{display:grid;grid-template-columns:1fr;}')
    expect(compactShell).toContain('.immersive-workbench__rail>summary{display:list-item;color:var(--showcase-cyan);cursor:pointer;}')
  })

  it('keeps the stacked execution rail visible throughout the tablet range', () => {
    const compactShell = compact(shellCss)

    expect(compactShell).toContain('@media(min-width:768px)and(max-width:1279px){.immersive-workbench__rail>summary{display:none;}.immersive-workbench__rail:not([open])>.immersive-workbench__rail-content{display:block;}')
  })

  it('stacks workflow, customer, and analytics scene grids across the tablet range', () => {
    const compactWorkflow = compact(workflowCss)
    const compactCustomer = compact(customerCss)
    const compactAnalytics = compact(analyticsCss)

    expect(compactWorkflow).toContain('@media(max-width:1279px){.dashboard-grid,.lower-grid{grid-template-columns:1fr;}')
    expect(compactCustomer).toContain('@media(max-width:1279px){.customer-console{grid-template-columns:1fr;}.customer-sidebar{display:grid;grid-template-columns:1fr;')
    expect(compactCustomer).toContain('.service-metrics{grid-template-columns:1fr;margin:0;}')
    expect(compactAnalytics).toContain('@media(max-width:1279px){.question-row{grid-template-columns:1fr;}')
  })
})
