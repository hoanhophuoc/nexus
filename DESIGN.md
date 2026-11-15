# Nexus Design System — Agent Entry Point

> **This file is a navigation entry point, not a second design specification.**

Every agent and developer changing UI in Nexus must read:

1. [Nexus UI/UX Design System](docs/ui-ux-design-system.md) — the single canonical source for product design intent, colors, typography, components, motion, accessibility, localization, UX states and quality gates.
2. [Nexus UI/UX Tooling Decisions](docs/ui-ux-tooling.md) — approved candidates, rejected alternatives, versions, licenses and proof-of-concept requirements.
3. [Nexus UI/UX Agent Skill](.agents/skills/nexus-ui-ux/SKILL.md) — the required workflow for every UI/UX change.
4. [Repository Agent Instructions](AGENTS.md) — project-wide engineering constraints.

## Product design direction

**Tactile Privacy & Precision Craft** is the working art direction: calm, human, content-first communication in `desktop-user`; precise, information-rich, keyboard-efficient workflows in `desktop-admin`. Learn from the interaction quality of respected desktop products, but do not copy their brand or full interface.

## Technical identity

- Native desktop: JDK 27, JavaFX 27.0.1, FXML and JavaFX CSS.
- Shared foundations: `desktop-shared` owns shared tokens, theme behavior and reusable components.
- User workflows: `desktop-user`.
- Admin workflows: `desktop-admin`.
- `server`, `client-sdk` and `api` do not receive desktop UI dependencies.

## Non-negotiable rules

- Native JavaFX patterns take precedence over web-specific instructions.
- Preserve one authoritative theme manager and one semantic token system.
- Keep the English/Vietnamese resource bundles, LIGHT/DARK parity, keyboard operation, accessibility, performance and security wording correct.
- Verify actual rendered results with FX tests, contrast checks and reviewed golden screenshots.
- Do not add a runtime dependency without checking its GA status, JDK/JavaFX compatibility, license, packaging and measured value.

When this file and either canonical document disagree, the detailed canonical document wins. Update the canonical document and this entry point only when the product direction itself changes.
