/goal {{list}}

$java-coding-standards

$software-orchestrator

Roles, models, review, ownership and recovery follow the global AGENTS.md role table and `$software-orchestrator`; this prompt adds only goal-specific requirements.

You are the root (goal coordinator) for this quality-first goal.
Handle all active tasks below in supplied order, subject to the delivery gates.
Do not invent missing requirements or silently expand scope.

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
The docs record an earlier Astra-only agent preference. The current global AGENTS.md
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

- Work within the permitted Changeo workspace and explicitly authorized test environments.
  No remote server, production database, partner account or deployment authority is
  implied by this prompt. Inspect the actual baseline; preserve protected workspace
  metadata and do not manufacture a Git repository by replacing it.
- Use approved private staging, partner sandboxes and test identities for website
  verification when available. Follow project restrictions on checks and migrations.
- Preserve all pre-existing work. No automatic staging, commit, merge, push or
  deployment follows completion; those require my applicable explicit instruction.

If you cannot resolve a required fact through authorized inspection, record the exact
question and hold dependent work until I answer. Continue other unblocked tasks in
order. Once all remaining work depends on my answers, pause the goal and ask in chat.
Do not invent answers or treat elapsed time as approval.

# Originals, briefs and bounded work

Other task documentation belongs under docs/ too.

Use templates/feature-coordinator-brief.md and templates/task-brief.md from the
skill. Each handoff supplies complete original-source and restriction paths,
accepted contract revisions and actual dependencies, workspace/baseline/pre-existing
changes, required AGENTS/Java/web/testing/skill guidance, inspected reference implementations,
ownership boundaries, guarantees, accepted plan/findings, assumptions, unresolved
questions and acceptance/verification criteria. Required loaded-skill attribution
records names/full paths, read versus fully injected, and application evidence.

For ambiguous reports, identify evidence that distinguishes remaining causes.
Once authorized inspection establishes that the next decision requires unavailable
browser/application/build/partner/configuration facts, save the diagnosis and exact question, stop dependent
work and continue other unblocked cards in order. Keep the required developer diagnosis
and independent review. Static support or an empty patch does not prove a report
invalid or fixed; avoid unrelated exploration without a discriminating question.

# Routing, verification and completion

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

Put unresolved questions/notes in .txt files with the relevant task identifier and
specific docs/ requirement links; include external task links only when supplied. After
all other unblocked work finishes, ask remaining questions in chat too so I can answer
or ask testers. Do not invent answers or continue dependent work while unresolved.

# Tasks

