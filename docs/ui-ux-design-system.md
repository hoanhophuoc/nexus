# Nexus UI/UX Design System

> Status: **Project design contract — active for all new and changed desktop UI**  
> Scope: `desktop-user`, `desktop-admin`, and reusable UI in `desktop-shared`  
> Runtime baseline: JDK 27, JavaFX 27.0.1, FXML, JavaFX CSS, Gradle  
> Owner: Nexus maintainers  
> Last reviewed: 2026-11-24

This document is the canonical product-specific visual and interaction contract for Nexus. It is deliberately native-desktop-first. It is not a web design system translated into JavaFX. When this document and a generic design skill disagree, this document and verified JavaFX 27 behavior take precedence.

### Working art direction: Tactile Privacy & Precision Craft

Use **Tactile Privacy & Precision Craft** as a working creative brief, not as a rigid theme preset or a security claim. It combines human, calm, content-first communication in `desktop-user` with precise, high-signal operational workflows in `desktop-admin`. Learn from the interaction quality and restraint of products such as Linear, Raycast and Signal, but do not clone their layouts, assets, iconography or brand identities. The design should feel considered and tactile through spacing, typography, feedback and subtle movement—not through glass effects, excessive shadows or visual noise. Revisit this direction when prototypes and user feedback provide better evidence.

Related documents:
- [UI/UX tooling decisions](ui-ux-tooling.md)
- [Repository architecture](architecture.md)
- [Development and testing](development.md)
- [Agent skill](../.agents/skills/nexus-ui-ux/SKILL.md)

## 1. Product experience and design intent

Nexus is a real-time messaging product with separate user and administrator desktop applications. The visual system should make the product feel:

1. **Trustworthy:** security, identity, moderation and system status are communicated accurately; never use decorative confidence to imply guarantees that the implementation does not provide.
2. **Fast and responsive:** frequent actions are easy to reach; interaction feedback is immediate; network work never freezes the interface.
3. **Calm and focused:** messages and operational data have visual priority; decoration does not compete with the task.
4. **Distinctive, not derivative:** borrow proven interaction conventions, but do not clone Slack, Discord, Signal, Teams, Linear or another product's brand or exact layout.
5. **Consistent:** shared controls, typography, spacing, surfaces, status semantics, keyboard behavior and theme rules work the same in both desktop applications.
6. **Accessible and internationalized:** full keyboard operation, readable states, meaningful names, scalable layouts and complete English/Vietnamese strings are product requirements, not finishing touches.

### Surface modes

| Application surface | User outcome | Design mode | Priority |
| --- | --- | --- | --- |
| User chat workspace | Find a person/conversation and exchange messages confidently | Operate | Conversation clarity, fast navigation, readable timeline, composer, presence, connection and encryption state |
| User sign-in, registration, recovery | Enter the product safely and recover from errors | Operate | Clear labels, validation, focus, reassurance without false security claims |
| Group settings and membership | Understand membership and make deliberate changes | Operate | Membership clarity, role/state labels, explicit destructive confirmation |
| Admin operations dashboard | Understand system health and act on operational signals | Operate | Information density, hierarchy, filters, timestamps, auditability and safe actions |
| Admin moderation / reports | Review evidence and make defensible decisions | Operate | Evidence first, contextual actions, confirmation, reversible flows where possible |

Do not use the same density for every surface. The user chat client should feel personal and conversational; the admin client should feel precise and information-rich. They share visual foundations, not necessarily identical layouts.

## 2. Architecture and ownership

### Module responsibilities

- **`desktop-shared`:** owns shared theme infrastructure, semantic tokens, typography registration, common icons/helpers, standard buttons and fields where a reusable component is justified, loading/empty/error states, confirmation dialogs, motion helpers and accessibility helpers.
- **`desktop-user`:** owns user-facing screen composition, chat-specific components, user workflows, navigation and user-specific copy.
- **`desktop-admin`:** owns administrator screen composition, data-dense controls, moderation workflows, audit views and admin-specific copy.
- **`server`, `client-sdk`, `api`:** are not visual design modules. Do not add UI libraries or design-only code to them.

A component belongs in `desktop-shared` only when at least two current or clearly planned desktop surfaces need the same behavior and visual contract. Do not create a generic abstraction for a single use merely to appear reusable.

### Existing implementation to preserve and evolve

At the time of writing, the repository already has:
- `Theme` and `ThemeManager` in `desktop-shared`, with SYSTEM/LIGHT/DARK selection and persisted preference.
- Shared semantic CSS variables and component classes in `desktop-shared/.../ui/theme.css`.
- Bundled Nexus font resources registered by `ThemeManager`.
- Shared helpers in `UiComponents`, and a themed `ConfirmDialog`.
- App-specific stylesheets and FXML views.
- JavaFX FX tests, rendered-control contrast tests and visual golden screenshot tests in both desktop applications.

Do not replace these facilities wholesale. Inspect and extend them. Maintain one application-level theme preference and one authoritative Nexus token layer. AtlantaFX 3.0.0 also ships its own `atlantafx.base.theme.ThemeManager`. During the proof of concept, explicitly decide whether Nexus `ThemeManager` remains the public owner of persisted preference, system-scheme resolution and scene lifecycle while delegating theme application to AtlantaFX, or whether AtlantaFX's manager is adopted and Nexus preference handling is integrated into it. There must be exactly one effective source of truth—never two managers that can independently change the active theme. Evaluate AtlantaFX's theme as the user-agent stylesheet and Nexus `theme.css` as the application stylesheet; verify the cascade, popups and dialogs in the actual app.

## 3. Visual language

### 3.1 Principles

- Establish a clear hierarchy with typography, spacing, alignment and restrained surfaces before adding borders, shadows or gradients.
- Prefer a few meaningful surface levels over stacks of nested cards.
- Use color to communicate meaning and focus, not as decoration.
- Reserve the strongest accent for primary actions, selected navigation and important interactive states.
- Use neutral surfaces for message history and operational data. Keep messages readable over long sessions.
- Use rounded corners consistently and moderately. Avoid making every control a pill.
- Avoid generic AI-dashboard patterns: repeated cards for everything, arbitrary purple/blue gradients, meaningless glow, decorative icon tiles above every heading, excess empty space, emoji used as interface icons, and shadows on every control.
- Do not add texture, glassmorphism, blur, 3D or shader effects unless a concrete user outcome justifies the cost and the effect performs well on supported hardware.

### 3.2 Color tokens

The existing stylesheet uses JavaFX looked-up colors such as `-color-bg`, `-color-surface`, `-color-text`, `-color-accent`, `-color-danger`, `-color-success`, `-color-warning` and message bubble tokens. Keep semantic tokens as the interface between design and components.

Rules:
- Screen styles must consume semantic tokens; do not scatter literal hex/RGB colors through per-screen selectors.
- Maintain light and dark definitions for every token used by a component.
- Add tokens by semantic purpose (surface, content, border, focus, status, message role), not by a component's temporary appearance.
- Define status colors for success, warning, danger and neutral/information. Do not use color alone: pair important status with text, icon, accessible text or another non-color cue.
- Verify actual rendered foreground/background pairs for labels, prompts, disabled controls, selected/hovered rows, dropdowns, popovers, date pickers, tooltips and dialogs in both themes.
- Target WCAG AA contrast: 4.5:1 for normal text and 3:1 for large text and relevant non-text visual indicators. The actual rendered skin must pass; token-only arithmetic is insufficient.
- Disabled state must remain legible. Avoid reducing opacity to a degree that makes important information unreadable.
- Preserve the existing `-color-*` semantic token vocabulary unless the project approves a deliberate migration. Do not introduce a parallel `-nx-*` token system just to rename the same concepts. Add aliases only when a proven integration need requires them.
- A proposed palette is not accepted merely because it looks modern. As a concrete example, the draft pairs `#64748B` on `#0B0E14` (4.06:1), white on `#3B82F6` (3.68:1), and white on `#10B981` (2.54:1) do not reach 4.5:1 for normal text. Recalculate actual foreground/background pairs and verify the rendered controls before adopting a palette.

### 3.3 Typography

- Continue using the bundled Nexus font assets unless an intentional, documented typography change is approved. The current golden test asserts the registered JavaFX family name `Nexus Sans`; do not assume the resource basename `nexusSans` is the CSS family name.
- Verify the actual internal font family name and fallback behavior; a resource filename is not proof of its font family.
- Use the platform monospace family for cryptographic fingerprints, key IDs and audit hashes only where fixed-width alignment improves comparison. Do not add JetBrains Mono or another font package without checking its license, embedding terms and jpackage behavior. Timestamps and normal metadata should remain in the primary UI font unless a specific data-display need proves otherwise.
- Do not copy browser CSS typography syntax such as `letter-spacing: -0.02em` into JavaFX CSS. JavaFX has its own CSS property set; `-fx-line-spacing` is documented for `Text` and `TextFlow`, not as a universal property for every `Label` or control. Verify every proposed style property against the JavaFX 27 CSS reference.
- Define a small type scale with clear roles: screen title, section title, body, supporting text, metadata, labels and numeric/data values.
- The baseline 13px currently in `theme.css` is a project implementation detail, not a requirement to use 13px everywhere. Choose size by reading distance, density and platform rendering; avoid tiny body text.
- Use line wrapping deliberately for names, message content, helper text, errors and Vietnamese translations. Do not truncate critical content without a way to inspect it.
- Use tabular numerals where it improves scanning of counts, timestamps or metrics.
- Never rely on color or font weight alone to express security, moderation or connection status.

### 3.4 Spacing, dimensions and shape

Use a consistent spacing scale derived from 4px increments. Preferred steps are 4, 8, 12, 16, 20, 24, 32, 40 and 48. This is a scale, not a command to add spacing everywhere.

- Use shared CSS variables or documented constants where a repeated dimension has semantic meaning.
- Keep alignment and spacing consistent within a screen and across sibling screens.
- Use JavaFX layout constraints, grow priorities, min/pref/max sizes and layout panes. Avoid absolute coordinates for primary layouts.
- Preserve sensible resizing behavior. Test the actual minimum supported window size, normal working size and a larger window; derive minimums from usable content rather than arbitrary marketing breakpoints.
- Account for platform scaling and high-DPI rendering. Never assume CSS pixels map one-to-one to physical pixels.

### 3.5 Icons and imagery

- Use a single coherent icon family for the primary interface: Feather icons from Ikonli 12.4.0, drawn through `com.nexus.desktop.shared.ui.Icons` and styled by the `nx-icon` class (see [tooling decisions](ui-ux-tooling.md)).
- Do not use emoji, Unicode symbols or arbitrary text glyphs as action icons in production UI. A theme toggle, a message overflow menu or any other action is a `FontIcon`, never a character in FXML or in a resource bundle.
- Give every icon-only action a meaningful accessible name, tooltip and adequate hit target.
- Use consistent icon sizes and optical alignment. A glyph must not shift layout when the font loads.
- Use imagery only when it adds product meaning (for example profile avatars). Do not use stock decoration to fill empty space in a messaging client or operations console.
- Include image fallback behavior, loading placeholders and predictable clipping for avatars.

## 4. Native JavaFX interaction rules

### 4.1 FXML, controllers and CSS

- Keep complex, stable view structure in FXML; keep behavior and view coordination in focused controllers.
- Keep domain rules, networking, persistence, crypto operations and orchestration out of controllers.
- Use descriptive `fx:id` values and explicit controller wiring. Do not use selector lookups as normal dependency wiring.
- Use semantic `styleClass` values and shared CSS. Avoid long inline `Node.setStyle(...)` strings.
- Load FXML and stylesheets with fail-fast error handling and useful diagnostic context. Never silently show a blank screen after a loading error.
- Keep FXML, CSS, fonts and images under the package-aligned resource paths required by the repository.
- Disable an action while the permission or target it needs is still loading, instead of accepting the click and then refusing it silently. A button that looks available has to be one whose action the controller will carry out; `GroupSettingsController` keeps rename and the member actions disabled until the member list says whether the signed-in user is an admin.

### 4.2 Native behavior and keyboard

- Design for a desktop keyboard and pointer first. Touch target guidance from mobile/web skill libraries is not a substitute for native desktop usability.
- Every primary workflow must be operable without a mouse.
- Preserve visible focus indication. Never remove focus rings without providing an equally visible focus treatment.
- Define and document shortcuts only where they are discoverable, non-conflicting and consistent. Handle text-input and IME composition correctly; Enter in a text field must not accidentally submit while an input method is composing text.
- Use correct default/cancel buttons, Escape behavior, focus order and focus restoration after dialogs.
- Use native window behavior and operating-system conventions where practical. Do not remove native decorations or replace window chrome just to look fashionable.
- Provide tooltips for icon-only controls, but do not make hover the only way to discover an essential action.

### 4.3 Forms and validation

- Every field has a persistent visible label associated with the control. Placeholder text is supplementary, never the only label.
- State required/optional fields clearly and validate both client-side and server-side according to the existing contract.
- Put actionable validation feedback near the affected field; preserve user input after recoverable errors.
- Distinguish validation errors, authentication errors, network errors, rate limits and server failures.
- Never expose passwords, access tokens, encryption keys, private message content or sensitive diagnostic details through labels, logs, tooltips or error copy.
- Confirm destructive actions with clear consequence-focused copy. Provide a safe cancel path and sensible initial focus.
- Do not create unnecessary dialogs for routine reversible actions.

### 4.4 Real-time chat experience

- The message timeline and conversation list are the primary visual hierarchy. Do not let decorative dashboard-style cards compete with them.
- Use virtualized JavaFX controls for potentially large conversation/message collections; do not create an unbounded scene graph of nodes for message history. The chat timeline is a `ListView<TimelineItem>` (`Day`, `Bubble`, `Pending`) whose cell factory builds row nodes on demand, so the scene graph stays bounded however long the history is. `ScrollPane` over a `VBox` was measured and rejected: at 1,000 messages it held 13,021 row nodes, appended at p95 1,969 ms and rebuilt at p95 2,111 ms, and the 5,000-message run exhausted the test JVM.
- Prefer public JavaFX APIs such as `ListView` with a carefully designed `cellFactory` when they satisfy variable message heights, grouping, selection, accessibility and scroll anchoring. Do not import internal implementation classes such as `com.sun.javafx.scene.control.skin.VirtualFlow`; internal APIs are not a stable extension point.
- Preserve scroll position when older messages load. Auto-scroll only when the user is already at the bottom or explicitly requests it.
- The implemented timeline contract: an update that only appends to the end of the loaded history adds rows without touching the ones above them; an update that rewrites or reorders the history rebuilds it and restores the message that sat at the top of the viewport, by message id — the anchor is an id and not a pixel offset on purpose, because `ListView` addresses content with `scrollTo(int)` and the pixels between rows belong to the internal `VirtualFlow`. The anchored row is therefore put back flush with the top of the viewport: the reader keeps the message they were reading, and a tall row that had scrolled partly above the edge has that part brought back into view. `ChatTimelineScrollFxTest` asserts this with a long wrapped row, and the scrollbar's own thumb drag and track click are driven as real mouse input to prove that neither an arrival after a drag nor one after a track click pulls the reader back; the newest row is followed only when the reader was already there (read from the list's scroll position, not from a recycled cell's bounds) or when the row is one the user just sent, and a follow the shell already started is not cancelled by an arrival that lands while the move to the newest row is still being re-applied — the scroll bar read mid-move is not the reader leaving the bottom. A move re-applies itself after measuring the rows it just gained rather than on a wall-clock budget, so a loaded machine lengthens it in time without ending it early; the anchor restore of a rebuilt timeline follows the same rule. A revealed search hit is positioned by its row index in the list, with one row of room above it.

**Timeline budget and measured result.** `:desktop-user:fxTest --tests
*TimelineBenchmarkFxTest` writes `desktop-user/build/timeline-benchmark.md` and fails the build when
the 5,000-message budgets are missed. The node budgets are what fails if the timeline goes back to
building one node per message; the timing budgets alone could pass by luck on a fast run. The
pulse budget is set above the measured spread rather than at it: nine runs put the 5,000-message p95
between 58 and 113 ms, so a 100 ms ceiling failed on healthy runs. The fixture carries alternating
senders, long and short bodies, a date separator every 30 messages and three queued rows that stay
pending through the measured phases.

The block below repeats the report and is read back by the same test, so it is verified rather than
hand-copied: the budget sentence must equal the benchmark's budget constants, the table's header
and history sizes must equal the report's, and each budgeted cell must stay within a factor of
three (or 25 ms, whichever is larger) of the run that produced the report, with node counts within
a quarter. Timing cells are compared from 500 messages up: at 100 messages class loading and the
JIT dominate, so the row is reported but its timings are not gated (append p95 has measured between
12 and 74 ms there); its node counts, like every row's, are compared. A table left behind by an older timeline — the `ScrollPane`+`VBox` one had 13,021 row
nodes and appends near 2,000 ms — therefore fails the build. Timings move between runs, most of all
at 100 messages, which absorbs JIT warm-up; refresh the block from
`desktop-user/build/timeline-benchmark.md` only when the timeline itself changed. This machine is
Linux, JDK 27.0.1, JavaFX 27.0.1, Xvfb 1280x1024.

<!-- timeline-benchmark:begin -->
Budget at 5,000 messages: render <= 800 ms, append p95 <= 50 ms, rebuild p95 <= 900 ms, resize p95 <= 150 ms, pulse gap p95 <= 150 ms, worst pulse gap <= 500 ms, message rows <= 60 and scene nodes <= 500.

| messages | render ms | append p95 ms | delivery p95 ms | rebuild p95 ms | resize p95 ms | reveal ms | pulse p95 ms | pulse max ms | row nodes | scene nodes | heap delta MB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 | 178 | 58 | 98 | 28 | 74 | 1 | 94 | 152 | 7 | 304 | 81 |
| 500 | 100 | 18 | 13 | 28 | 65 | 1 | 37 | 86 | 8 | 317 | 69 |
| 1,000 | 75 | 26 | 9 | 32 | 62 | 3 | 62 | 78 | 8 | 314 | 118 |
| 5,000 | 80 | 11 | 25 | 36 | 60 | 1 | 69 | 94 | 7 | 303 | 109 |
<!-- timeline-benchmark:end -->

`delivery p95` is the part before the timeline: the socket round trip plus the encrypted cache's
whole-file persist. It is reported, not budgeted, because it is a data-layer cost; the append
budget measures the timeline's own work from the row entering the model to it being painted.
`pulse p95` and `pulse max` are the gaps between animation pulses recorded across the whole measured
run — a gap is how long the reader kept seeing the previous frame — and they are what a stall shows
up as: a burst of arrivals that each rebuild the list, as ten back-to-back pushes do here, is the
current worst case at 5,000 messages. `heap delta MB` is the used heap with the history in the
timeline minus the used heap before that size was opened (each size after its own collection, so a
row is that size's cost and not the sizes before it). It is reported, not budgeted, because garbage
collection timing makes it too noisy to gate a build on, and it can be negative when a run collects
more than it retains.
- Make pending, sent, failed, retrying and incoming states distinguishable without confusing them with encryption or delivery guarantees.
- Keep composer focus, keyboard send behavior, multiline entry, attachments and error recovery predictable.
- Presence and unread indicators must not rely on color alone.
- Connection interruptions and reconnect states must be visible and actionable without repeatedly interrupting the user.
- Animate new content subtly; never animate an entire long message list on each event.
- For groups, accurately represent the current membership and cryptographic state as the implementation exposes it.

### 4.5 Admin and moderation experience

- Favor scanability and information scent: meaningful page titles, clear sections, searchable/filterable tables, visible result counts and explicit empty states.
- Align numeric columns, timestamps and status values; use stable sort and filter behavior.
- Provide contextual detail before irreversible moderation or account actions.
- Distinguish “no results”, “not loaded”, “loading”, “failed” and “permission denied”.
- Preserve audit context: actor, action, target, time and reason where the backend contract supplies them.
- Avoid decorative charts that do not support an operational decision.
- Never conceal destructive consequences behind vague labels such as “Proceed” or “OK”.
- An identity table that is shown both full width and inside the shell's narrower content area must keep every column readable at the narrow width. The accounts table does this with three rules. A row action is an icon-only button carrying its words as accessible text and tooltip (`Icons.describe`), because two labelled buttons need about 140 px and pushed the action column past the edge in the shell. A column header wraps through `AdminUi.wrapHeader` instead of ending in an ellipsis. A data cell may still shorten with an ellipsis, but the shortened value has to stay readable: every text cell of the admin tables shown in that area (accounts identity, login history and friends, reports and its evidence, groups and their members, registrations, audit) installs a tooltip only while its text is actually wider than the cell, carrying the whole value as both the hover text and the accessible text, wrapped at 420 px and shortened to its opening 200 characters when the value is a paragraph, because wrapping bounds the width but not the height. A run that never offers a space — a long email, a path — gains invisible break opportunities inside the popup, because a token that cannot break would still overflow the width; the accessible text and the copied value stay the untouched value. A pointer is not required to reach any of it: a JavaFX table keeps focus on the control rather than on a cell, so `AdminUi.keyboardReadable` drives the focus itself — the arrows walk the columns that explain themselves, the reached cell is marked with `admin-cell-focus` and shows its own explanation, and the copy shortcut takes that cell's whole value. F2 and the row copy stay as the fallback for the whole row. The reached cell also describes itself to assistive technology — the column's header, the whole value, and that the screen is clipping it — because a screen reader cannot see the `admin-cell-focus` border, and the description is cleared when the cursor leaves the cell or the table.
- A form control must be as wide as its longest value, not its English one. The registration form lets the gender list take the space the date field does not need, because “Không muốn tiết lộ” was ellipsized in the half-width column that “Prefer not to say” fitted.

## 5. Motion and perceived performance

Motion is feedback, not decoration. Use JavaFX-native animation APIs such as `FadeTransition`, `TranslateTransition`, `ScaleTransition`, `Timeline`, `ParallelTransition` and `SequentialTransition` when the effect improves understanding.

Guidelines:
- Prefer short, calm, interruptible transitions. Start with approximately 120–200ms for control feedback and 180–260ms for meaningful view/panel transitions, then tune after observing the real application.
- Use easing that communicates acceleration/deceleration naturally; avoid bounce, elastic overshoot and exaggerated spring movement in routine messaging/admin flows.
- Animate opacity or small translations rather than repeatedly animating layout dimensions. Avoid layout thrashing.
- Do not animate every row, every message, every hover state or every navigation item.
- Avoid perpetual motion. A loading indicator may indicate progress, but decorative infinite loops are not acceptable.
- Respect an application-level reduced-motion preference (and platform preference where reliably available). Reduced motion should remove non-essential transitions while preserving state feedback.
- Never use motion as the only indication of a state change.
- Cancel or complete transitions safely when the view is disposed, navigated away from or receives newer data.
- Test motion while messages arrive quickly, while a user scrolls and while a window is resized.
- If multiple screens need the same motion behavior, centralize it in a small `MotionPresets` helper in `desktop-shared`; do not create an animation framework for one transition. Suggested starting ranges are 110–160ms for micro-feedback and 180–260ms for meaningful panel/view transitions. `Interpolator.SPLINE(0.22, 1.0, 0.36, 1.0)` may be evaluated as an ease-out curve, but timings and easing are hypotheses to review on the real app—not universal rules.
- Provide a central `nexus.ui.animations.enabled=false` switch for deterministic FX tests and a reduced-motion preference for users. When disabled, complete the state change synchronously rather than leaving a transition half-finished. Make transitions cancellable and restore node state on completion, cancellation and disposal.
- **Motion decision (2026-11-18):** the shipped desktop UI currently contains no JavaFX animation — no `Transition`, `Timeline` or animated property in `desktop-shared`, `desktop-user` or `desktop-admin`. Every state change (theme flip, timeline update, badge, status, dialog) is applied synchronously, so there is no motion to reduce and no `MotionPresets` helper or animation switch has been added for a single unused transition. The guidance above stays the contract for the first transition that is introduced; at that point it must ship with the reduced-motion switch and the motion tests, not before.
- **Motion review (2026-11-24):** the completion review asked whether any repeated transition genuinely improves the product. None does. The flows that change most often are the chat timeline (a row appended or a list rebuilt) and admin table loads; both are read by scanning, and delaying either would make the data arrive later than the event that caused it, which is the opposite of the precision the admin client exists for. The only candidate left is a fade on a rebuilt timeline, which would animate a surface the reader may be reading and would have to be excluded in exactly the virtualized case that matters. The decision is therefore to add no motion: no helper, no switch, and no animation property. The `nexus.ui.animations.enabled` switch remains unconfigured because there is nothing to gate; the first real transition still has to arrive with it and with deterministic FX coverage.
- Do not promise 60fps from a preset. Measure frame pacing and input responsiveness on the supported OS/hardware matrix.
- Do not automatically set `Node.cache=true` and `CacheHint.SPEED` for every animation. JavaFX documents that `SPEED` favors cached-bitmap rendering and may reduce visual quality. Use it only for an expensive node after a measured improvement; restore its previous cache hint in every completion/cancellation path. See the [JavaFX 27 CacheHint API](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/CacheHint.html).

## 6. Accessibility and localization

Every new or materially changed screen must be reviewed in both English and Vietnamese and in LIGHT and DARK themes.

Checklist:
- Visible labels, accessible text and meaningful accessible roles for interactive controls.
- Icon-only controls have accessible names and tooltips.
- A clipped value is readable and copyable without a pointer, not only on hover.
- Keyboard focus order is logical; focus remains visible and is restored after dialogs.
- Controls are not operable by color or hover alone.
- Text/background contrast is measured on rendered JavaFX skins in both themes and relevant states.
- Errors and status changes are announced or exposed in a way available to assistive technology; use JavaFX accessible text/help where appropriate and verify on supported platforms.
- Text does not clip or overlap with long Vietnamese strings, long names, timestamps, large counts or scaled display settings.
- Dates, times, numbers and pluralization use locale-aware formatting.
- All user-visible strings belong in the appropriate resource bundles; no new hard-coded screen copy.
- Keyboard shortcuts do not break IME composition or normal text editing.
- Do not claim a screen is fully accessible based only on an automated test or one contrast ratio.

## 7. Security and trust language

Nexus contains security-sensitive user experiences. Design must reflect the actual server/client guarantees.

- The repository explicitly describes group crypto as **MLS-style, not RFC 9420**. Never label it RFC 9420 or imply standards conformance unless the implementation and documentation are deliberately changed and independently verified.
- Do not claim all messaging is “fully end-to-end encrypted” unless that statement is demonstrably true for every affected flow and the product owner approves the wording.
- Never expose plaintext message bodies, credentials, tokens, key material or sensitive account data in screenshots, logs, analytics labels or accessibility text.
- Do not present message delivery, server receipt, local persistence and cryptographic protection as interchangeable statuses.
- Security-related badges and explanations must be precise, understandable and linked to the underlying real state.
- Security copy is not a substitute for actual security tests or protocol review.

## 8. Responsiveness, data and runtime performance

- The JavaFX Application Thread must remain free for layout, input and rendering. Never perform network, disk, database, crypto or expensive parsing synchronously in event handlers.
- Use the existing asynchronous services or JavaFX `Task`/`Service` patterns. Marshal scene-graph changes back to the FX thread.
- Avoid repeated full-scene rebuilds for small data updates. Prefer bindings, observable collections and targeted updates.
- Avoid unbounded message nodes, oversized images and per-row effects that scale poorly.
- Cache or virtualize where justified; measure before adding speculative caching.
- Test on realistic message counts, large conversation lists and repeated reconnects.
- Keep visual effects lightweight; avoid expensive blur and large drop shadows on frequently updated nodes.
- Any new UI dependency must not compromise startup time, memory, package size or native packaging across supported operating systems.

## 9. Component contract

Build shared components around actual repeated product needs. A screen does not need every conceivable state: define only the states that can genuinely occur for that screen/component, but never omit a reachable loading, empty, error, offline, permission, busy or success state. Do not force a CTA into an empty state when there is no meaningful next action; explanatory empty content is acceptable. Skeletons are optional and should be used only when they improve perceived continuity without creating unnecessary animation or visual noise.

Candidate component families:

| Family | Expected variants/states |
| --- | --- |
| Buttons | Primary, secondary, quiet/ghost, danger, icon-only; hover, focused, pressed, disabled, busy |
| Fields | Text, password, search, multiline; idle, focused, invalid, disabled, busy |
| Status | Connection, presence, delivery, moderation, warning/error; icon + text, not color only |
| Feedback | Loading, empty, error, offline, permission denied, success |
| Dialogs | Confirmation, destructive confirmation, information, form dialog; keyboard and focus-safe |
| Navigation | Selected, hover, keyboard focus, unread indicator, disabled |
| Data views | Table, list, timeline; loading/empty/error/selection/sort/filter |
| Chat | Message bubble, composer, conversation row, attachment, date separator, delivery/retry state |

Before adding a shared component:
1. Confirm the same interaction/semantics is required in multiple screens.
2. Define its states, accessibility behavior, sizing and theme behavior.
3. Check whether a JavaFX built-in control or an already-approved library solves the need.
4. Implement in `desktop-shared` only if shared ownership is justified.
5. Add JavaFX tests for behavior, styling, theme variants and accessibility.

Do not build a component gallery or design-system framework that costs more to maintain than the product UI.

## 10. Required design-to-code workflow

For every new screen, significant redesign, or material interaction change:

1. **Inspect:** read this document, the agent skill, relevant FXML/CSS/controllers, adjacent screens, current tokens and existing FX tests. Understand the exact user task and existing behavior.
2. **Frame:** state target user, user outcome, primary action, secondary actions, edge states, data/privacy constraints and non-goals.
3. **Explore:** gather visual references and consider at least two viable layout directions for major new screens. Use Figma or OpenDesign only when a prototype would reduce ambiguity. The product's own design direction wins over an external brand reference.
4. **Specify:** record layout hierarchy, component reuse, tokens, states, keyboard behavior, motion and localization before implementation. Update this document only when the design-system contract itself changes; screen-specific decisions belong near the feature spec/design notes.
5. **Implement:** prefer existing shared components and JavaFX built-ins. Use AtlantaFX/Ikonli/ControlsFX only according to [tooling decisions](ui-ux-tooling.md) and compatibility gates. Keep FXML, controllers and CSS responsibilities separate.
6. **Verify behavior:** add/update FX tests for important interactions, errors, focus, keyboard and state transitions.
7. **Verify appearance:** run visual golden tests; inspect the captured PNGs rather than trusting a passing build. Cover light/dark and English/Vietnamese for the modified surface. Add missing golden coverage for any materially new primary screen/state.
8. **Verify accessibility/performance:** run rendered control contrast checks, review keyboard-only operation, inspect text clipping and confirm no FX-thread blocking.
9. **Review regression:** compare adjacent screens and both apps to ensure the shared system remains coherent and app-specific UX remains appropriate.
10. **Document:** update relevant docs in the same change. Include before/after screenshots in the PR for UI changes, with test commands and results.

### Visual golden baseline policy

The repository already maintains `golden` and `golden-ci` PNG baselines and FX screenshot tests for several user/admin views. Preserve this as a production quality gate.

- Never refresh a golden solely to make a failing test green.
- First inspect whether the visual change is intentional and consistent with this document.
- Generate new goldens only with the relevant explicit update environment variable already defined by the test.
- Review the changed images at the exact committed dimensions and in both themes before accepting them.
- Keep the hosted-CI baseline deterministic. Do not loosen thresholds without evidence and review.
- The comparison allows 0.75% of pixels to differ, with a per-channel delta of 16 — roughly 6,700 pixels of a 1180×760 capture. That absorbs the difference between a developer Linux run and the hosted runner, and is loose enough to miss a small real regression: treat a result near the threshold as a failure under review.
- `golden/` is generated on a developer machine and `golden-ci/` on the hosted Linux runner; several English baselines already differ between the two within that tolerance. A new baseline is committed to both directories from one reviewed capture and confirmed again on the runner.
- Every primary chat surface has both an English and a Vietnamese baseline, so a translated string that clips or reflows is caught by the image rather than only by a bundle assertion.
- The chat states that are not a settled conversation have baselines of their own, in both themes: an empty conversation list (`shell-empty-*`), search results in the sidebar (`shell-search-*`), a message the server refused that stays queued (`shell-pending-*`, with the "saved on this device" status line) and a realtime reconnect that could not re-sync (`shell-reconnect-*`, with the offline status dot). Each state is reached through the real path rather than a label set by the test: the fake server hides the conversation for the empty list, refuses sends for the queued row, and drops the WebSocket and then refuses the conversation list for the reconnect.
- Every admin workspace has a baseline: accounts, reports, analytics, audit, groups, registrations, sign-in and the shell, each in LIGHT and DARK, and each also in Vietnamese in both themes — so a translated toolbar, table header, section title or action label that clips or reflows is caught by an image rather than only by a bundle assertion. Analytics is captured at 1180x1120 instead of the 1180x780 working window because it is the one admin screen taller than the window; a shorter capture would record a scroll position rather than the screen. Its year and date fields are pinned, like the registration range, so a baseline cannot move with the calendar.
- The user sign-in, chat shell and group chat have English and Vietnamese baselines in both themes, and so do the primary form surfaces: account, registration, report and group settings. The chat states listed above (`shell-empty-*`, `shell-search-*`, `shell-pending-*`, `shell-reconnect-*`) and the popups are captured in both themes in English, which is enough because their copy is short, theme-driven status text rather than a translated layout.
- A new baseline is one reviewed capture committed to both `golden/` and `golden-ci/` and confirmed again on the hosted runner. The reconnect baseline's socket drop lives in the test WebSocket server, which now keeps its listener open after a client disconnects so a reconnect reaches a real handshake.
- Protect tests against machine-dependent font rendering, locale, timezone, focus and animation timing.

Current known FX test classes include:
- `desktop-user/.../DesignSystemFxTest.java`
- `desktop-user/.../RenderedControlContrastFxTest.java`
- `desktop-user/.../PopupKeyboardFxTest.java`
- `desktop-user/.../PopupThemeFxTest.java`
- `desktop-user/.../TimelineBenchmarkFxTest.java`
- `desktop-user/.../UserGoldenScreenshotFxTest.java`
- `desktop-admin/.../AdminDesignSystemFxTest.java`
- `desktop-admin/.../AdminKeyboardFxTest.java`
- `desktop-admin/.../RenderedAdminContrastFxTest.java`
- `desktop-admin/.../AdminGoldenScreenshotFxTest.java`

Always inspect the current test tree before relying on this list; it will evolve.

## 11. Pull request acceptance checklist

A UI PR is not complete until all applicable items are true:

- [ ] The change solves a clear user problem; the primary task is obvious.
- [ ] It follows this design contract and preserves product-specific behavior.
- [ ] Shared UI is reused or the decision not to reuse is explained.
- [ ] No arbitrary inline CSS, hard-coded visible strings, emoji action icons or raw screen-level colors were introduced.
- [ ] Light and dark themes are both correct.
- [ ] English and Vietnamese text fit and make sense.
- [ ] Keyboard-only operation, focus visibility and focus restoration work.
- [ ] Icon-only controls have accessible names and tooltips.
- [ ] Important states have loading, empty, error, offline and permission handling as appropriate.
- [ ] Rendered text/control contrast has been checked.
- [ ] Visual golden tests cover the changed screen(s) and have been manually reviewed.
- [ ] No work that may block rendering runs on the FX Application Thread.
- [ ] Motion is purposeful, interruptible, lightweight and reduced-motion-aware.
- [ ] Sensitive content is not leaked and security copy is accurate.
- [ ] FXML/CSS resources resolve in a clean build and native packaging is not broken.
- [ ] Relevant documentation and screenshots are included in the same PR.
- [ ] The relevant FX tests and `./gradlew fullTest` pass, or any environment limitation is clearly reported.

## 12. Change control

This document is a living contract, but it must not drift casually.

- Update it when a visual token, component contract, motion rule, accessibility standard or product design principle changes.
- Record a brief rationale for changes that affect multiple screens.
- Keep tool versions and compatibility findings in [ui-ux-tooling.md](ui-ux-tooling.md), not here.
- Code plus passing tests are the implementation authority. If implementation and documentation diverge, fix the documentation in the same PR.
- Never downgrade a quality gate merely because it slows down one feature.
