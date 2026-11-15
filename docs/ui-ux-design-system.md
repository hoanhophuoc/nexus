# Nexus UI/UX Design System

> Status: **Project design contract — active for all new and changed desktop UI**  
> Scope: `desktop-user`, `desktop-admin`, and reusable UI in `desktop-shared`  
> Runtime baseline: JDK 27, JavaFX 27.0.1, FXML, JavaFX CSS, Gradle  
> Owner: Nexus maintainers  
> Last reviewed: 2026-11-14

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

- Continue using the bundled Nexus font assets unless an intentional, documented typography change is approved. The current golden test asserts the registered JavaFX family name `Chat System Sans`; do not assume the resource basename `nexusSans` is the CSS family name.
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

- Use a single coherent icon family for the primary interface (Ikonli is the preferred candidate; see [tooling decisions](ui-ux-tooling.md)).
- Do not use emoji, Unicode symbols or arbitrary text glyphs as action icons in production UI.
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
- Do not change application behavior, permissions, API contracts, encryption or moderation policy as a side effect of visual work.

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
- Use virtualized JavaFX controls for potentially large conversation/message collections; do not create an unbounded scene graph of nodes for message history. The current shell uses a `ScrollPane` with a `VBox` message container, so benchmark this path with realistic history sizes before choosing a migration.
- Prefer public JavaFX APIs such as `ListView` with a carefully designed `cellFactory` when they satisfy variable message heights, grouping, selection, accessibility and scroll anchoring. Do not import internal implementation classes such as `com.sun.javafx.scene.control.skin.VirtualFlow`; internal APIs are not a stable extension point.
- Preserve scroll position when older messages load. Auto-scroll only when the user is already at the bottom or explicitly requests it.
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
- Do not promise 60fps from a preset. Measure frame pacing and input responsiveness on the supported OS/hardware matrix.
- Do not automatically set `Node.cache=true` and `CacheHint.SPEED` for every animation. JavaFX documents that `SPEED` favors cached-bitmap rendering and may reduce visual quality. Use it only for an expensive node after a measured improvement; restore its previous cache hint in every completion/cancellation path. See the [JavaFX 27 CacheHint API](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/CacheHint.html).

## 6. Accessibility and localization

Every new or materially changed screen must be reviewed in both English and Vietnamese and in LIGHT and DARK themes.

Checklist:
- Visible labels, accessible text and meaningful accessible roles for interactive controls.
- Icon-only controls have accessible names and tooltips.
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
- Extend coverage for the main chat shell, conversation timeline, composer, message states, connection/reconnect states, group flows and admin operational views where they are not yet covered.
- Protect tests against machine-dependent font rendering, locale, timezone, focus and animation timing.

Current known FX test classes include:
- `desktop-user/.../DesignSystemFxTest.java`
- `desktop-user/.../RenderedControlContrastFxTest.java`
- `desktop-user/.../UserGoldenScreenshotFxTest.java`
- `desktop-admin/.../AdminDesignSystemFxTest.java`
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
