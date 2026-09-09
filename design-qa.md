# Evidence Theater Design QA

- Date: 2026-08-30
- Baseline viewport: 1440 × 1024
- Target surface: customer-facing Agent showcase home

## Visual sources

- Selected reference: `docs/design-references/2026-08-30-showcase-option-2.png`
  - Dimensions: 1487 × 1058
  - SHA-256: `81860A68CD2BC91EEA30918052B104EBBBCDB9779B5BC8AE34DDA231C91E635D`
- Generated text-free park asset: `ui/src/assets/showcase/evidence-theater-park.png`
  - Dimensions: 1600 × 983
  - SHA-256: `F89188AAC581CFBCD4E4659AB1F6709865BBDA4B0F20E49F28B87D1EE6864206`
  - Inspection result: no product UI, words, logos, icons, buttons, panels, or watermark are embedded in the raster.

## Runtime states checked

The production UI continued to consume `/api/showcase/scenarios`. A local visual-QA proxy was used only to supply a verified `READY`/`live` catalog state on port 5182; the real backend state on port 5181 was checked separately and correctly rendered the no-ready state. The frontend contains no fixture or manufactured readiness data. Readiness is revalidated when the showcase becomes active and immediately before a run; stale overlapping responses are ignored and transient failures expose a retry action.

| State | Evidence | Result |
| --- | --- | --- |
| Verified collaboration selected | `.superpowers/sdd/2026-08-30-showcase-home-option-2/qa/final-verified-viewport-1440x1024.png` | Passed |
| Reference + implementation, same comparison input | `.superpowers/sdd/2026-08-30-showcase-home-option-2/qa/final-comparison.png` | Passed |
| Real-backend no-ready state | `.superpowers/sdd/2026-08-30-showcase-home-option-2/qa/final-disabled-1440x1024.png` | Passed |
| CTA-routed collaboration workbench | `.superpowers/sdd/2026-08-30-showcase-home-option-2/qa/final-workbench-collaboration-1440x1024.png` | Passed; PNG content area 1425 × 1013 inside the 1440 × 1024 CSS viewport |
| Tablet breakpoint | `.superpowers/sdd/2026-08-30-showcase-home-option-2/qa/final-tablet-1249x1024.png` | Passed; PNG content area 1234 × 1012 inside the 1249 × 1024 CSS viewport |
| Mobile breakpoint | `.superpowers/sdd/2026-08-30-showcase-home-option-2/qa/final-mobile-759x1024.png` | Passed; PNG content area 744 × 1004 inside the 759 × 1024 CSS viewport |

## Comparison history

### Initial capture

- P1: the right task panel overflowed its baseline slot by about 253px and exposed a long scrollbar.
- P1: a second oversized stage title duplicated the product lockup and wrapped incorrectly.
- P1: the evidence ribbon said `实时演绎中` before any run had started.
- P2: the stage was substantially darker than the selected reference and the server business question lacked primary hierarchy.

Resolution: removed the duplicate title, made the server question primary, lightened the stage treatment, neutralized the ribbon to `流程说明`, and compacted the panel without removing server content.

### Round 1

- The panel overflow was reduced to 12px (`clientHeight 817`, `scrollHeight 829`).
- All other P1/P2 findings above were visibly resolved.

Resolution: reduced only the right-panel flex gap from 10px to 6px. `overflow: auto` remains as a long-content safety fallback.

### Final baseline

- Document: `1440 × 1024`, no horizontal or document-level vertical overflow.
- Stage: `979.19 × 839.67`.
- Task panel: `clientHeight 817`, `scrollHeight 817`; no baseline scrollbar.
- Typography: no rendered showcase text below the 14px body minimum.
- Heading count: one `h1`.
- Evidence state: `证据链路 / 流程说明`; no pre-run activity claim.
- Capability ledger: three detailed scenario rows plus one compact fourth entry; no service-provided unavailability reason is dropped.
- Same-input visual comparison retained the selected reference's dominant geometry: immersive park stage, right-side task theater, and full-width evidence ribbon.

## Responsive and interaction QA

- 1249 × 1024: the panel stacks below the stage, panel content fits (`645 = 645`), the evidence ribbon remains readable, no showcase text is below 14px, and horizontal overflow is false.
- 759 × 1024: single-column order is preserved, decorative chain and extra-task label are hidden, the selected question and primary start action remain visible, panel content fits (`899 = 899`), no showcase text is below 14px, and horizontal overflow is false.
- The verified start action is enabled only for `READY && live`, routes to the existing collaboration workbench, hides the showcase surface, and does not invoke a scenario execution API.
- The real-backend no-ready state disables the start action, retains all four unavailable reasons, reports `暂无已验证场景`, and fits the baseline panel (`817 = 817`).
- Surface transitions move keyboard focus to the destination root. Returning to the same requested workbench scenario reapplies its view. Leaving during pre-start catalog validation invalidates the pending start, while hiding the workbench deactivates voice capture, invalidates in-flight session or microphone setup, and isolates any pending capture startup from immediate reentry.
- Native buttons, text-plus-icon status, `aria-live`, `:focus-visible`, and `prefers-reduced-motion` treatments remain present.

## Console and initialization QA

The collaboration route initially exposed a Vue Flow warning because the workflow graph attempted `fit-view-on-init` while its workbench view was hidden. The root fix defers the graph until the workflow view is first visible, then keeps it mounted. A regression test covers the lazy-first-mount/preserve-after-switch behavior.

- Fresh collaboration route console: no warnings or errors.
- Subsequent first workflow-view open: graph visible, no warnings or errors.
- Real-backend no-ready page console: no warnings or errors.

## Follow-up notes

- P3: the source mock includes decorative Agent callout labels and glowing connector arrows. They were intentionally not approximated with CSS/div/SVG art or fabricated capabilities; the generated raster supplies only the environmental connection field.
- P3 engineering: Vite still reports the pre-existing production chunk-size warning above 500kB. It does not block this visual slice, but route-level code splitting should be handled as a separate performance task.

final result: passed

---

# Issue #69 Design QA

Result: **PASSED**

## Evidence

- Approved reference: `smart-park-ai-approved-designs.zip / 01-park-overview.png` (1672 × 941).
- Actual browser captures: `docs/evidence/issue-69/overview-1440x900.png` and `docs/evidence/issue-69/overview-1920x1080.png`.
- Runtime: repository Compose analytics profile with its deterministic PostgreSQL demo facts; the page was opened through the in-app browser at `http://127.0.0.1:15173/`.
- Responsive check: 1366 × 768 reported `scrollWidth=1351`, `innerWidth=1366`, so no horizontal overflow. The workbench entry, first KPI and building markers were visible.

## Comparison

The approved reference and the 1920 × 1080 implementation capture were reviewed together. The implementation preserves the reference hierarchy and spatial rhythm:

- white customer header with active overview navigation;
- wide daylight campus banner;
- four KPI cards plus the green-operation promotion;
- left attention list, central park image with building markers, and right todo/report column;
- bottom energy trend, two distributions, and latest-event list;
- pale blue canvas, white cards, compact radii, restrained shadows, blue/green/coral/violet status accents.

Intentional differences are capability-driven rather than visual drift. Search, weather and live date are omitted because no reliable source exists. Device run rate is replaced by affected buildings because the current API has no total-device denominator. The report and later customer pages are visibly unavailable instead of linking to empty pages. The trend has one observed series rather than fabricated comparison data, and empty todo/service counts remain zero when returned by the running backend.

## Interaction and state checks

- Selecting B2 changed the map selection and latest-event context to `研发大厦 · 同一业务窗口`.
- Entering the internal workbench hid the customer surface; returning restored the customer surface and retained B2 selection.
- The four later navigation items are non-link elements with `aria-disabled=true`.
- The customer surface had no unnamed buttons, duplicate IDs, or browser console warnings/errors.
- API failures remain explicit and do not fall back to a successful fixture; missing energy buckets stay null and ECharts keeps line breaks.

## Iteration history

1. First live run showed the anomaly data but rejected the energy request with HTTP 400.
2. Root cause: the overview window carries minute/second precision while the governed hourly time-series API requires exact hour boundaries.
3. The customer adapter now clamps the requested last-24-hour range to valid hour boundaries; a focused unit test covers the unaligned source window.
4. The rebuilt live page returned 3,348 kWh from the current demo facts and rendered the trend/distribution charts. Final 1440 × 900 and 1920 × 1080 captures were then taken.
5. PR review found a stale-refresh race and two derived-state inconsistencies. The final pass now invalidates old evidence at refresh start, stops obsolete continuations after the energy await, derives attention badges and map warnings from each building's actual signals, and regenerates both browser captures.
