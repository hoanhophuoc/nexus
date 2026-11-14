---
name: nexus-ui-ux
description: "Mandatory project-specific skill for every UI/UX change in Nexus desktop-user, desktop-admin or desktop-shared. Use when creating, redesigning, polishing, reviewing, styling, animating, localizing or testing any JavaFX screen or component."
---

# Nexus UI/UX Engineering Skill

This skill is mandatory for every visual or interaction change in Nexus. It is a native JavaFX desktop product. Do not translate web-only design rules into JavaFX.

## Required context (read before editing)

1. Read `docs/ui-ux-design-system.md` in full.
2. Read the relevant decisions in `docs/ui-ux-tooling.md`.
3. Read repository `AGENTS.md`, `.agents/profile.md` and the relevant `docs/README.md`.
4. Inspect the target FXML, controller, CSS, shared components, both app themes and adjacent screens.
5. Inspect existing FX tests and golden screenshots before making visual changes.
6. Verify the actual JDK/JavaFX versions and current release compatibility. Current repository baseline at skill creation: JDK 27 and JavaFX 27.0.1.

Do not rely on memory from a previous task or on an unpinned external skill. These repository documents are the source of truth.

## Design workflow

For every non-trivial UI task:

1. State the target user, task, primary action and success outcome.
2. Identify loading, empty, error, offline, permission, selected, disabled, hover and keyboard-focus states that apply.
3. Inspect shared UI components and reuse them when semantics match.
4. Define layout hierarchy, token usage, component states, keyboard flow, motion and EN/VI copy before coding.
5. Implement the smallest complete change in the correct module:
   - shared primitives/theme in `desktop-shared`;
   - user-specific views in `desktop-user`;
   - admin-specific views in `desktop-admin`.
6. Keep FXML for declarative view structure, controllers for view coordination, services for application behavior, CSS for appearance.
7. Keep all screen text in resource bundles; verify English and Vietnamese.
8. Test LIGHT and DARK themes; test SYSTEM theme behavior when relevant.
9. Run FX interaction, contrast and visual-golden tests. Inspect the actual captured images.
10. Update relevant docs and include screenshots plus test results in the PR.

For a major new screen or redesign, consider at least two design directions and use Figma/OpenDesign for prototype review only when doing so reduces uncertainty. The final implementation must be native JavaFX and meet the repository design contract.

## Native JavaFX rules

- Use JavaFX 27-compatible APIs; do not copy JavaFX 26 examples without checking for differences.
- Do not use web technologies or web motion frameworks as if they were JavaFX libraries (for example Tailwind, DOM, ARIA attributes, GSAP or browser breakpoints).
- Use FXML, JavaFX controls/layout panes, CSS style classes and semantic looked-up tokens.
- Avoid `Node.setStyle(...)` for production component styling.
- Do not block the FX Application Thread with networking, disk, database, crypto or expensive parsing. Use the existing async services or JavaFX Task/Service patterns and update the scene graph on the FX thread.
- Use observable collections and virtualized controls for potentially large message/conversation lists. Do not create one scene-graph node per message in an unbounded history.
- Preserve scroll position and focus; do not force the chat timeline to the bottom while the user is reading older messages.
- Use JavaFX-native animation APIs. Motion must be purposeful, short, interruptible, lightweight and reduced-motion-aware.
- Design for desktop keyboard/pointer conventions and high-DPI scaling; do not blindly apply mobile 44px target or web responsive rules.
- Do not add dependencies to server/client-sdk/api for desktop visual features.

## Library policy

Follow `docs/ui-ux-tooling.md`:
- AtlantaFX 3.0.0 is the preferred theme candidate but still requires a JavaFX 27.0.1 proof of concept before production adoption.
- Ikonli 12.4.0 GA is the preferred icon candidate; Early Access releases are disallowed by the GA-only policy.
- UI UX Pro Max can be used as a UX/design-research assistant; verify JavaFX guidance against JavaFX 27 and this repository.
- Impeccable is process inspiration only unless JavaFX support is explicitly proven.
- Taste Skill is website-specific inspiration, not the JavaFX implementation skill.
- OpenDesign is an optional prototype workspace.
- ControlsFX and FormsFX are selective options only after a concrete need and compatibility proof.
- Do not combine AtlantaFX with MaterialFX or JMetro as competing theme systems.
- Do not add any external library because it is popular or has many stars.

No dependency may be added without checking its latest GA release, JavaFX/JDK compatibility, license, transitive graph, native packaging and tests. Never weaken the GA-only dependency gate to adopt an Early Access build.

## Product and trust requirements

- User desktop: prioritize conversations, message timeline, composer, presence, unread state, delivery/retry, connection recovery and accurate group-security status.
- Admin desktop: prioritize operational visibility, filterable data, auditability, moderation evidence and safe destructive actions.
- Keep user and admin layouts appropriately distinct while sharing foundations.
- Never clone another product's branding or complete layout.
- Never claim MLS-style group crypto is RFC 9420; the repository explicitly says it is not RFC 9420.
- Do not claim all messages are fully end-to-end encrypted unless that claim is verified for every flow and approved.
- Never leak credentials, tokens, key material or private message content in logs, screenshots, accessibility text or error details.

## Mandatory quality gate

Before calling a UI task complete:

- Build the affected desktop module.
- Run the applicable unit and JavaFX FX tests.
- Run `./gradlew :desktop-user:fxTest :desktop-admin:fxTest` when shared theme/components change.
- Run `./gradlew fullTest` before submitting, or report a specific environment blocker honestly.
- Run existing rendered-control contrast tests for changed controls/skins.
- Run golden screenshot tests for every changed visual surface in both themes; manually inspect the images.
- Verify English and Vietnamese copy and text wrapping.
- Test keyboard-only operation, focus visibility, dialog focus restoration and IME-safe shortcuts.
- Verify no blocking work is performed on the FX thread.
- Confirm FXML/CSS/fonts/icons load from packaged resources, not just IDE classpaths.
- Include before/after screenshots in the PR for UI changes.
- Update `docs/ui-ux-design-system.md` or `docs/ui-ux-tooling.md` when the contract or tool decision changes.

Never refresh a golden screenshot just to make a test pass. Inspect and justify the visual change first.
