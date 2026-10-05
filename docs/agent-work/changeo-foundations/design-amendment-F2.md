# F2: resolved authorization and security design

Amends plan.md F1 and addresses PR1–PR5 in plan-reviewer/report.md. Root must accept shared operation/context refinements; same plan reviewer must confirm readiness before product code. S1, complete originals/baseline, foundation ownership and production gates remain.

## PR1: protected commit

IdentityAccess exposes advisory `PermissionDecision decide(PermissionRequest)` and own audience-safe `AccountAccessSnapshot snapshot(AccountId)`, plus `PermissionDecision requireForUpdate(PermissionRequest)` for protected mutation. Mutation guard requires already-active caller-owned PostgreSQL transaction; absent transaction or denial throws typed exception. It never uses REQUIRES_NEW or exposes entities/repos/lock handles. Resource owner validates its resource, calls guard and commits protected intent/resource in that same transaction.

Identity acquires exclusive PESSIMISTIC_WRITE locks on every involved stable account row in ascending UUID order, then applicable immutable guardian-link rows in stable ID order, then consent rows in stable ID order; fresh facts evaluated after locks. Every conflicting identity transition including restriction insertion, new link/consent, token/security mutation and role removal uses stable accounts first in the same order. This covers absent-row phantoms. Link participants immutable; preliminary lookup to find account set must be reloaded/validated after locks. Complete request/account pair supplied before guard; no contrary nested lock acquisition. DB uniqueness handles concurrent duplicates.

Serialization point is protected transaction commit: action committed first becomes existing obligation; revocation committed first denies later protected intent. No external side effect is protected outside this transaction; later async/money owners add their own revalidation/reconciliation, never cache decision as permanent authority. Real PostgreSQL two-transaction latch/barrier tests assert both valid orders, denial/rollback after winning revoke/restriction/role removal and absent-transaction failure. Owner intent used to prove atomicity is test-only, not product job/money.

F2 lock-set clarification: include discovered current guardian account even for minor self-drafts (actor/subject alone insufficient). Establish complete account set before locking. Reload relationship after account locks; if relationship/account set changed, deny or retry from fresh transaction, never append new accounts out of global order.

## PR2: MFA provisioning, key and sessions

Initial privileged account/factor comes only from deliberately invoked opt-in local synthetic operator provisioning tool. Generate unique random factor per account and private enrollment material; no shipped shared/default credentials. `/admin/mfa` verifies already-enrolled factor only; password-only HTTP cannot enroll/display/replace/remove it. Website factor recovery/replacement unavailable; explicit local operator reprovisioning records audit, advances security epoch and invalidates sessions. No production recovery policy inferred.

Persist factor encrypted with standard authenticated encryption under generated external owner-private key file outside DB/source/logs in local runtime/tmp. Explicit key path; reuse key on restart. Missing/invalid/wrong key fails closed for startup with enrolled factors/privileged actions; never silently generates replacement key/factor or disables MFA. Exclude keys/enrollment/token material from snapshots/general logs/deliverable. Standard Java crypto may implement bounded RFC6238 using official vectors, no custom cryptographic primitives.

TOTP technical synthetic settings: 30-second steps, +/-1 step window, persisted highest-used counter. Account/MFA locks atomically consume advancing counter before framework session step-up, preventing concurrent/restart replay. Inject clock; bounded five failed attempts per actor/source per minute. Rotate session ID on step-up; session MFA/fresh-auth bound to security epoch and 10-minute local validity. Security mutations require current role, unexpired factor step-up and password reauthentication within same bounded fresh window. Role/factor/password changes advance epoch. Reset cannot remove MFA. Audit excludes raw secret/code/password/token. Technical settings are sandbox inputs, not production promises. Test password-only access/enrollment denial, reset preserving factor, parallel/restart replay, key failure, stale epoch/fresh-auth and material nonexposure.

## PR3: tokens and authentication epoch

Token at least256-bit cryptographic random, hash-only in DB, immutable account+purpose+generation bound. Contact currently immutable .test; future contact change increments contact generation. Recovery binds security/recovery generation. Issue/resend serializes account and supersedes earlier same-purpose tokens. Consume account lock before token lock, atomically checks hash/account/purpose/generation/expiry/unconsumed and writes intended effect. Reset advances security/recovery generations, invalidates all outstanding recovery tokens and sessions; simultaneous consumption has one effect. Contact verify derives target from hashed token and authenticated owner, never form-selected account. Anonymous recovery token possession only authorizes its bound account reset.

Local filesystem-only mailbox, directory/files owner-private and runtime-ignored, no HTTP inbox/general logs. Delivery failure stays visible pending/retry, never marks verified/reset. Raw token only private delivery/POST body; safe no-store/no-referrer, no token query redirects/access logs/audit. Generic recovery acknowledgement avoids enumeration. Synthetic technical inputs verification TTL30min/recovery15min, injectable clock and bounded register/login/recovery/resend limits; no retention/legal policy inferred. Persist security epoch and validate framework session epoch each request; old ordinary/MFA sessions rejected/cleared after password/factor/role changes or required security revocation. Guardian/restriction facts remain fresh independently. Tests wrong purpose/account/contact, expiry, parallel token, second prior reset token invalid, all sessions invalid and delivery isolation/failure.

## PR4: exact request and matrix

F2 anonymous-request clarification supersedes the signature below: PermissionRequest(Optional<ActorId>, Optional<SubjectId>, Capability, Optional<JobContext>). Authenticated requests require both IDs present; anonymous requests have both absent and only BROWSE_GENERAL/VIEW_SENSITIVE may accept them. One ID absent/one present is invalid. Actor remains framework-principal derived. No synthetic anonymous account is created.

PermissionRequest(server-trusted authenticated ActorId, SubjectId, Capability, Optional<JobContext(jobId, fulfillmentMode)>). HTTP derives actor from authenticated principal, never submitted ID/role/evidence. Unknown account/contradictory context/missing required context denies. No-job capabilities reject supplied job context. Job owner validates actual existence/participants/mode in same own transaction before identity mutation guard; foundation consent exercises visibly synthetic reference IDs, no actual job claim. Snapshot only own coarse scoped status/preferences; no raw evidence/secrets/other contact, advisory only.

C=verified synthetic contact; A=established adult evidence; M=established minor evidence; G=current verified adult/contact/guardian authority; P=separate synthetic legal/partner provider eligibility. Optional badge never provides these/KYC. New participation denies actor or subject restriction/revoked authority. Public approval pending regardless synthetic scope. Exact capability matrix follows.

| Capability | Actor/subject/evidence | Context and additional owner boundary |
| --- | --- | --- |
| BROWSE_GENERAL | Anonymous or self | No job; public approved general only |
| VIEW_SENSITIVE | Anonymous or self, eligible preference | No job; owner warning/policy/approval |
| VIEW_ADULT | Self C+A, explicit adult preference | No job; restricted/minor/unknown denied; owner moderation |
| DRAFT_REQUEST | Self C+A or self C+M with G and explicit synthetic age-appropriate consent evidence | No job; private draft, restricted denied |
| DRAFT_OFFER | Self C+A with provider eligibility or self C+M aged16–17 with G+P | No job; under16/restricted denied; caller billing/content |
| NEW_REQUEST | Adult self C+A or G acting for minor C+M | No job; owner resource/moderation |
| NEW_PROVIDER_PARTICIPATION | Adult self C+A+provider eligibility or G for16–17 C+M+P | No job; under16 denied; caller KYC/billing/service |
| SEND_MARKETPLACE_MESSAGE | Adult self C+A or G acting for minor C+M | Optional job; owner conversation membership/moderation |
| ACCEPT_AGREEMENT | Adult self C+A or G for minor C+M | Required job/mode; LOCAL minor needs job consent; actual owner terms/participants |
| SYNTHETIC_FUNDING_ELIGIBILITY | Same as agreement | Required job/mode; prospective payer actor; real money always disabled, owner financial/KYC rules |
| GRANT_IN_PERSON_CONSENT | G acting for minor C+M | Required LOCAL job; current link; provider/legal rules still apply |
| ACCESS_EXISTING_OBLIGATION | Authenticated self only | Required job/mode; controlled route, owner historical membership; retained despite restrictions/expired evidence/revocation |
| ACCESS_SUPPORT | Authenticated self only | No job; controlled route even restricted/unverified, no unrelated subject access |
| ACCESS_FINANCIAL_RIGHTS | Authenticated self only | Optional job; controlled assistance only, no balances/writes, retained rights |
| ADMIN_IDENTITY | Authenticated self, current identity-admin + MFA/fresh password auth | No job; separate identity-owned target validation |
| ADMIN_RESTRICTIONS | Authenticated self, current restriction-admin + MFA/fresh password auth | No job; separate target validation |
| ADMIN_AUDIT | Authenticated self, current audit-reader + MFA | No job; authorized bounded evidence audience |

PermissionDecision prospective legalPayerId Optional only for ACCEPT_AGREEMENT/SYNTHETIC_FUNDING_ELIGIBILITY, where actor is contracting/paying account; absent elsewhere. Never overwrite historical agreement/credit payer. Retained access actor=subject; resource owners independently preserve historical payer/participant despite current guardian relinking. Later controlled existing-message workflows combine existing obligation decision with own resource/support rules, not unrestricted new-message grant. Existing snapshots are not cached authorization. Tests actor spoof/cross subject/context/expired evidence with retained access/unrelated guardian rights/relink without historical transfer.

## PR5: guardian and consent transitions

One pending/verified guardian link per minor; guardian may manage several synthetic minors. PostgreSQL partial unique minor constraint for PENDING/VERIFIED, immutable guardian/minor IDs, reject self links/wrong ages. Request only PENDING. Separate guardian adult/contact evidence, relationship authority and minor age/consent synthetic evidence required. Current identity-admin MFA/fresh-auth transitions PENDING->VERIFIED or REJECTED using expected version/evidence; own guardian/minor or authorized admin may revoke PENDING/VERIFIED. Restrictions do not trap own revocation/support. REJECTED/REVOKED terminal, stale review never revives; new request gets new ID/generation/evidence.

Consent immutable link ID/generation+synthetic job ID+LOCAL mode. Guardian only grants/revokes own verified link via fresh guard; pending cannot grant. Link revocation invalidates active linked consent in same locked transaction and preserves history/audit. Replacement link requires new consent. ACTIVE->REVOKED terminal; new grant fresh row/generation; unique active link/job/mode. Added POST /account/guardian/{id}/consent and /account/guardian/{id}/consent/{consentId}/revoke, deriving actor/current own link. Support handles unavailable authority without unrelated private data. Tests duplicate/self/age/pending, concurrent approve/revoke, stale version, replacement consent, wrong job/mode, preserved records/rights.

## Acceptance additions

F-A1–F-A8 plus all PR1–PR5 tests. Runtime safety evaluates effective resolved profile/binds/live-money before HTTP, including env/CLI overrides and every app/management listener; missing/ambiguous bind denied, explicit loopback default. Java25/Maven/real PostgreSQL/Testcontainers/browser evidence still required, unavailability remains honest blocker. No coordinator/reviewer writes product code.
