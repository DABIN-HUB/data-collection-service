# Admin Console Optimization Plan

## 1. Scope

This document breaks the admin console optimization work into three layers:

- `P0` mandatory usability fixes
- `P1` experience enhancements
- `P2` architecture upgrade

Current frontend scope:

- `src/main/resources/static/admin/index.html`
- `src/main/resources/static/admin/app.js`
- `src/main/resources/static/admin/local-point-editor.js`
- `src/main/resources/static/admin/point-config-editor.js`
- `src/main/resources/static/admin/styles.css`
- `src/main/resources/static/admin/admin-redesign.css`

## 2. Current Problems

The current console is already functional, but the interaction quality is held back by several structural issues:

- Too many responsibilities are packed into one page: device list, realtime points, point config, protocol config, local temporary device modeling, control, shadow, and monitoring all compete for attention.
- Some controls exist visually but have no real behavior wired in yet, which lowers trust in the console.
- Several panels are still placeholders, but they occupy primary workspace positions.
- Realtime interaction is rough: fixed polling, no request cancellation, no visibility-aware refresh strategy, and search triggers repeated reloads.
- The page relies on global state plus large `innerHTML` rendering, and extension scripts patch core behavior by overriding global functions.
- Layout uses many fixed heights and nested scroll areas, which makes the page feel cramped and increases cognitive load.

## 3. Goals

- Make the current console usable for day-to-day operations before pursuing larger redesigns.
- Reduce false affordances and dead ends.
- Keep protocol schema-driven forms and existing backend APIs reusable.
- Separate quick wins from medium-term interaction work and long-term frontend architecture work.

## 4. Delivery Principles

- `P0` should stay within the current static admin page model unless a change is impossible without backend support.
- `P1` can add moderate frontend structure and limited backend endpoints where the user-facing value is clear.
- `P2` is the point where the frontend should stop being maintained as a large vanilla-script single page and move to a proper application structure.
- New UX should be task-oriented, not feature-dump oriented.

## 5. P0 Mandatory

### 5.1 Objective

Bring the existing console from "can use" to "usable and trustworthy" without changing its technical form too aggressively.

### 5.2 Work Items

- [ ] Remove or hide dead controls that currently have no behavior.
  - Examples: site selector, collapse menu button, notification/help icon buttons, favorites tab, device filter button.
- [ ] Either implement device search or remove the input until it is real.
  - The current device search field should not remain decorative.
- [ ] Reduce main workspace overload.
  - Keep the center area focused on one primary job: selected device realtime workbench.
  - Move placeholder modules out of the primary path or mark them clearly as unavailable.
- [ ] Normalize loading, empty, error, and success states across all major panels.
  - Device list
  - Realtime points
  - Point detail editor
  - Protocol connection form
  - Shadow panel
  - Monitor panel
- [ ] Fix realtime interaction mechanics.
  - Add debounce for search input.
  - Prefer local filtering after a realtime payload is loaded.
  - Prevent overlapping realtime requests.
  - Pause auto-refresh when the page is hidden or when the selected device changes mid-request.
- [ ] Reduce layout pressure from fixed heights and nested scroll traps.
  - Revisit hardcoded heights such as `35rem`, `35.4rem`, `46.5rem`, `640px`, `920px`, and similar caps.
  - Ensure the main workbench can breathe on 1440p and smaller laptop screens.
- [ ] Strengthen config editing safety.
  - Keep dirty-state indicators consistent.
  - Warn before destructive whole-list point saves where the backend uses full list replacement.
  - Preserve selected device and selected point across refreshes where possible.
- [ ] Clean up the local temporary device editor flow.
  - Keep the feature, but reduce accidental complexity.
  - Make advanced JSON clearly secondary to form-based editing.
- [ ] Standardize action labeling and button hierarchy.
  - Primary action should be obvious in each panel.
  - Secondary operations should not visually compete with the main task.

### 5.3 Acceptance Criteria

- Operators can finish the main loop of `select device -> inspect realtime points -> inspect/edit point config -> save -> verify` without getting lost.
- No obvious visible control is non-functional.
- Realtime search does not spam requests on every keystroke.
- The page remains readable on common desktop widths without fighting multiple nested scroll regions.
- Placeholder modules are no longer mistaken for available production features.

### 5.4 Suggested Output Form

This phase can be completed in the existing static page:

- `index.html`
- `app.js`
- `local-point-editor.js`
- `point-config-editor.js`
- `styles.css`
- `admin-redesign.css`

## 6. P1 Experience Enhancements

### 6.1 Objective

After the current page becomes reliable, improve operator efficiency and reduce context switching.

### 6.2 Work Items

- [ ] Reorganize the console into clearer task areas.
  - `Overview`
  - `Device Workbench`
  - `Configuration`
  - `Diagnostics`
- [ ] Turn JSON-first panels into structured workflows.
  - Control panel: structured write form plus advanced raw payload area.
  - Shadow panel: reported / desired / delta split view instead of a single raw JSON emphasis.
- [ ] Improve device list ergonomics.
  - Status grouping
  - Local temporary device badges
  - Protocol-based filtering
  - Fast access to abnormal devices
- [ ] Improve point inspection ergonomics.
  - Better metadata hierarchy
  - Inline quality badges
  - Change highlights after refresh
  - Faster navigation among points
- [ ] Introduce read-only history and trend views where backend support already exists.
  - Use `/api/data/history/...` when available.
  - Show clear disabled state when TDengine history is unavailable.
- [ ] Replace placeholder alarm/log areas with gated real content when backend APIs exist.
  - If APIs are not ready, feature-gate them explicitly instead of showing fake panels.
- [ ] Improve monitor and diagnosis readability.
  - Promote the most actionable metrics.
  - Keep raw JSON as a secondary detail view, not the main output.
- [ ] Add visibility into long-running or risky operations.
  - Config sync in progress
  - Device reload in progress
  - Save success/failure summaries
- [ ] Upgrade realtime delivery if backend support is introduced.
  - Prefer SSE or WebSocket backed by Redis Stream instead of pure polling.

### 6.3 Backend Dependencies

The following are the main dependencies that may be needed during `P1`:

- History query completeness
- Telemetry stream browser-facing endpoint
- Alarm/log query endpoints
- Better report-related read APIs if reporting views are included

### 6.4 Acceptance Criteria

- Operators can complete common workflows with fewer panel jumps and less JSON hand-editing.
- Monitoring and diagnosis views expose actionable information before raw payloads.
- Feature availability is explicit: available, disabled, or not yet implemented.
- Realtime refresh strategy is more efficient and more understandable than fixed polling alone.

## 7. P2 Architecture Upgrade

### 7.1 Objective

Move the admin console from an increasingly fragile static page into a maintainable frontend application.

### 7.2 Upgrade Direction

- [ ] Introduce a dedicated frontend project under `frontend/`.
- [ ] Use `Vite + React + TypeScript` as the baseline stack.
- [ ] Use `TanStack Query` for request lifecycle and caching.
- [ ] Use `Zod` for request/response and schema validation.
- [ ] Keep build output published back into `src/main/resources/static/admin`.

### 7.3 Architecture Work Items

- [ ] Replace global mutable page state with scoped module state.
- [ ] Replace function overriding and patch-style extension scripts with explicit composition.
- [ ] Split the UI into stable modules.
  - Layout shell
  - Device explorer
  - Realtime workbench
  - Point configuration editor
  - Protocol configuration editor
  - Diagnostics center
  - Local temporary device wizard
- [ ] Build a shared API layer.
  - Token injection
  - Error normalization
  - Polling / streaming strategy
  - Retry and cancellation policy
- [ ] Build a shared schema-driven form renderer.
  - Reuse existing protocol schema APIs
  - Standardize field help, required state, defaults, and validation
- [ ] Introduce a design token layer and component rules.
  - Spacing
  - Typography
  - Colors
  - Status semantics
  - Density modes if needed
- [ ] Add frontend test coverage for critical workflows.
  - Device selection
  - Realtime refresh
  - Point config editing
  - Local temporary device save
  - Protocol connection save

### 7.4 Acceptance Criteria

- The console can grow without relying on global script patching.
- Realtime, config, and diagnostics modules are independently maintainable.
- Frontend behavior becomes easier to test and refactor safely.
- New features no longer require expanding one large script file.

## 8. Recommended Execution Order

1. Finish `P0` first and keep it grounded in the current static page.
2. Start `P1` only after the main operator workflow is stable.
3. Start `P2` once the team agrees the console will keep expanding and deserves a real frontend app structure.

## 9. Immediate Recommendation

The best next move is:

1. Treat `P0` as the current sprint target.
2. Do not add more major panels to the existing page before `P0` cleanup is done.
3. Use `P1` to improve workflows only where backend capability is already real or can be added with low risk.
4. Plan `P2` early, even if implementation starts later, because the current global-script pattern is already close to its maintenance ceiling.
