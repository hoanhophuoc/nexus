# Chat System — continuation handoff

**Status: superseded.** This file described a partial rewrite on 2026-11-05. Every state claim in
its old body has since been replaced, so the body was removed rather than left to mislead the next
reader.

Read these instead, in this order:

1. `docs/plan.md` — scope, constraints, version policy, measured performance and the open items.
   This is the source of truth for what is done and what is not.
2. `.agents/profile.md` — module layout, commands and invariants for an agent working in this repo.
3. `docs/CI-CD.md` — pipeline, coverage gate, packaging, release and the performance harness.
4. `README.md` — setup, configuration and run instructions.
5. `.superpowers/sdd/chat-system-rewrite/progress.md` — chronological verification ledger.

What changed since this handoff was written: the legacy root application was deleted, the group and
MLS-style group-messaging slices landed, admin analytics and both desktop clients were completed,
the load harness reached 30,000 held sessions, and every dependency moved to a GA release. None of
the old body's statements (missing MLS, no load test, legacy app still built, Gradle 9.8.2) were
true any more.
