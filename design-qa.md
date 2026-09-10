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
- Actual browser captures: `docs/evidence/issue-69/overview-1440x900.png` and `docs/evidence/issue-69/overview-1920x1080.png`, both regenerated against the runtime built from UI commit `aeb2f43637d5f87809864e0976e8a0e23f5eb5f6`.
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
- Customer-visible statuses are mapped to Chinese. Unknown values render `状态未知`; partial and unavailable states remain explicit. No raw Issue number, `OPEN`, or `REDACTED:` metadata is visible in the normal browser state.
- “今日重点关注” keeps its deterministic-rule and no-model disclosure in a collapsed `数据依据` detail, which was expanded and visually checked in the browser.
- The customer surface had no unnamed buttons, duplicate IDs, or browser console warnings/errors.
- API failures remain explicit and do not fall back to a successful fixture; missing energy buckets stay null and ECharts keeps line breaks.

## Acceptance closeout regression

- Focused customer suite: `2` files / `27` tests passed, including status localization, unknown fallback, unavailable navigation, responsive WebP sources with PNG fallback, no-data, partial data, independent failure/retry, and internal-workbench handoff.
- Full unit suite: `44` files / `423` tests passed.
- `npm.cmd run typecheck`: passed.
- `npm.cmd run build`: passed; only the existing Vite chunk-size advisory remains.
- Normal browser state: passed against the running Compose analytics stack and deterministic PostgreSQL demo facts (`3,384 kWh`, `3` buildings, `4` unhandled alerts at capture time). Demo/simulated-data markers remained visible.
- Internal workbench: entering from the customer header and returning to the customer surface both passed in the browser.
- Approved-reference comparison at 1440 × 900 and 1920 × 1080: passed without page redesign; the 1366 × 768 viewport remained free of horizontal document overflow.
- In the final browser capture environment, both approved viewport sizes selected `campus-banner-2172.webp`, `eco-operations-480.webp`, and `park-aerial-daylight-1200.webp` (about 291 KB total source bytes), instead of transferring the three 5.9 MB source PNGs. The width candidates allow lower-density or narrower clients to select smaller variants; PNG files remain only as browser compatibility fallbacks.

## Iteration history

1. First live run showed the anomaly data but rejected the energy request with HTTP 400.
2. Root cause: the overview window carries minute/second precision while the governed hourly time-series API requires exact hour boundaries.
3. The customer adapter now clamps the requested last-24-hour range to valid hour boundaries; a focused unit test covers the unaligned source window.
4. The rebuilt live page returned 3,348 kWh from the current demo facts and rendered the trend/distribution charts. Final 1440 × 900 and 1920 × 1080 captures were then taken.
5. PR review found a stale-refresh race and two derived-state inconsistencies. The final pass now invalidates old evidence at refresh start, stops obsolete continuations after the energy await, derives attention badges and map warnings from each building's actual signals, and regenerates both browser captures.
6. Follow-up review found cross-domain blocking and incomplete filtering/aggregation. Overview/energy, metrics and work items now settle independently; terminal work items are removed before the four-item display limit; latest events combine alert, device and energy evidence by timestamp; and zero energy deviation no longer produces a deviation claim. The focused component suite covers all four cases.
7. Final-head review found that the anomaly building list is not a park inventory. Energy now queries the complete current B1/B2/B3 park catalog even with no anomalies, old energy is invalidated before a refreshed query, and actionable attention requests only the OPEN alert slice.
8. A further concurrency and evidence pass starts building evidence independently of energy, distinguishes unavailable/partial evidence from a confirmed empty list, and keys repeated meter observations by meter plus measurement time.
9. Final refresh QA invalidates the prior overview while revalidation is pending, keeps partial-evidence notices visible alongside any available rows, and removes the inherited mobile navigation minimum width so narrow screens scroll inside the navigation instead of widening the document.
10. The final data-state pass exposes HTTP-200 energy responses with `UNAVAILABLE` status instead of treating them as empty observations, and preserves an explicitly selected catalog building across workbench reactivation even when it has no anomaly row.
11. The next review pass disables map selection while the overview is revalidating so a pending response cannot overwrite a newer user choice, and treats absent catalog rows as normal only when every anomaly domain completed with `OK`.
12. The customer-demo closeout localizes raw status and redaction metadata, removes delivery Issue numbers from customer copy, turns future navigation into explicit non-interactive “未开放” entries, moves the rule/no-model statement into expandable data-basis disclosure, and regenerates both approved browser capture sizes from commit `8b361b645f9b5cfd32dc94237f0c881cbc352daa`.
13. Final-head review identified 5.9 MB of eager source PNG transfers. The page now uses responsive WebP `srcset` variants with PNG fallbacks; actual browser selection at both capture sizes was verified, and the screenshots were regenerated against `2e8c7227cec35d158606dd38ef6f3a740ce1420d` with no visual drift.
14. The next final-head review found that energy appeared empty while its required overview window was still pending. Energy now stays in an explicit loading state until the window settles; an unavailable overview supplies an explicit energy-unavailable reason. The slow-overview and failure regressions assert both states, and the final browser captures were regenerated against `aeb2f43637d5f87809864e0976e8a0e23f5eb5f6`.

---

# Issue #71 Design QA

Result: **PASSED**

## Source and comparison

- Approved reference: `03-events-workorders.png`, 1672×941, SHA-256 `B1B77357C426D4E371BB74FC23FB24583DBAA72658DF89A9D74CADE9DA9835A3`.
- Implementation comparison: `docs/evidence/issue-71/05-design-qa-1672x941.png`, captured in the same 1672×941 CSS viewport.
- The approved source and implementation capture were opened together in one comparison input after the final layout change.

The implementation preserves the reference hierarchy: shared white customer header, wide campus banner, four KPI cards, left event queue, central event/progress area, split recommendation/history cards, and right work-order/participant/action rail. The first pass stacked recommendations and history, making the page visibly taller than the reference. The final pass places them side by side like the approved design; document height is 963px in a 941px viewport, with the remaining 22px attributable to the retained shared footer. There is no overlap or horizontal overflow.

## Intentional capability-driven differences

- KPI counts come from current evidence and collaboration APIs. Reference counts, trends and average duration were not copied; unavailable average duration reads “未提供”.
- The reference's search, weather and live date remain absent because the unique existing customer shell has no reliable source for them.
- Only the one verified same-event row is shown. Other reference work orders are not fabricated.
- The reference's named assignee, department, three-party collaboration, status donut, follow-up and report actions are omitted or shown as unavailable unless the current backend supplies them.
- The page adds a compact same-alert verification notice because identity equality is a product safety gate, not decorative copy.

## Interaction and accessibility

- The live path `overview → analysis suggestions → same event → manual confirmation → WO-0001 / PENDING_EXECUTION` passed in the browser.
- The confirmation modal receives focus on its primary action, supports Escape/cancel without creating a work order, and restores focus to the trigger.
- Native buttons, semantic headings, labeled navigation, status/alert roles, text-plus-icon states and `:focus-visible` are retained.
- At 1366×768 the action remains visible and enabled; `scrollWidth=1351` for `innerWidth=1366`.
- Browser console: no warnings or errors during the continuous flow.

## Final checks

- 1328 backend tests passed; 3 existing conditional tests skipped.
- 475 frontend tests passed across 48 files.
- TypeScript/Vue typecheck and production build passed.
- Existing Vite chunk-size advisory remains a separate performance concern and was not hidden or expanded into this slice.
