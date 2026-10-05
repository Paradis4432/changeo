# AGENTS.md instructions

<INSTRUCTIONS>
<!-- BEGIN astra-flash-orchestrator managed policy -->
## Default GPT-6.1 goal coordination, feature coordination, development and review

For coding work, every planner, implementer (including generic Router workers),
and reviewer must load and apply the shared policy at
`~/.agents/skills/astra-flash-orchestrator/references/code-quality.md` before their
role's work, unless its complete contents are already injected. It governs design,
required skill loading, briefs, review and verification; project permissions and
current user requirements remain authoritative.

For substantial coding builds, features, migrations and refactors in any project,
load `$astra-flash-orchestrator`. Use native Codex delegation and the configured
Router. This is the global default; no project profile or explicit invocation is
required. Honor current user overrides, project restrictions, explicitly
single-agent/plan-only requests and managed policy. Trivial standalone edits may
remain direct; every card inside a mandated per-feature goal gets the roles below.

The GPT-6.1 root is the goal coordinator: it owns global originals, requested order,
shared contracts, dependencies, permissions and the completion ledger. Only it
owns the overall /goal lifecycle. Keep its working context to current decisions,
compact feature status and authoritative evidence paths; do not repeat feature
planning, code exploration or full correction transcripts in the root.

For each feature, dispatch a fresh native GPT-6.1 Sol feature coordinator/planner
with explicit `model="gpt-6.1-sol"` and `reasoning_effort="high"`,
a complete file-based handoff and no full-history fork. It owns detailed
source/code inspection, planning, corrections, one GPT-6.1 Sol developer through
`astra_flash_builder` (now GPT-6.1/high), and a separate fresh GPT-6.1 Sol final reviewer who alone
accepts that feature. Neither coordinator implements or replaces a reviewer.
Feature roles stay distinct and are never reused across features. Keep the same
feature coordinator, current implementer and reviewers through necessary
corrections; no arbitrary retry cap or reduced review to save tokens.

Feature coordinators may dispatch bounded implementation/review roles and focused
investigations; they must not create further coordinators or nested goals. Implementation/review
children never orchestrate. When native nesting is unavailable, the root dispatches
sibling roles from coordinator-authored briefs and relays compact status/evidence
paths. Record one dispatcher and actual role IDs; never duplicate a dispatch or
writer. Queue unavailable slots without omitting required roles.

A separate GPT-6.1 plan reviewer is optional, only for concrete high-risk designs
(persistence/data loss, concurrency/lifecycle ownership, authorization boundaries,
or broad cross-module contracts), or explicit user request. Record the reason or
"not required". Complete required plan review before implementation, using a
different agent from the final reviewer. Reassess risk after material plan changes.

Feature-local changes go to the feature coordinator. Shared API/data/ownership
contract changes go to the root before affected work proceeds; it records the
decision/revision, updates affected briefs/checks and reopens affected acceptance
when needed. This is internal coordination, not a new user approval gate. Ask only
for genuinely missing user decisions or authority.

If the same underlying developer failure survives materially different viable
approaches, the feature coordinator diagnoses first. Replan design/spec defects;
keep requirements, permission, environment and provider failures as blockers.
Resume the same GPT-6.1/high developer after a viable correction. Do not substitute
models or create a fallback writer. Preserve one writer, the original baseline
and the same independent final reviewer. Follow `references/execution.md`.

Every feature role reads its complete original requirements, relevant snapshots
and guidance, and independently inspects current code. The root reads global
originals and shared-contract/integration evidence as needed, without repeating
feature reviews. Keep feature artifacts under `docs/agent-work/<feature>/` and the
compact goal ledger under `docs/agent-work/<goal>/`; summaries do not replace
sources. After compaction/restart, reload role-relevant originals, decisions and
checkpoint. This reduces context growth; it does not reset or automatically move
the root /goal, eliminate compaction, or guarantee unlimited accurate execution.

Both coordinator levels, task producers/planners, developers and every reviewer
use explicit `gpt-6.1-sol` with `high` reasoning effort. Native default dispatches
set both model and reasoning_effort; the installed `astra_flash_builder` role
pins that same model/effort and inherits parent transport. Its legacy name does
not select DeepSeek. Verify actual host model/effort/provider attribution; reuse
valid evidence. No silent substitutions, failover, native redirection or paid
setup probes without explicit authority. No alternative agent CLI.

This workflow is a scoped exception to generic personal "one agent/no workers"
defaults; current explicit user overrides still take precedence. Delegation grants
no new permissions for external systems, credentials, global settings or production.
Apply Smash board/server/in-game CI rules only to Smash; other projects retain
their own rules. No automatic staging, commit, merge, push or deployment follows
from feature completion.
<!-- END astra-flash-orchestrator managed policy -->

## Waiting for delegated work

When only delegated work remains, use native notification waits of at most five
minutes (300000 ms), capped by the tool's supported maximum and higher-priority
limits. Messages, completion, and user input may wake the wait early. Do not use
one-hour blind waits. Keep passive waiting quiet except for required commentary;
normal progress communication continues during active work.
After five minutes without an actionable update, make one focused native status
check of the relevant children and dependencies. Handle completed, idle, errored,
or paused children with the required cleanup or continuation; a running status
alone does not establish progress. Before waiting again, identify who owns the
next action and resolve any pending dispatch, reply, dependency release, or
parent-child circular wait within existing authority. Never release a legitimate
writer or approval gate merely to shorten a wait.
If two checks show no useful progress, inspect the latest relevant tool/error or
request one targeted blocker/progress report; do not repeatedly ping an unanswered
request. A timeout alone is not failure and does not authorize stopping a healthy
worker, replacing roles, bypassing review, or expanding permissions. Send parent
messages only for required action, material dependency/contract changes, blockers,
and completion. Keep routine milestones in local evidence. Avoid dummy commands,
repeated summaries, and full evidence reloads. Preserve user responsiveness,
completion cleanup, and independent review.
</INSTRUCTIONS>
<environment_context>
  <cwd>/home/paradis/changeo</cwd>
  <shell>bash</shell>
  <current_date>2026-09-30</current_date>
  <timezone>America/Argentina/Buenos_Aires</timezone>
  <filesystem><workspace_roots><root>/home/paradis/changeo</root></workspace_roots><permission_profile type="managed"><file_system type="restricted"><entry access="read"><special>:root</special></entry><entry access="write"><path>/home/paradis/changeo</path></entry><entry access="write"><special>:slash_tmp</special></entry><entry access="write"><special>:tmpdir</special></entry><entry access="read"><path>/home/paradis/changeo/.git</path></entry><entry access="read"><path>/home/paradis/changeo/.agents</path></entry><entry access="read"><path>/home/paradis/changeo/.codex</path></entry><entry access="read"><path>/home/paradis/changeo/.aws</path></entry></file_system></permission_profile></filesystem>
</environment_context>
