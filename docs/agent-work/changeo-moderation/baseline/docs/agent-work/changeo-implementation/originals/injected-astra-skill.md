<skill>
<name>astra-flash-orchestrator</name>
<path>/home/paradis/.agents/skills/astra-flash-orchestrator/SKILL.md</path>
---
name: astra-flash-orchestrator
description: >-
  Execute substantial coding builds, features, migrations and refactors with a
  thin GPT-6.1 goal coordinator, a fresh GPT-6.1 feature coordinator/planner, one
  GPT-6.1 developer and an independent GPT-6.1 final reviewer per feature.
  Use durable original requirements and bounded contexts; optional separate plan
  review only for concrete high-risk designs or explicit request. Honor single-agent
  and plan-only overrides. Trivial standalone edits may remain direct; every card
  inside a mandated per-feature goal gets all roles. Assigned feature coordinators
  may orchestrate bounded children; implementers and reviewers must not.
---

# GPT-6.1 goal coordination, feature coordination, implementation and review

Use native Codex delegation and the globally configured Router. This is the default
for substantial coding across projects. User overrides and actual project permissions
remain authoritative; this skill grants no new authority. An explicit single-agent
or plan-only request retains that scope. Trivial standalone work may be direct;
inside a mandated per-feature goal every feature/card uses the topology below.

Read and apply [references/code-quality.md](references/code-quality.md) before
planning, implementation or review; reuse a complete injected copy. Pass its exact
installed path in every role brief.

Load references for the assigned role and current decision, not the whole package
for every child. Required originals and guidance must still be read completely;
reuse identical complete copies already available in context. Keep one authoritative
evidence report per role; checkpoints and notes link to its facts instead of
repeating them. Scale document detail to uncertainty while preserving every required
role, independent inspection and verification obligation.

## Roles and context ownership

1. **GPT-6.1 goal root:** preserve global originals, order, shared contracts,
   dependencies and permissions; dispatch fresh feature coordinators; maintain a
   compact goal ledger and verify cross-feature integration. Only root owns /goal.
2. **Fresh GPT-6.1 feature coordinator:** read the complete feature originals and
   relevant current code; own detailed discovery, plan, child briefs, corrections,
   risk reassessment and blocker diagnosis for that one feature.
3. **GPT-6.1 developer:** use `astra_flash_builder`; independently inspect current
   code, implement the approved plan, verify and report. Completion means ready
   for review, never accepted.
4. **Separate fresh GPT-6.1 final reviewer:** independently inspect originals,
   current code, complete baseline-relative patch and verification evidence; only
   this reviewer accepts the feature under the default delegated workflow.

Determine your assigned role from the handoff. A feature coordinator must not
promote itself to goal root, create another coordinator tier or start a nested goal.
Neither coordinator implements or fills a review role. Implementers and reviewers
execute their briefs; they must not invoke this orchestration workflow or delegate.
Keep feature roles distinct and never reuse them across features. Keep the same
feature coordinator, current implementer and reviewers for necessary corrections;
no fixed retry cap, cost-based early acceptance or reduced verification.

Read `references/coordination.md` for the root/feature handoff, shared-contract
changes, compact return record and nested-versus-sibling dispatch. Use fresh
contexts with relevant authoritative files rather than full-history forks.
Detailed code reads, logs and failed attempts stay in feature contexts/files. Root
receives compact status and evidence paths; it does not redo feature planning or
review. It reads detailed evidence when a real global contract/integration decision
requires it. Moving every transcript into a root summary defeats this separation.

A separate GPT-6.1 plan reviewer is optional, used only for a concrete high-risk
design decision (persistence/data loss, concurrency/lifecycle ownership,
authorization boundary, broad cross-module contract) or explicit user request.
Record the reason or "not required" and resolve material findings before development.
It is different from the final reviewer and never replaces the final review gate.
Do not add it merely because work is large or mentions risk.

## Preserve original requirements and boundaries

Capture complete original requirements, later corrections, source identifiers and
relevant attachments/snapshots before dispatch. Keep goal-wide requirements,
contracts and ledger under `docs/agent-work/<goal>/`; keep feature plans, briefs,
reviews, reports, checkpoints, notes and saved logs under `docs/agent-work/<feature>/`.
A single-feature goal can share its directory; use distinct role-owned files.
Summaries and indexes never replace original source content. Do not snapshot
credentials or unrelated private data; reference host session logs in place.

Every feature role independently reads its complete task originals, applicable goal
restrictions, accepted contracts, repository guidance and relevant current code.
The root independently reads global originals and authoritative shared decisions;
it need not reload every feature's source/code on each progress update. Pass exact
source paths, workspace/baseline, ownership, accepted dependencies, required skills,
permissions and acceptance criteria. Ensure file reads are complete; follow up on
truncated output. Update handoffs after source, requirement or contract changes.

Use each project's actual permissions. For Smash or assigned worktrees, read the
Smash-only boundaries in `references/execution.md` and pass them to every role.
Delegate no new authority. Record unresolved questions with task/card links and
evidence under the feature directory; continue other unblocked work in requested
order, then ask the remaining questions in chat too. Never invent missing answers.

## Confirm roles and routing

Read installed `routing.json` and `references/routing.md`. Both coordinator
levels, task producers/planners, developers and plan/final reviewers use
`gpt-6.1-sol` with `high` reasoning effort. Dispatch native default agents with
explicit `model="gpt-6.1-sol"` and `reasoning_effort="high"`; do not inherit effort
defaults. Implementation uses the installed `astra_flash_builder` role, now pinned
to `gpt-6.1-sol` with `high` effort and inherited parent transport. Its legacy name
does not select DeepSeek. A native default agent with explicit model/effort and a
bounded brief suffices for coordination; do not create a custom role or edit global defaults just
for this topology. Custom roles can override model selectors: verify actual host
model/provider and record distinct role IDs plus Router attribution. A model's
self-identification is not evidence.

Reuse valid static/routing evidence unless configuration/session facts changed.
When needed, run the read-only doctor with the actual CODEX_HOME/profile and inspect
builder TOML/provider overrides it does not cover. Do not silently switch models,
providers, root transport, global defaults, failover or native redirection. No paid
probes/certification without explicit authorization; unavailable metadata means
unverified routing. Missing required roles/routes are blockers, not permission to
omit roles. Queue capacity; use the sibling relay when nesting alone is unavailable.

## Feature planning, implementation and review

The feature coordinator reads `references/planning.md`, inspects current source,
preserves dirty-workspace baselines and writes a dependency-aware plan with exact
contracts, bounded ownership, acceptance criteria and feasible verification. Capture
tracked/staged and relevant untracked content; HEAD alone is not a dirty baseline.
Original plans from another workflow remain authoritative inputs, not replacements
for inspection. Templates and `scripts/validate_plan.py` are optional structural aids,
not approval; manifest paths use `--repo-root <actual-repository-root>`.
Manifests use `executor: "developer"` and `max_workers: 1` (or 2 for permitted,
isolated parallel work); regenerate manifests with legacy executor names before use.

Read `references/execution.md` for worker ownership, correction and blocker
diagnosis. Keep one active code writer per workspace. Feature coordinators dispatch
bounded children natively, or use the recorded root sibling relay; no duplicate
writers, recursive worker agents or external agent CLIs. Let workers perform their
internal code/test/fix loop; wait timeouts alone are not blockers.

Read `references/review.md`. The separate final reviewer verifies originals, code
quality and the full original-baseline patch, including work retained through
corrections. It independently runs checks needed for confidence within actual
permissions; explicitly record missing verification. Batch corrections to the
current implementer through the feature coordinator and re-review until accepted
or genuinely blocked. Material local plan changes return to the feature coordinator
for risk reassessment; newly high-risk designs receive separate plan review.
Shared-contract changes follow the root decision procedure in coordination.md.

Keep the same developer and independent reviewer through corrections. Diagnose
repeated failures before further edits: plan/spec defects require replanning;
missing authority, inputs, dependencies or environment/provider access remain
blockers. Do not substitute models or create a fallback writer. Preserve partial
work, the original baseline, one-writer ownership and independent review.

## Complete and recover

Feature coordinators checkpoint meaningful transitions and return compact decisions,
review verdict/evidence, integration state, blockers and exact next action to root.
Root records the ledger, confirms accepted dependencies are present and ensures
whole-goal integration checks are satisfied on the final state before completing
the goal. Use the evidence-reuse and optional-helper rules in `references/coordination.md`;
neither coordinator self-accepts changed code.
Interrupt completed/idle children through native controls, and resume the same child
with follow-up for that feature's corrections. Do not interrupt healthy work to poll.

After compaction/restart, each role reloads its authoritative originals, latest
contract decisions and checkpoint before continuing. Root reloads the goal ledger,
not every past transcript. Refresh context only through real host mechanisms; this
workflow does not clear root history, automatically migrate /goals or guarantee
zero compaction, unlimited runtime, lower cost or perfect recall. No automatic
commits, staging, merges, pushes, deployments or migrations follow acceptance.

</skill>
