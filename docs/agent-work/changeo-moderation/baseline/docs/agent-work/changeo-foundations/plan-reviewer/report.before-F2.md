# Foundations F1 independent high-risk plan review

Verdict: **changes_requested**. Review date: 1 October 2026. Reviewer task `/root/changeo_foundations/foundation_plan_review`, host `01a0f583-520e-7e40-87f7-9bd14833f9da`; coordinator `/root/changeo_foundations` owns corrections. No implementation, delegation, goal, staging or deployment performed. Only this role's artifacts were written. The later independent final reviewer remains responsible for feature acceptance.

Root accepted F1 for sandbox design in `../../changeo-implementation/foundations-contract-F1.md`, subject to this review. S1 permits the proposed private synthetic application while preserving every production Gate0 group. The proposed ownership, one deployable Java application, moderation-before-public-content restriction and separate money authority are sound. High-risk review is required because persisted authentication, guardian authority/revocation, administrator access and concurrency are shared foundations for later owners. Several requirements are currently guarantees without a sufficiently concrete execution contract. Resolve findings below before the sole developer begins product code; shared operation/context refinements require the existing root contract decision path, not a new user approval ceremony.

## Findings

### PR1 — P1: authorization needs a protected-commit concurrency contract

Location: `../plan.md:19`, `../plan.md:23`, `../plan.md:39`; accepted F1 concurrency boundary.

Reloading current facts and putting guardian mutations in transactions does not prevent this interleaving: a caller obtains `allowed`, revocation/restriction commits, then the caller commits new protected participation using its earlier decision. Optimistic versions on the guardian row alone do not reject a different owner's resource write. The same gap affects concurrent role removal and administrator mutation. The advertised immediate revocation guarantee is therefore underspecified.

Requested correction: distinguish advisory decisions/snapshots from a behavior-level operation that guards a mutation in the caller-owned active PostgreSQL transaction. Minimal shape is `PermissionDecision decide(PermissionRequest request)` for display/read decisions and `PermissionDecision requireForUpdate(PermissionRequest request)` for protected mutation, with a documented precondition of an already-active caller transaction and an exception when absent or denied. Identity owns acquiring stable account/relationship/consent locks and evaluating fresh state; locks remain held through the caller's protected intent/resource commit. Do not implement the guard in `REQUIRES_NEW` or expose repositories/entities/lock handles. Every conflicting identity transition must acquire the same stable affected-account locks, then relationship/consent locks, in one documented deterministic order. Stable account locking must cover insertion of restrictions/new links as well as updates, avoiding a missing-row/phantom loophole. An equivalent simple serialization design is acceptable if the coordinator specifies it before dispatch.

Define the serialization point: an authorized protected intent committed before revocation is an existing obligation; a revocation committed first prevents a later protected intent. Future asynchronous/external owners must define their revalidation/reconciliation separately and cannot cache the decision as permanent authority. No money implementation belongs to this correction.

Verification addition: PostgreSQL tests with two real transactions and coordinated interleavings for guarded action versus guardian revoke/restriction/role removal; assert the two valid commit orders, denial after a winning revocation, rollback/no partial resource intent and failure when a mutation guard is called without an active transaction. Avoid timing sleeps and in-memory tests as concurrency proof.

### PR2 — P1: administrator factor enrollment, recovery and secret lifecycle are unresolved

Location: `../plan.md:23`, `../plan.md:27`, `../plan.md:29`, `../plan.md:41`.

The plan requires completed MFA and a protected secret, but `/admin/mfa` and local bootstrapping do not define how the first factor is provisioned or whether a password-only account can enroll/replace it. Ordinary password recovery could accidentally reset the factor. `last accepted counter` also does not specify atomic advancement, leaving simultaneous valid submissions able to both succeed. These omissions directly affect the requirement that the administrator password alone is insufficient.

Requested correction: document an explicit sandbox provisioning path independent of the web password-only session, such as an opt-in local operator tool that creates synthetic privileged accounts and provisions a unique generated factor through owner-private local material. No default/shared shipped credentials or deterministic production factor. Password-only `/admin/mfa` may verify an already provisioned factor, but cannot display its secret, enroll/replace it or remove it. Password recovery must preserve factor enrollment and clear previous session MFA/fresh-auth authority. Either keep factor recovery/replacement unavailable in this feature with a truthful local-operator route, or specify its existing-factor/fresh-auth and local authority requirements. Do not invent production recovery policy.

Specify protection at rest, key location and restart behavior: generated per-account secret encrypted with an external private local key outside database/source/general logs; missing/wrong key must fail closed rather than generate a replacement factor or disable MFA. Specify bounded TOTP window, rate limiting and atomic persisted counter advancement before granting session MFA. Session step-up must use framework security-context/session handling, protect against fixation, and bind MFA/fresh-auth state to the current account authentication/security version. Security mutations require current role plus unexpired step-up; role/factor/password changes revoke stale authority. Audit successful transitions without secrets or raw codes. These are sandbox technical settings, not unapproved production values.

Verification addition: password-only access to admin data/actions/enrollment/replacement is denied; password reset cannot remove MFA; parallel replay of the same TOTP permits one successful consumption; replay after restart remains denied; missing/wrong key fails closed; role removal and stale fresh-auth deny actions; logs/audit/rendered account views contain no factor material. [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238.txt) requires single-use validation after a successful OTP and protected key access. Hardware key storage is not required for this local synthetic feature.

### PR3 — P1: reset/contact tokens need account-bound atomic effects and session revocation

Location: `../plan.md:29`, `../plan.md:38`.

Purpose/hash/expiry/consumed fields and an acceptance phrase about session invalidation leave important lifecycle choices open: duplicate reset submissions, two still-live recovery tokens, verification for another account/contact, and a previously logged-in session retaining authority after password change. Expiration and single use of one token alone do not close these paths.

Requested correction: bind each cryptographically random token to account, purpose and the relevant contact/security generation; consumption must atomically validate expiry/purpose/generation and update the intended account effect in the same transaction. Define issuance/resend invalidation and ensure a successful password reset invalidates every previously issued recovery token for that account, not merely the submitted row. Persist an authentication/security epoch or equivalent revocation authority; old ordinary and MFA sessions fail on their next request after reset, role/security changes, or other required credential revocation. Contact verification must match the token's account and current contact and cannot verify an arbitrary form-selected account. Preserve managed framework sessions/login/logout rather than creating a parallel authentication mechanism.

Pin the local mailbox access boundary. A filesystem-only, owner-private synthetic delivery channel is a sufficient minimal solution; if exposed through HTTP, specify and test its authorization without creating an anonymous recovery/verification inbox. Tokens must not leak through request/access logs, audit, public links or redirects; reset/verification forms and responses need safe caching/referrer behavior. Delivery failure has an explicit retry/error state and never marks contact verified. Bound login, recovery, resend and MFA abuse with documented synthetic test settings and a test clock.

Verification addition: concurrent same-token consumption causes one effect; wrong purpose/account/contact and expired token are rejected; a second pre-reset token cannot reset again after successful reset; all prior ordinary/MFA sessions lose authority; delivery failure and private mailbox isolation are exercised. [Spring session guidance](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) establishes framework session/context handling, not application-level account credential epochs by itself.

### PR4 — P1: actor/context and retained-rights semantics need an exact capability matrix

Location: `../plan.md:19`, `../plan.md:21`, `../plan.md:40`; accepted F1 job-owner condition.

The request permits actor/subject/job/mode inputs, while the plan only broadly states owner checks and protected-action denials. It does not define which fields are authoritative, which contexts are mandatory, or how support/rights differ from new participation. A blanket age/contact denial could also strand retained rights when evidence expires. A current guardian-derived `legalPayerId` must never overwrite the historical legal payer of an existing agreement or credit.

Requested correction: provide a compact explicit matrix for each exposed capability: permitted actor/subject relationship, required contact/age/evidence/preference, required optional context and restriction/revocation treatment. The HTTP adapter derives the actor from the authenticated principal; submitted actor IDs/roles/evidence are not trusted. Unknown IDs, inconsistent context and missing required job/mode must fail closed. Ordinary self decisions require actor=subject; delegated participation requires current verified guardian authority. Account snapshots expose only audience-safe immutable facts and are advisory, not cached authorization or raw evidence/secrets.

Job owner validates existence/participants/mode under its own resource transaction before using job-specific consent; a synthetic F1 consent exercise must be visibly synthetic and must not assert a fabricated real job exists. A support/financial-rights identity decision permits only controlled assistance/owner routes, even if new-participation evidence is missing or revoked, and never grants unrelated private-resource reads or financial writes. Later owners preserve the historical legal-payer/resource relationship independently of current guardian links. Define `legalPayerId` as a prospective permission result where relevant, optional elsewhere; it is not an authoritative historical money owner. This is a refinement of accepted F1, not authority for identity to own jobs or funds.

Verification addition: actor spoofing, cross-subject access, contradictory/missing job contexts, expired eligibility with retained-rights access, unrelated account/guardian financial-rights denial, and guardian relinking without transfer of historical payer authority.

### PR5 — P2: guardian and consent lifecycle constraints must be explicit before schema dispatch

Location: `../plan.md:21`, `../plan.md:27`, `../plan.md:29`.

The proposed request/review/revoke endpoints and status/version columns do not define the transition graph or uniqueness/cardinality rules. A request must not become verified authority merely by being submitted, and a revoked link must not silently regain old job consent after reapproval or relinking.

Requested correction: specify supported guardian-link cardinality and enforce it with PostgreSQL constraints/concurrency handling; define pending/verified/rejected/revoked states and authorized transitions. Adult verification, relationship authority and minor age remain distinct independent synthetic evidence. Self-links, invalid guardian/minor age roles and client-supplied verified status are rejected. Link approval is a current MFA-protected authorized operator action, not self-claim. Revocation preserves history, prevents new acts, invalidates linked consent, and cannot be undone by replaying a stale review. New authority/link generation requires new job-specific in-person consent. Define who can revoke their own relationship and the controlled support route when authority is unavailable, without granting either party unrelated data access. Constraints should enforce the supported behavior rather than implement speculative multi-guardian machinery.

Verification addition: pending request has no authority; concurrent approve/revoke and stale review cannot revive revoked powers; duplicate/self/invalid-age links fail; consent for a revoked/replaced link or different job/mode is rejected; existing history/support rights remain present.

## Complete-source and independent-inspection evidence

Read the complete `../plan-reviewer-brief.md`, every `../originals/user-*.md` (eight files), `../../changeo-implementation/sandbox-scope-contract.md`, the later root `../../changeo-implementation/foundations-contract-F1.md`, all nine current top-level docs named in the brief, full `../../changeo-planning/requirements.md` and `CHECKPOINT.md`, full `prompt.md`, `../plan.md`, `../CHECKPOINT.md` and `../role-routing.json`. Initial combined outputs were truncated; followed with bounded complete reads. Complete baseline/environment JSON was parsed and inspected: all command fields, tools/blockers and all 271 manifest entries in bounded batches. Repeated Git stdout was verified to be exactly the manifest's untracked paths plus their copied snapshot paths, with the original `?? ` prefixes, no omissions, exit 0 and empty stderr. No duplicate command facts were inferred where unequal.

Independently hashed every original-baseline snapshot: all 271 match. All nine relevant current full docs match their snapshot bytes, so the complete current reads also cover those identical baseline sources. Current root `GOAL-CHECKPOINT.md` differs, as expected for its separately owned changing ledger; no other baseline file changed at inspection. `rg --files` excluding docs/protected metadata finds only `prompt.md`; there is no application implementation or local good code reference to invent. The architecture ownership table and trust permission matrix are the inspected design references.

Guidance attribution:

| Guidance | Complete load | Application in this review |
| --- | --- | --- |
| `/home/paradis/.agents/skills/astra-flash-orchestrator/SKILL.md` | Read | Distinct plan/final roles, corrections before writer, no reviewer implementation/delegation. |
| `/home/paradis/.agents/skills/astra-flash-orchestrator/references/code-quality.md` | Full injected shared policy | Explicit ownership, traceable failure/normal paths, useful behavioral boundaries, substantive tests, commentless conventional code requirement. |
| `/home/paradis/.agents/skills/astra-flash-orchestrator/references/planning.md` | Read | Concrete interface/context and feasible verification requirements. |
| `/home/paradis/.agents/skills/astra-flash-orchestrator/references/review.md` | Read | Independent originals/source inspection, honest verdict and limits. |
| `/home/paradis/.agents/skills/astra-flash-orchestrator/references/routing.md` | Read | Requested and actual Sol/high distinguished from provider attribution. |
| `/home/paradis/.agents/skills/astra-flash-orchestrator/routing.json` | Read | Installed builder route Sol/high/inherited, role ownership unchanged. Initial incorrect `references/routing.json` path returned exit 1; corrected installed root path read fully. |
| `/home/paradis/.agents/skills/java-coding-standards/SKILL.md` | Read | Immutable context/result records, conventional Java/Spring and module ownership; no entities crossing public boundaries. |
| `/home/paradis/.agents/skills/java-junit/SKILL.md` | Read | Focused behavior tests, deterministic concurrency coordination and parameterized permission boundaries. |
| Ponytail full / Caveman ultra | Full injected mode instructions | Shared quality applied; brevity affects chat only, not identifiers or necessary evidence. |

Native host metadata saved by coordinator in `../role-routing.json` identifies actual `gpt-6.1-sol`/`high` and parent `codex-router-signed` transport for this task. Downstream inference provider and per-request Router correlation remain explicitly unverified. No attribution probe or model substitution occurred.

## Factual verification and limits

Local read/inventory/hash commands ran with exit 0; `logs/verification.json` preserves independent command output and exact exits. Baseline Java command exit 0 identifies Java 21.0.10, Maven/psql absent; baseline Docker info exit 1 records socket permission denial; Podman info exit 1 records an unwritable default runtime directory. Those are reused coordinator observations, not newly executed runtime tests. This review did not acquire tools, exercise browser ownership or run unavailable application tests.

Independently opened official compatibility sources on 1 October 2026. [Spring requirements](https://docs.spring.io/spring-boot/system-requirements.html) reports Boot 4.1.1, Java 17–26 and Maven 3.6.3+; [Spring project](https://spring.io/projects/spring-boot/) identifies 4.1.1; [Oracle roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) identifies Java 25 LTS. This confirms the proposed compatibility facts; it does not prove artifact downloads or runnable dependencies. Primary security sources are linked at PR2/PR3, and `logs/web-evidence.md` records the facts without copying entire copyrighted pages. The initial RFC HTML find returned internal errors; the RFC text source was subsequently opened and replay/security sections inspected.

Full Java25/Boot4 Maven build, actual PostgreSQL migrations/restart/concurrency behavior, Testcontainers and browser scenarios remain future builder/final-review checks. Environment investigation within workspace/temp is authorized; none of these missing checks can be replaced by H2, compilation or silently skipped tests. The local PostgreSQL fallback must retain real database verification and explicitly report any unavailable required Testcontainers check. Production eligibility, vendor accounts/custody, real minors/data, retention/legal/commercial policy and production launch remain unapproved and unverified.

S1 startup guard should evaluate effective resolved profile/address/real-money configuration before serving HTTP, default explicit loopback, and cover every enabled application/management listener; test command-line/environment overrides and missing/ambiguous binds. This is an implementation checklist within the existing F-A7 guarantee, not a new shared safety policy or permission expansion.

Next action: coordinator refines these five design areas, obtains root acceptance for mutation-guard/context contract changes, updates the same plan/brief, then resumes this same plan reviewer. No new founder decision is required to resolve these technical findings. Plan readiness remains pending; implementation/final acceptance is not claimed.
