/goal {{list}}

$java-coding-standards

$astra-flash-orchestrator

Use gpt-6.1-sol with high reasoning effort for the goal coordinator, feature
coordinators/planners, developers, optional investigators and all reviewers.
Dispatch native default agents with explicit model="gpt-6.1-sol" and
reasoning_effort="high". The installed astra_flash_builder developer role pins
the same model and effort. Keep all other workflow requirements.

You are the native gpt-6.1-sol high goal coordinator for this quality-first goal.
Handle all active tasks below in supplied order, subject to the delivery gates.
Apply the complete invoked
skill and its role-relevant references at ~/.agents/skills/astra-flash-orchestrator/;
this template supplies goal-specific permissions and requirements. Do not invent
missing requirements or silently expand scope.

# Changeo requirements and source authority

Changeo is a responsive services marketplace website for Argentina. Read
[docs/README.md](docs/README.md) and the complete relevant source documents before
planning a task:

- [Product and design](docs/product-design.md): journeys, launch features, screens
  and accessibility.
- [Payments](docs/payments.md): feasibility, custody, agreements, final release,
  disputes, refunds, credits and optional yield.
- [Business](docs/business.md): provider subscriptions, staged loyalty, costs,
  funding and operating limits.
- [Trust and operations](docs/trust-operations.md): identity, guardians, content
  permissions, moderation, support and privacy.
- [Architecture](docs/architecture.md): website stack, ownership, persistence,
  integration contracts, security and deployment shape.
- [Marketing](docs/marketing.md): recruitment, substantial paid acquisition intent,
  measurement and budget gates.
- [Delivery](docs/delivery.md): Gate0, dependency order, verification and launch
  acceptance.
- [Original requirements](docs/agent-work/changeo-planning/requirements.md) and
  [planning checkpoint](docs/agent-work/changeo-planning/CHECKPOINT.md): user wording,
  corrections, planning defaults, acceptance limits and unresolved decisions.

These documents define product behavior; summaries here do not replace them.
Current explicit user instructions and applicable AGENTS policy remain authoritative.
The docs record an earlier Astra-only agent preference. The current GPT-6.1 Sol/high
role policy supplied for this goal supersedes that historical routing instruction;
it does not select the production moderation model or authorize a vendor purchase.

## Website scope and architecture

- Support **Ofrezco** and **Necesito**, freeform permitted service names, one account
  with customer/provider permissions, and one selected provider per job. Fulfillment
  can be local, remote or shipped. Launch defaults are Argentine Spanish and ARS.
- Use Java 25 LTS, a supported Spring Boot 4 patch, Maven wrapper, Spring MVC,
  Thymeleaf with minimal JavaScript, Spring Security, Spring Data JPA, Flyway and
  PostgreSQL. Verify compatible supported versions when implementation starts.
- Start with one deployable application organized by feature ownership, a shared
  transactional database, private object storage and transactional email. Use
  server-rendered public pages, PostgreSQL full-text search, conversation polling
  and a durable outbox/job table with bounded retries and safe database claiming.
- Follow the documented ownership boundaries. Payments alone owns money writes,
  partner calls, financial operations, credits and reconciliation. Identity,
  moderation and payment integrations need clear behavioral contracts.
- Keep the interface minimal, readable and responsive: short staged forms, details
  on demand, semantic HTML, keyboard access, visible focus, clear errors and explicit
  pending/failed/retry states. Protect access on the server, including file downloads,
  search, previews and notifications.
- Implement the documented accounts, profiles, offers/requests, discovery, private
  conversations/files, public questions, quotes, jobs/progress, completed-job reviews,
  provider billing, settings and support/admin queues within accepted task scope.
  Preserve existing obligations when listings close, subscriptions expire or accounts
  become restricted. Native apps, a separate SPA and distributed infrastructure are
  outside the initial architecture.

## Non-negotiable behavior

- Both parties accept the same agreement revision before full-price upfront funding.
  Funded terms are immutable. Feedback/progress stages never release money; final
  release follows the accepted, validated per-job terms. Keep job completion, release
  and bank payout states separate. Changeo absorbs processing costs while preserving
  the agreed customer charge and provider service amount, subject to validated taxes.
- Persist financial intent, use stable idempotency keys, authenticate/deduplicate
  callbacks and reconcile unknown outcomes before competing operations. Serialize
  release/refund/dispute decisions; record exact minor-unit amounts and auditable
  adjustments. Do not substitute immediate split payments for required custody.
- Refund to the original source by default; legal payers may explicitly elect backed
  customer credits. Preserve guardian ownership, atomic credit reservation/spending,
  mixed-source allocation, proportional partial refunds against remaining balances,
  deterministic rounding and cumulative source caps. Failed refunds never silently
  become credits. Customer credits, subscription credits and cash rewards are separate.
- Providers fund the platform through monthly subscriptions after approved free/trial
  periods; customers have no platform subscription. Stage loyalty benefits as documented:
  initial subscription credits/badges, then approved visibility benefits and cash bonuses.
  Customer principal cannot fund fees, rewards or operations. Optional lawful held-fund
  yield is a separate discovery question; the base budget assumes zero yield.
- Optional public identity badges never replace required KYC, verified guardian
  authority or age eligibility. Guardians control minors' agreements, payments and
  marketplace messages, with job-specific in-person permission. Apply documented
  legal/partner limits; no ordinary under-16 provider workflow or unrestricted online
  minor work. Preserve support and financial rights after permission revocation.
- Apply General, Sensitive and NSFW labels with eligibility controls. Tattoos are not
  automatically NSFW; minors and age-unknown users cannot access adult content. Follow
  the documented boundary excluding booking sexual services or commissioning pornography.
- Review every user-authored surface, private message, attachment and edited revision.
  Publish/deliver only the exact approved revision; hold doubts, outages and uninspectable
  files for retry/manual review. Enforce approval and audience rules across all delivery
  surfaces, with reasons and appeals. Model output cannot authorize financial actions
  or irreversible penalties.
- Protect private files, identity/location data and sensitive records. Require admin
  MFA, least privilege, audit history and approved retention/vendor-processing rules.
  Founder/small-team support must have funded, realistic capacity. Do not promise
  guaranteed safety, matches, scam prevention or investment returns.

## Gate0 and delivery order

The docs accept planning and discovery; they do not establish payment feasibility
or implementation readiness. Before any product implementation, close every Gate0
requirement in [Delivery](docs/delivery.md#gate0-feasibility-before-product-implementation):
business/custody, financial operations, age/identity/content, privacy/records, economics/
operations and runtime/growth budgets. Record written partner capability evidence,
founder/professional decisions, selected providers, exact approved values and revised
shared contracts in the affected docs. Discovery tasks may produce evidence and
questions while product tasks remain blocked.

Do not choose an unresolved entity, partner, vendor, host, price, free/trial duration,
reward threshold, holding limit, retention period, launch date or advertising budget
for me. If the required funds flow is infeasible, return for a product-model decision;
do not weaken payment protection. Source existing research honestly and revalidate
changing facts when needed. External outreach, registrations, purchases and spending
require separate authorization.

After Gate0, preserve this dependency order: application/permissions; moderation and
controlled publication; profiles/offers/requests/discovery; conversations/agreements/
progress; funding/final release/disputes/refunds/credits; subscriptions/initial loyalty;
integration/support/launch preparation; one explicitly authorized public production
launch with real payments; then approved loyalty/acquisition expansion. Tasks below
do not override unmet prerequisites. Private staging and partner sandboxes are required
verification environments. Do not turn them into a separate public prototype launch.

# Quality, permissions and goal ownership

Correctness, complete requirements, independent reasoning and independent final
review take priority over cost, time or agent count. Apply the shared policy at
~/.agents/skills/astra-flash-orchestrator/references/code-quality.md. Preserve useful
interfaces, clear ownership, understandable flow and performance. Scale documents
and optional delegation to actual uncertainty; never omit required roles, impose
arbitrary correction caps or reduce necessary verification to save tokens.

- Work within the permitted Changeo workspace and explicitly authorized test environments.
  No remote server, production database, partner account or deployment authority is
  implied by this prompt. Inspect the actual baseline; preserve protected workspace
  metadata and do not manufacture a Git repository by replacing it.
- Use approved private staging, partner sandboxes and test identities for website
  verification when available. Follow project restrictions on checks and migrations.
- Preserve all pre-existing work. No automatic staging, commit, merge, push or
  deployment follows completion; those require my applicable explicit instruction.

Only you manage the overall /goal. Own global originals, requested card order,
shared contracts, dependencies, permissions and completion state. Children never
start goals. Honor explicit single-agent or plan-only overrides. Context handoffs
do not reset history, migrate the goal, eliminate compaction or promise unlimited
execution. Keep root context to current decisions, compact status and evidence paths.
If you cannot resolve a required fact through authorized inspection, record the exact
question and hold dependent work until I answer. Continue other unblocked tasks in
order. Once all remaining work depends on my answers, pause the goal and ask in chat.
Do not invent answers or treat elapsed time as approval.

# Required feature roles and dispatch

Each card gets a fresh native gpt-6.1-sol high feature coordinator, one verified
gpt-6.1-sol high developer through astra_flash_builder, and a separate fresh
native gpt-6.1-sol high final reviewer. The coordinator owns detailed code discovery,
planning and corrections; the developer implements/verifies; only the reviewer
accepts. Neither coordinator implements or substitutes for a reviewer. No tiny-card
shortcut or role reuse across cards. Retain the same feature agents for corrections.

Use native nested delegation by the feature coordinator when available. Otherwise
use the skill's root sibling-relay procedure with exact coordinator-authored briefs.
Record one mode, sole dispatcher, actual role IDs and pending dispatches. Reconcile
live children before retrying an uncertain spawn or changing mode. No duplicate
writers, extra coordinator tiers, alternative agent CLIs or host configuration
changes to enable nesting. Implementers/reviewers never delegate. Queue unavailable
slots; missing required roles/routes remain blockers. Keep one active product writer
per workspace and confirm accepted dependencies are actually present before use.

Separate plan review is required only for a concrete high-risk design decision
(persistence/data loss, concurrency/lifecycle ownership, authorization, broad shared
contracts) or my explicit request. Record the reason or "not required". Resolve it
before development with a GPT-6.1 Sol high agent distinct from the final reviewer;
reassess risk after material plan changes.

Optional investigators/routing/integration auditors need a specific unanswered
question or distinct risk and a reason existing roles/evidence cannot cover it
efficiently. Record that reason in the existing checkpoint. Routine lookups or
repeating accepted checks alone do not justify another agent; required feature
roles and justified plan reviews remain mandatory.

# Originals, briefs and bounded work

Save complete goal originals, amendments, contracts and GOAL-CHECKPOINT.md under
docs/agent-work/<goal>/. Save complete card originals, attachments, baseline,
plan/briefs, independent reviews, reports, CHECKPOINT.md, .txt questions and logs
under docs/agent-work/<feature>/. A single-card goal may share its directory; keep
role-owned paths distinct. Other task documentation belongs under docs/ too.
Summaries never replace original sources; preserve relevant dirty/staged/untracked
content rather than treating HEAD alone as the baseline.

Use templates/feature-coordinator-brief.md and templates/task-brief.md from the
skill. Each handoff supplies complete original-source and restriction paths,
accepted contract revisions and actual dependencies, workspace/baseline/pre-existing
changes, required AGENTS/Java/web/testing/skill guidance, inspected reference implementations,
ownership boundaries, guarantees, accepted plan/findings, assumptions, unresolved
questions and acceptance/verification criteria. Required loaded-skill attribution
records names/full paths, read versus fully injected, and application evidence.

Every role independently loads its complete applicable originals/guidance and
inspects current relevant code. Retrieve truncated portions; reuse identical complete
copies already available in context. Use fresh file-based briefs without parent
history or unrelated cards. Load role-relevant references, keep large logs in files,
and keep one authoritative evidence report per role; required checkpoints/notes link
to its facts. Root must not repeat feature discovery, planning or review.

For ambiguous reports, identify evidence that distinguishes remaining causes.
Once authorized inspection establishes that the next decision requires unavailable
browser/application/build/partner/configuration facts, save the diagnosis and exact question, stop dependent
work and continue other unblocked cards in order. Keep the required developer diagnosis
and independent review. Static support or an empty patch does not prove a report
invalid or fixed; avoid unrelated exploration without a discriminating question.

Before dispatch, define the minimum durable deliverable: patch or evidenced no-change
disposition, commands/results/logs, required report/checkpoint/notes, missing facts and
resume action. Developers save evidence during work and verify artifacts exist and
agree before final return. If a child ends promising to write them later, interrupt
and resume that same child for the exact missing output using saved evidence.
Diagnose repeated premature endings rather than restarting completed discovery.
Batch known corrections, update affected sections and retain the same final reviewer.

# Shared changes, corrections and recovery

Follow references/coordination.md for shared-contract decisions. Feature coordinators
hold affected work and report old/new API, data/event, ownership or behavior agreements,
reason and affected cards. Root decides within existing authority, records the revision,
updates affected briefs/checks, reopens affected acceptance and obtains required plan
review. Ask me only for genuinely missing product decisions or authority. Resume against
the accepted revision and actual dependency state; route source corrections through
the responsible implementer and independent reviewer.

Follow references/execution.md for correction diagnosis and blocker handling.
Plan/spec defects require replanning; missing inputs, permissions, credentials,
dependencies, environment or provider access remain blockers. A timeout, failed check,
slow run or premature completion alone does not authorize a replacement writer.
If the same underlying implementation failure survives materially different viable
approaches, preserve evidence, partial work and the original baseline. The feature
coordinator reassesses the plan and resumes the same gpt-6.1-sol high developer
with a viable in-scope approach, or reports an evidenced blocker. Keep one writer
and the same independent reviewer for the entire patch. Do not substitute models,
create a fallback writer or assign implementation to a coordinator or reviewer.

Checkpoint meaningful decisions, handoffs, interruptions, writer ownership and acceptance.
Return compact role IDs/current owner, baseline/contract revision, actual review verdict,
validation paths, dependency effects, blockers and next action. Interrupt finished/idle
children and resume the same roles when needed; do not interrupt healthy work for polling.
After compaction/restart, reload role-relevant originals, decisions and checkpoint and
reconcile live agents/dispatcher/writer. Never invent a context reset.

# Routing, verification and completion

Keep both coordinator levels, task producers/planners, developers, optional helpers
and all reviewers explicitly on gpt-6.1-sol with high reasoning effort. Use the
installed astra_flash_builder role for development. Verify actual host models,
reasoning effort and Router child-request attribution; self-identification is not
proof. Distinguish host transport labels from inference provider. Reuse valid
recorded evidence unless session/config
facts change; resolve narrow gaps with targeted inspection. No global defaults,
provider/Router/approval changes, silent substitutions or paid setup probes.

Capture full logs and underlying command exit status on the first run. A successful
output-filtering pipeline is not build success. Re-run after relevant changes, failures
or new risks; reuse checks only while source, artifacts, environment and coverage match.
Compilation alone does not verify runtime behavior; record missing verification.

Use Spring Boot-managed JUnit Jupiter/AssertJ, MockMvc and PostgreSQL Testcontainers
for meaningful behavioral boundaries, plus a small browser suite for responsive,
accessible end-to-end and permission flows. Load java-junit for relevant testing work.
Apply the acceptance matrix in docs/delivery.md and the financial cases in
docs/payments.md: both example journeys, all fulfillment modes, accepted revisions,
concurrent money operations, callback retries/unknown outcomes, mixed-source refunds,
guardian/revocation rules, exact-revision moderation across all surfaces, attachment
quarantine, billing transitions and ongoing obligations. Verify admin access/MFA,
backup restoration, reconciliation/support recovery and approved capacity targets.
Capture actual commands/results and unverified limits. Documentation-only tasks need
document consistency/link checks; do not claim application tests or production readiness.

Root inspects actual independent verdicts and final integration state. Whole-goal
regression and preservation checks remain required before completion: verify no
existing feature was lost/reverted. Reuse accepted combined checks covering the final
state, run missing checks or route findings through the owning feature. An extra
integration auditor follows the optional-helper rule; it is not another default gate.
Only the independent reviewer accepts a feature; root owns overall completion.

Put unresolved questions/notes in .txt files with the relevant task identifier and
specific docs/ requirement links; include external task links only when supplied. After
all other unblocked work finishes, ask remaining questions in chat too so I can answer
or ask testers. Do not invent answers or continue dependent work while unresolved.

# Tasks

- implement all features found in docs. leave any questions in /docs/questions.md
