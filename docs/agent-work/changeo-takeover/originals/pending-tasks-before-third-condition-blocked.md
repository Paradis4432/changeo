# Changeo pending tasks and takeover handoff

Updated: 1 October 2026. Workspace: `/home/paradis/changeo`.

## Start here

Implement the complete Changeo website described in the source documents, in the accepted delivery order. This file is a handoff and task index; it does not replace the complete requirements, contracts or independent reviews.

**Current state after takeover:** accounts/permissions, F3 and original cumulative moderation are independently accepted. All MFR1–MFR3 corrections are resolved. Profiles/listings/discovery is partially implemented but **unaccepted**, currently held by renewed coding-agent `503` failures. Same coordinator `/root/profiles_listings` and developer `/root/profiles_listings/profiles_builder` are retained/interrupted; final reviewer not yet dispatched because delivery is incomplete. P1/P1A shared moderation integration and additive public identity badge behavior require independent acceptance on the profiles final state. Later bundles remain pending. Overall continuation goal is active and incomplete in thread `01a0f959-d9b3-7040-a5bd-32fd5122ccf6`. Current execution authority/evidence is the [takeover ledger](agent-work/changeo-takeover/GOAL-CHECKPOINT.md), [profiles blocked handoff](agent-work/changeo-profiles/coordinator-report.md), [accepted original moderation verdict](agent-work/changeo-moderation/takeover/reviewer/report.md) and [moderation handoff](agent-work/changeo-moderation/takeover/coordinator-report.md). Moderation developer and reviewer verification passed 161 executions including 56 actual PostgreSQL container executions; root checked 143 source fingerprints/40 reviewer seals at release. Profiles earlier full verification passed 276 executions, including 111 container executions; 60 later canonical executions and browser flows pass, but latest combined verification/restart/delivery/review remain missing. User expressly approved replacing original inaccessible roles; current profiles provider failure does not authorize another replacement. Original pre-takeover index is preserved [here](agent-work/changeo-takeover/originals/pending-tasks-before-moderation-acceptance.md).

**Historical handoff:** original thread was blocked by coding-agent route `503`, latest `a43eb49a1a4a72d6-EZE` at `2026-10-01 21:54:27 UTC`. That failure is not a Mercado Pago API error. Historical role handles and failed logs below are preserved evidence, not current execution ownership.

Private synthetic development before Gate0 is authorized by [S1](agent-work/changeo-implementation/sandbox-scope-contract.md). **Real payments and public launch remain disabled.** All six production Gate0 groups remain open.

The next agent should:

1. Read the complete [goal original](agent-work/changeo-implementation/originals/user-goal.md), [AGENTS/environment original](agent-work/changeo-implementation/originals/user-agents-and-environment.md), amendments in that originals directory, and current applicable workspace policy.
2. Read [the current takeover checkpoint](agent-work/changeo-takeover/GOAL-CHECKPOINT.md), [accepted moderation handoff](agent-work/changeo-moderation/takeover/coordinator-report.md) and [actual accepted independent review](agent-work/changeo-moderation/takeover/reviewer/report.md). Original implementation checkpoint, blocker audit and changes_requested review remain historical evidence.
3. Read [README](README.md), the complete relevant documents below, [original planning requirements](agent-work/changeo-planning/requirements.md), and [planning checkpoint](agent-work/changeo-planning/CHECKPOINT.md) before planning a feature.
4. Inspect the current workspace and native agent/goal state. Reconcile retained roles and any pending dispatch before resuming. Do not infer current runtime health from historical logs.
5. When the configured route can execute useful work, resume the same profiles/listings coordinator from [current feature handoff](agent-work/changeo-profiles/coordinator-report.md) and [assignment](agent-work/changeo-takeover/profiles-coordinator-brief.md). It resumes the same builder: HTTP guardian/file/audience coverage, latest full verification, packaged restart, truthful docs, complete durable delivery, then fresh separate independent final reviewer. No active product writer/pending duplicate dispatch. Require profiles/P1/P1A independent acceptance before conversations/agreements/jobs.

Some high-level documents still describe moderation as awaiting review. Current takeover independent review is **accepted**; original **changes_requested** review is preserved and all its findings are resolved. Use the current takeover evidence for execution status.

## Completed takeover work: moderation

The checklist below is now satisfied by the [accepted cumulative review](agent-work/changeo-moderation/takeover/reviewer/report.md). Historical detailed failure/progress evidence remains preserved; next active bundle is profiles/listings/discovery.

Complete requirements are in the [original correction brief](agent-work/changeo-moderation/briefs/corrections-MFR1-MFR3.md) and [remaining-work brief](agent-work/changeo-moderation/briefs/remaining-MFR-postgres-assertions-MFR3.md). The latter contains historical dispatch wording; current authority and lifecycle come from the latest goal checkpoint and user instructions.

- [x] **MFR3:** deterministic actual PostgreSQL tests for both applicable file-byte orders versus restriction, Sensitive/Adult preference withdrawal and private membership removal.
- [x] Current identity/canonical locks retained through bytes; conflicting mutation ordering and no-byte denial demonstrated; bridge once per read; recall races and latch/executor cleanup preserved.
- [x] Focused eight races and full actual PostgreSQL/Testcontainers verification; complete logs/true exits retained.
- [x] Cumulative original-baseline/F3-separated patches, fingerprints, preservation and durable role reports/checkpoints/notes/seals regenerated without erasing failures.
- [x] Owned runtime released; corrected handoff saved. Assigned PostgreSQL/Podman retained.
- [x] Separate replacement final reviewer, expressly user-authorized after old role proved inaccessible, independently accepted entire cumulative patch and all original findings.
- [x] Root read actual acceptance, rechecked source/review seals and released profiles/listings.

Earlier partial progress below is historical; these changes are now independently accepted within the complete cumulative feature:

- **MFR1:** worker changes preserve recoverable approval through transient activation failures and support bounded, deliberate recovery without duplicate publication.
- **MFR2:** structured result validation rejects contradictory machine outcomes and human-only reason provenance.
- **Focused evidence:** 20 validator cases pass; three real PostgreSQL correction regressions pass. The transaction-precondition errors in the first attempted green run were corrected using the existing transaction helper. Do not replay unchanged focused checks solely to format a report; the final full suite remains required.

The four partial files are [ModerationWorker.java](../src/main/java/ar/changeo/moderation/ModerationWorker.java), [ReviewResult.java](../src/main/java/ar/changeo/moderation/ReviewResult.java), [ModerationPostgresTest.java](../src/test/java/ar/changeo/moderation/ModerationPostgresTest.java), and [ReviewValidationTest.java](../src/test/java/ar/changeo/moderation/ReviewValidationTest.java). Preserve their current contents. Older test snapshots predate the solved transaction assertions and must not be restored over them.

Evidence:

- [Focused PostgreSQL log](agent-work/changeo-moderation/developer/logs/MFR-pg-green.log) and [exit 0](agent-work/changeo-moderation/developer/logs/MFR-pg-green.exit).
- [First affected check](agent-work/changeo-moderation/developer/logs/MFR-green.log), underlying exit 1: 20 validator passes and three historical transaction-fixture errors. Preserve this failed run.
- [Root final preservation check](agent-work/changeo-implementation/logs/continuation-turn3-final-check.json): 36 seals match; 143 reconciled source files unchanged; 139 initial fingerprints unchanged and four owned correction files changed.
- [Current evidence seals](agent-work/changeo-moderation/logs/coordinator-blocked-artifacts.json).
- [Latest provider failure](agent-work/changeo-moderation/logs/current-continuation-provider-attempt-3.json).
- [Exact technical resume note](agent-work/changeo-implementation/moderation-review-blocker.txt).

The original 121 passing executions, including 45 actual PostgreSQL Testcontainers executions, precede correction acceptance. They do not prove MFR1–MFR3 resolved. An older exhaustive evidence check retained exit 1 for a mutable Podman API log append; its sealed prefix was preserved. Do not rewrite that history as complete hash equality.

## Remaining product tasks, in order

These bundles are still unaccepted. Current synthetic moderation workbench examples do not implement the real marketplace owners or their journeys.

| Order | Pending bundle | Required work and prerequisite |
| --- | --- | --- |
| 3 | Profiles, offers, requests and discovery | After accepted moderation: provider/customer profiles, portfolios, coverage/availability and correctly scoped badges; **Ofrezco/Necesito** staged forms; freeform permitted service names; draft/preview/publish/edit/pause/close/report/share; approved public revisions; PostgreSQL text search, tags, coarse location and local/remote/shipped filters; responsive public pages and clear empty states. |
| 4 | Conversations, agreements and jobs | After accepted listings: private participant conversations, polling, exact-revision message delivery and attachments, notifications/report/block; moderated public questions/comments; competing proposals with one selected provider; versioned quotes and bilateral acceptance; jobs, deadlines, progress/feedback, final delivery and completion; completed-job reviews with moderation/appeals. Preserve history and obligations when listings close or permissions change. |
| 5 | Funding, final release, disputes, refunds and customer credits | After jobs and accepted financial contracts: full-price upfront funding only after both parties accept the same revision; immutable funded terms; final-only release; separate job/release/bank-payout states; persisted intent/idempotency/authenticated callbacks/reconciliation; serialized competing money operations; original-source and explicitly elected backed-credit refunds; legal-payer/guardian ownership; atomic mixed-source credit funding/refunds, deterministic rounding and cumulative caps; dispute/chargeback/recovery workflows. S1 permits synthetic tests only; real Mercado Pago integration requires approved capability/account/contracts and authorized sandbox access. |
| 6 | Provider subscriptions and initial loyalty | After accepted payments/billing contracts: free/trial/paid/past-due/canceled entitlements, renewal consent, cancellation, applicable receipts/invoices, subscription failure and ongoing job rights; approved subscription credits and achievement badges with idempotent anti-abuse rules. Customers have no platform subscription. Production prices/durations/thresholds remain unresolved. |
| 7 | Support, integration and launch preparation | Complete founder/admin queues for identity/guardian exceptions, moderation/appeals, disputes/refunds, payment/reconciliation exceptions and billing; least privilege/MFA/audit; settings/privacy/data rights/retention integration; durable notifications/jobs; operational runbooks, backups/restore, recovery, monitoring and approved capacity checks. Perform full integration, preservation, responsive/accessibility and permission verification. |
| 8 | One public production launch | Only after every production Gate0 group, launch bundle, partner/legal/operational gate and final acceptance passes, with separate explicit production launch authority. No public prototype or automatic deployment. |
| 9 | Approved loyalty and acquisition expansion | Postlaunch evidence and approved funding: earned visibility benefits, later cash bonuses, substantial paid acquisition in measured tranches, recruitment/retention/attribution and operating limits. No spending or outreach follows from this handoff. |

Acceptance must cover both example journeys: reusable 3D-printing offer and an unusual posted request through final settlement; all three fulfillment modes; same-revision agreements; concurrent money operations and unknown outcomes; mixed-source/one-cent refunds; guardian/revocation and ongoing rights; all-surface exact-revision moderation/quarantine; billing transitions; admin MFA; restore and support/reconciliation recovery. Use the complete [delivery acceptance matrix](delivery.md#acceptance-matrix) and [financial cases](payments.md#required-financial-acceptance-tests), not this abbreviated index alone.

## Production Gate0 and unresolved decisions

All groups in [Gate0](delivery.md#gate0-feasibility-before-product-implementation) remain open:

- [ ] Business/entity, Argentina/ARS account eligibility and approved custody arrangement, with written partner capability evidence and professional decisions.
- [ ] Financial operations: final-release terms/holding limits, processing-cost subsidy/taxes, refunds/chargebacks/reserves, backed customer credits, KYC and reconciliation.
- [ ] Age/identity/content: validated guardian authority and minor eligibility, service/content policy, production identity/moderation providers and processing terms.
- [ ] Privacy/records: purposes, access, sensitive data, retention, international processing, rights/deletion and required financial/case records.
- [ ] Economics/operations: approved subscription/free/trial/reward values, subsidy exposure, reserve funding, costs, support hours/targets and realistic solo capacity.
- [ ] Runtime/growth: selected host/region and services, resource/capacity budgets, marketing funding/tranches and measurement thresholds.

Existing questions are in [questions.md](questions.md); research and question-ID mapping are in [gate0-research.md](gate0-research.md). Record new unanswered questions there and in the relevant task `.txt` notes. Do not invent missing answers.

Already supplied: Mercado Pago; `100$ a month` (USD comparison provisional); founder alone, regular hours; public API; personal checking account proposed now, Changeo account later. Do not repeat these questions. The personal-account proposal is not approved custody. No new founder product answer is needed to finish the current synthetic moderation correction. Provider route recovery is the immediate technical prerequisite.

## Workspace, runtime and historical role ownership

Current ownership is in the [takeover ledger](agent-work/changeo-takeover/GOAL-CHECKPOINT.md). Original root native thread is idle; original developer is failed/systemError and reviewer notLoaded/completed. Canonical/host-ID followups cannot resume them in this root. User authorized recreated Sol/high roles; moderation replacement roles completed/interrupted, profiles/listings fresh coordinator owns next work. Do not follow historical same-handle resume instructions as current authority.

Existing application uses Java 25, Spring Boot 4.1.1, Maven wrapper, MVC/Thymeleaf/Security/JPA/Flyway/PostgreSQL. Keep one deployable application and feature ownership; no SPA/native apps/distributed replacement. Revalidate supported compatible versions when required implementation work starts.

Git is user-approved, **unborn `master` with no HEAD commit**. The dirty/untracked workspace is the real baseline. Preserve it; do not reinitialize Git, reset/stash/restore from HEAD or manufacture a clean base. The original moderation baseline contains 289 files; accepted F3 supplement contains 87 source files and six evidence seals. Detailed preservation and separately attributed F3 changes are in the feature artifacts.

Last safe runtime observation, `2026-10-01 21:48:49 UTC`: PostgreSQL PID `1730365` on `127.0.0.1:55432`; private Podman API PID `2094717`, socket `/tmp/changeo-podman/podman.sock`, native session `94126`; no app on port 8080. Historical reviewer app `2287567` was stopped. Revalidate identity/ownership/current handles before runtime operations. See [sandbox-runtime.md](sandbox-runtime.md) and [runtime evidence](agent-work/changeo-moderation/logs/current-continuation-turn3-runtime.json). Never print, copy or snapshot `.runtime` passwords, factors, mailbox tokens or enrollment material.

Retained moderation roles:

| Role | Native name | Host ID / last state |
| --- | --- | --- |
| Feature coordinator; sole nested dispatcher | `/root/changeo_moderation` | `01a0f5f8-b744-74e2-92c3-0b15f212bfeb`; completed/interrupted |
| Sole developer, `astra_flash_builder` | `/root/changeo_moderation/moderation_builder` | `01a0f63e-8e08-7402-b9f2-7890bc50902c`; errored/interrupted |
| Independent final reviewer | `/root/changeo_moderation/moderation_final_review` | `01a0f6a6-743f-7c42-aaf2-67e616a4cb8d`; retained, last completed/interrupted |
| Distinct plan reviewer | `/root/changeo_moderation/moderation_plan_review` | `01a0f605-34ea-7211-8a69-9a599b9a2261`; retained |

No active product writer or pending dispatch was present at this handoff. Resume retained roles rather than duplicating writers. Exact native `followup_task` by canonical name previously restored the same coordinator/developer handles; initial roster absence alone did not prove they were lost. If a new session cannot access retained roles, report that actual host limitation and resolve it within user authority rather than silently substituting roles/models.

The original goal belongs to root thread `01a0f433-fd90-7511-905b-e6e5d6440d39`. A file does not migrate a thread-scoped goal or reset its history. A takeover coordinator must verify actual goal ownership/host support and avoid concurrent coordinators or a competing goal/writer. Do not mark the original objective complete to perform a handoff.

## Required workflow and source index

Load `/home/paradis/.agents/skills/astra-flash-orchestrator/SKILL.md` and complete role-relevant references; apply its `references/code-quality.md`, Java coding standards and Java JUnit for relevant testing. Every new feature gets a fresh native coordinator with explicit `model="gpt-6.1-sol"`, `reasoning_effort="high"`; one `astra_flash_builder` developer; a separate fresh Sol/high independent final reviewer. Retain the same feature roles for corrections. A distinct plan reviewer is required for concrete authorization/concurrency/persistence/shared-contract risk; neither coordinator implements or self-accepts. Root owns order/shared contracts/goal lifecycle. Implementers/reviewers do not delegate.

Use current installed guidance and templates, complete file-based originals/briefs, independent source inspection and one product writer. Verify actual model/effort/transport where available; downstream inference attribution remains unverified in saved routing evidence. No alternate model/provider/agent CLI, paid probe or global configuration change is authorized. Chat uses Caveman ultra; Java is conventional and commentless under the shared quality policy.

Source documents: [product/design](product-design.md), [payments](payments.md), [business](business.md), [trust/operations](trust-operations.md), [architecture](architecture.md), [marketing](marketing.md), [delivery](delivery.md). Accepted contracts are in `agent-work/changeo-implementation/`: S1, F1/F2/F3, M1/M1A/M1B/M1C/M1D; feature-local M1C1 is documented in the moderation plan/briefs. Read their complete current contents before relying on a contract summary.

No remote server, real account, production database, partner sandbox or deployment authority is implied. External outreach, registrations, purchases, spending, production changes, staging, commits, merges and pushes require applicable explicit authority. This handoff changes no scope or permissions.
