# Delivery, gates and verification

## Current status and scope

The planning program has [independent acceptance](agent-work/changeo-planning/planning-review.md). Private foundations are independently accepted; moderation implementation awaits separate review; production and payment readiness are unestablished. This documentation task saves the accepted direction under `docs/`; no application feature, deployment, external outreach or spending is authorized by the instruction “write to docs.”

Historical planning baseline: no application code or usable Git repository was exposed. Current S1 development uses the user-approved unborn Git master and preserved complete dirty snapshots. Protected workspace metadata must not be replaced. Establish a permitted baseline before future code work and preserve any intervening user changes.

## Gate0: feasibility before product implementation

The founder starts from no established business/payment arrangement. A coordinator prepares evidence and bounded questions; the founder obtains commercial/professional decisions where required. External communications, service purchases and registrations are separate authorized actions.

| Gate | Decision owner | Required evidence/output | Failure consequence |
| --- | --- | --- | --- |
| Business and custody | Founder with qualified legal/tax advice and prospective partner | Entity/contract roles; Argentina/ARS eligibility; funds-flow ownership; permitted service/user scope; holding/release/refund/credit support; partner terms. | No product implementation or paid launch; return for a business-model decision. |
| Financial operations | Founder and payment partner, documented by coordinator | Accepted term choices/holding limits, fee subsidy, refunds/chargebacks/reserves, mixed credits, KYC and reconciliation capabilities. | No money implementation; do not substitute ordinary split payments. |
| Age, identity and content | Founder with legal/privacy review and relevant vendors | Guardian authority process; permitted minor participation/work; adult eligibility; content/service policy; identity and moderation vendor terms. | No affected permissions or vendor integration brief becomes implementation-ready. |
| Privacy and records | Founder with legal/privacy review | Data purposes, access, retention, sensitive data, international transfers, vendor processing, rights and deletion/record obligations. | No unapproved production data processing. |
| Economics and operations | Founder | Actual prices and cost scenarios; subscription/free/trial terms; rewards, reserves, subsidy limits, staffing/hours and support capacity. | No implementation of unapproved commercial rules and no launch commitments. |
| Runtime and growth budgets | Founder informed by coordinator comparisons | Selected host/region, email/storage/identity/AI services, resource budget, marketing tranches, capacity targets and measurement thresholds. | No purchases, campaign spend or unbounded vendor commitments. |

Payment protection and customer-credit capability are required outcomes, not optional shortcuts. If written evidence does not support them, record the blocker and seek the genuinely missing product decision. The zero-yield business case must be viable independently of optional investment income.

Before closing Gate0, record exact values and selected providers in the affected docs and publish a revision of the shared contracts. No builder chooses unresolved commercial/legal policy on behalf of the founder. No launch date is inferred from agent availability.

## Ordered delivery bundles

| Order | Bundle | Accepted prerequisites | Completion outcome |
| --- | --- | --- | --- |
| 0 | Documentation and feasibility | This planning record | Complete evidence, resolved contracts and funded operating model. |
| 1 | Application and permission foundations | Gate0; approved age/privacy/vendor rules | Runnable Java app, data migrations, authentication, guardian/verification permissions and admin controls with real access boundaries. |
| 2 | Moderation and controlled publication | Foundation; approved content/AI policy | Exact-revision review, quarantine, reporting, appeal and delivery controls across the surfaces later features must use. |
| 3 | Profiles, offers, requests and discovery | Foundation and moderation | Both marketplace entry paths work with real persistence, search and permission-safe public pages. |
| 4 | Conversations, agreements and progress | Listings; moderation; approved agreement contract | Private files/messages, quote acceptance, jobs and feedback stages, ready for the payment integration. |
| 5 | Funding, final release, disputes, refunds and credits | Jobs; accepted money contracts and partner access | Reconciled, auditable financial flow through sandbox verification and approved production readiness. |
| 6 | Provider subscriptions and initial loyalty | Accepted billing/reward contracts and financial owner | Free/trial/paid entitlements, renewal/cancellation, funded credits and achievement badges. |
| 7 | Integration, support and launch preparation | All launch bundles accepted | Complete end-to-end journeys, operational runbooks, restore/security/accessibility checks and launch evidence. |
| 8 | Single public production launch | Founder-authorized launch; partner/legal/operational gates passed | Real payments and required trust/support capabilities work for public users. |
| 9 | Loyalty and acquisition expansion | Postlaunch evidence and approved budgets | Cash bonuses, visibility benefits and expanded marketing with measured controls. |

The table states production prerequisites. S1 separately authorizes private synthetic delivery in the same order, beginning with independently reviewed foundations under F1/F2; no production gate is closed. Bundles are dependency milestones, not permission to build disconnected scaffolding. Feature coordinators may size cohesive end-to-end tasks within them. Preserve the order and accepted shared contracts. Private staging and payment sandboxes are required verification environments, not a second public release or prototype.

## Current Sol/high execution workflow

The current goal's explicit GPT-6.1 Sol/high instruction supersedes the historical Astra-only preference. Use explicit `gpt-6.1-sol` with `high` reasoning for the root, fresh feature coordinator, sole `astra_flash_builder` implementer and separate independent final reviewer. Verify actual host attribution when available; a selected model name alone is not proof of provider routing. Do not silently substitute models or run paid setup probes.

The root owns global originals, dependencies, shared contract revisions and the compact ledger. Each fresh feature coordinator owns its detailed inspection/plan/corrections, and dispatches one bounded implementer plus an independent final reviewer. Coordinators do not implement or self-accept. Keep the same feature roles through corrections; do not impose a retry cap or weaken verification to save tokens. Use one active code writer per workspace, or isolated accepted workspaces with explicit integration boundaries.

Every role reads complete authoritative requirements, current decisions, relevant current code and applicable guidance. Load the shared code-quality policy and Java/framework/testing skills relevant to the task. Record reads, commands, outcomes and verification limitations. No existing local code reference can be claimed for the initial build.

Use file-based fresh-context handoffs. Feature plans, briefs, reviews and checkpoints belong under `docs/agent-work/<feature>/`; global originals and compact status belong under the goal/planning directory. Summaries do not replace originals. Do not start nested goals or assume this task created an overall goal.

Add a separate plan reviewer for concrete money lifecycle, authorization, persistence/concurrency or shared-contract risks; record the reason. That review is distinct from final implementation acceptance. Escalate shared API/data/ownership changes to the root before affected work proceeds. Stop/interrupt completed children so their host status reflects completion.

No automatic staging, commit, merge, push, migration against production, deployment or external messaging follows acceptance.

## Acceptance matrix

| Area | Required scenarios |
| --- | --- |
| Main journeys | Offer 3D printing and satisfy an unusual posted request from discovery to final settlement; original offer remains reusable. |
| Fulfillment | Local appointment, remote deliverable and shipped print with appropriate agreement/evidence and no accidental early payout. |
| Agreements | Same accepted revision on both sides; reject stale changes and duplicate acceptance; stages only track feedback. |
| Money lifecycle | Valid/invalid callback authentication, duplicates, reordering, timeouts, concurrent release/refund/dispute and post-payout recovery. |
| Credits/refunds | Explicit customer election, legal payer/guardian ownership, insufficient/contended credit, mixed funding, repeated one-cent partial refunds and source caps. |
| Permissions | Optional badges do not bypass guardian/KYC rules; unknown age and minors cannot access adult content; revoked permission cannot silently strand money. |
| Moderation | Every content surface and edit, stale model results, unsupported files, outage/retry, false positives, appeals and malicious embedded instructions. |
| Information exposure | Search, public profiles, preview/cache/email, file links and location data obey current approved revision and audience permissions. |
| Billing/rewards | Trial/free/paid transitions, canceled renewal, subscription failure with ongoing jobs, idempotent rewards and collusion/refund handling. |
| Operations | Accessible mobile/keyboard flows, administrator MFA/authorization, backup restore, reconciliation exceptions, moderation/support backlog and approved load targets. |

Use focused JUnit/MockMvc/database tests and a small browser suite rather than a custom framework. Financial/security behavior warrants meaningful regression tests; static document changes do not require application tests.

## Launch decision

Launch requires accepted feature reviews, successful end-to-end and recovery evidence, resolved payment/identity/moderation vendor onboarding, funded reserves and support, published terms/privacy/service rules, approved marketing spend, and explicit authority for production changes. No “scam-proof,” guaranteed-yield or guaranteed-provider-safety claims are permitted in product copy.

After launch, monitor matches, completed jobs, contribution, renewal/churn, refunds/chargebacks, moderation and support capacity. Expand rewards and advertising only under the approved budget and operating thresholds.

## Private development authorization and current foundations

The [complete sandbox scope S1](agent-work/changeo-implementation/sandbox-scope-contract.md) records explicit authorization for private synthetic development before Gate0, with real money disabled. Current [identity contract F1](agent-work/changeo-implementation/foundations-contract-F1.md) and [protected-transaction/security contract F2](agent-work/changeo-implementation/foundations-contract-F2.md) apply to later callers. Advisory permission decisions never replace a resource owner's membership, moderation, terms, billing or money checks. Protected writes use the same caller transaction and current identity locks through commit; historical legal-payer rights are preserved.

The local foundation provides contact verification, authentication/recovery, preferences, guardian/consent exercises and MFA-protected identity administration. Synthetic evidence never certifies production eligibility. [Runtime instructions](sandbox-runtime.md) describe local tools, private token/factor files and loopback operation. Foundations and F3 are independently accepted; moderation awaits its separate independent review; Gate0, vendors, production policies, public deployment and real money remain unapproved. Current execution uses GPT-6.1 Sol/high; historical sealed planning/checkpoint/routing records remain unchanged.

## Private synthetic moderation and publication

The foundation and focused moderation roles are independently accepted under [F3](agent-work/changeo-implementation/foundations-contract-F3.md). The current moderation implementation is readying an independent review under [M1](agent-work/changeo-implementation/moderation-contract-M1.md), [M1A](agent-work/changeo-implementation/moderation-contract-M1A.md), [M1B](agent-work/changeo-implementation/moderation-contract-M1B.md), [M1C](agent-work/changeo-implementation/moderation-contract-M1C.md) and [M1D](agent-work/changeo-implementation/moderation-contract-M1D.md). This does not close Gate0 or certify production content, vendors or capacity.

The local `/moderation` workbench saves guarded private drafts, queues exact immutable revisions, retains an unchanged approved revision during edits, and exposes review/retry/report/appeal states. `/content` retrieves only currently approved and audience-eligible synthetic examples; detail, search, preview, notification projections and downloads use current owner checks. A committed draft that was not submitted explicitly says it still needs review. Current workbench owners cover request-body and private-message fixtures only; the marketplace workflows and their actual authored surfaces remain later dependencies.

Canonical workbench resources, membership, revisions and publication pointers are separate from moderation reference barriers, immutable review snapshots, decisions, tasks, cases and ID-only signals. Installed trusted owners lock/revalidate canonical bindings after current identity authorization. Submission joins the owner's transaction before canonical locks; a downstream caught failure still rolls back that whole submission. Background results never lock canonical workbench records. Every positive automated/manual/appeal/label-correction decision records a durable activation intent; a fresh identity-first transaction changes the owner's pointer and completes its signal/task together. Files owns each raw-read transaction and rejects an outer caller transaction; its current bridge authorizes before bounded immutable byte copying. The independent test-owned canonical bridge demonstrates this boundary without workbench records; future real owners still require their own concrete integration tests.

Only an explicit local opt-in enables deterministic review fixtures. Arbitrary or invalid output holds for human review, outages retry, and unsupported files stay quarantined. Operator review requires the focused current role, MFA and fresh authentication; metadata audit requires its own role and MFA. Human warnings/strikes are reversible evidence and do not automatically restrict identity or move money. Restricted authors retain controlled status/help/appeal access. Production Spanish model performance, legal content policy, privacy processing, retention and support capacity remain unverified.
