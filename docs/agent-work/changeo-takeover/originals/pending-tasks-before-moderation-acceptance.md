# Changeo pending tasks and takeover handoff

Updated: 1 October 2026. Workspace: `/home/paradis/changeo`.

## Start here

Implement the complete Changeo website described in the source documents, in the accepted delivery order. This file is a handoff and task index; it does not replace the complete requirements, contracts or independent reviews.

**Current state:** accounts/permissions and their F3 extension are independently accepted. Moderation has partial corrections but its actual independent verdict is **changes_requested**. All subsequent product bundles remain pending. The overall goal is **blocked**, incomplete, because the required coding-agent route repeatedly returns `503` before the retained developer can execute work. The latest error is `a43eb49a1a4a72d6-EZE`, at `2026-10-01 21:54:27 UTC`. This is a coding-agent error, not evidence of a Mercado Pago API error.

Private synthetic development before Gate0 is authorized by [S1](agent-work/changeo-implementation/sandbox-scope-contract.md). **Real payments and public launch remain disabled.** All six production Gate0 groups remain open.

The next agent should:

1. Read the complete [goal original](agent-work/changeo-implementation/originals/user-goal.md), [AGENTS/environment original](agent-work/changeo-implementation/originals/user-agents-and-environment.md), amendments in that originals directory, and current applicable workspace policy.
2. Read [the current goal checkpoint](agent-work/changeo-implementation/GOAL-CHECKPOINT.md), [blocker audit](agent-work/changeo-implementation/blocker-audit.json), [moderation coordinator disposition](agent-work/changeo-moderation/coordinator-report.md), and [actual independent review](agent-work/changeo-moderation/reviewer/report.md).
3. Read [README](README.md), the complete relevant documents below, [original planning requirements](agent-work/changeo-planning/requirements.md), and [planning checkpoint](agent-work/changeo-planning/CHECKPOINT.md) before planning a feature.
4. Inspect the current workspace and native agent/goal state. Reconcile retained roles and any pending dispatch before resuming. Do not infer current runtime health from historical logs.
5. Resume the moderation correction described below when the authorized route can execute useful work. Require independent acceptance before starting profiles/listings.

Some high-level documents still describe moderation as awaiting review. The actual review has already returned **changes_requested**; use the detailed checkpoint/review evidence for execution status.

## Immediate pending work: moderation

Complete requirements are in the [original correction brief](agent-work/changeo-moderation/briefs/corrections-MFR1-MFR3.md) and [remaining-work brief](agent-work/changeo-moderation/briefs/remaining-MFR-postgres-assertions-MFR3.md). The latter contains historical dispatch wording; current authority and lifecycle come from the latest goal checkpoint and user instructions.

- [ ] **MFR3:** add deterministic actual PostgreSQL tests for both applicable orders of file-byte reads versus current identity restriction, Sensitive/Adult preference withdrawal, and private conversation/resource membership removal.
- [ ] Prove a permitted read retains current identity/canonical locks through byte materialization, so the conflicting mutation cannot commit first. Prove a read behind a committed denial returns no bytes. Exercise the installed trusted bridge once per read; retain existing recall races and release test latches/executors reliably.
- [ ] Run affected checks after the new tests, then the full current Maven verification with actual PostgreSQL Testcontainers. Capture complete logs and underlying exits; compilation alone is insufficient.
- [ ] Regenerate cumulative original-baseline patches, current source fingerprints, preservation evidence, developer report/checkpoint/notes and final artifact seals. Preserve prior reports and failed logs before replacing any current delivery artifact.
- [ ] Release only owned app/browser/test resources and produce the durable corrected handoff.
- [ ] Have the same independent final reviewer inspect the entire cumulative patch, correction behavior, new race evidence and preservation. Only that reviewer can accept moderation.
- [ ] Root/coordinator verifies actual acceptance and accepted dependencies, then releases profiles/listings work.

Already saved, **not independently accepted as a corrected feature**:

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

## Workspace, runtime and role ownership

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
