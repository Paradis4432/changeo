# Delivery, gates and verification

## Current status and scope

The planning program has [independent acceptance](agent-work/changeo-planning/planning-review.md). The product and payment implementation do not. This documentation task saves the accepted direction under `docs/`; no application feature, deployment, external outreach or spending is authorized by the instruction “write to docs.”

Baseline: no application code or usable Git repository was exposed during planning. Protected workspace metadata must not be replaced. Establish a permitted baseline before future code work and preserve any intervening user changes.

## Gate0: feasibility before product implementation

The founder starts from no established business/payment arrangement. An Astra research coordinator prepares evidence and bounded questions; the founder obtains commercial/professional decisions where required. External communications, service purchases and registrations are separate authorized actions.

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

## Ordered delivery bundles after Gate0

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

Bundles are dependency milestones, not permission to build disconnected scaffolding. Feature coordinators may size cohesive end-to-end tasks within them. Preserve the order and accepted shared contracts. Private staging and payment sandboxes are required verification environments, not a second public release or prototype.

## Astra-only execution workflow

The user's explicit Astra-only instruction overrides the installed orchestration skill's default Flash implementer. Use explicit `gpt-6-astra` for root, fresh feature coordinator, separate implementer and separate independent final reviewer. Verify actual host attribution when available; a selected model name alone is not proof of provider routing. Do not silently substitute models or run paid setup probes.

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
