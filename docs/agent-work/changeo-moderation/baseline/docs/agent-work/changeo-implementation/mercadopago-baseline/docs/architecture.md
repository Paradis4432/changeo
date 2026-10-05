# Architecture and shared contracts

Status: architecture baseline for later bounded feature plans. No application exists yet. Payment, age, vendor and business contracts must pass [Gate0](delivery.md#gate0-feasibility-before-product-implementation) before implementation.

## Stack and deployment shape

Use Java 25 LTS, a supported Spring Boot 4 patch, Maven wrapper, Spring MVC, Thymeleaf with minimal JavaScript, Spring Security, Spring Data JPA, Flyway and PostgreSQL. Pin compatible versions when implementation starts using [Spring's requirements](https://docs.spring.io/spring-boot/system-requirements.html) and dependency management. Java familiarity is an established preference; no demonstrated cost advantage justified changing language.

Start with one deployable application organized by feature ownership and a shared transactional database. Use private object storage for files and transactional email for delivery notifications. Public pages are server-rendered for accessibility and search indexing. No native app, separate SPA, microservice fleet, custom search cluster or distributed message broker is required by current scope.

Use PostgreSQL full-text search, ordinary application services and incremental polling for active conversations. A persistent outbox/job table with bounded retries and safe database claiming handles notifications, moderation and reconciliation. Add dedicated infrastructure only when measured workloads or a concrete requirement justify it.

Host/region is a Gate0 selection using actual price, Argentina latency, Java memory needs, database backup support and operational effort. Use an always-on container, managed PostgreSQL with tested backups/recovery, object storage, TLS, secret management and health checks. Capacity estimates are planning inputs, not verified load limits. Avoid unneeded native-image optimization before measurement.

## Ownership boundaries

| Area | Owned state and decisions |
| --- | --- |
| Identity and permissions | Accounts, contact verification, optional identity status, guardian links/authority, age eligibility and role permissions. |
| Listings and discovery | Offers, requests, approved public revisions, tags, fulfillment coverage, search and availability. |
| Conversations and files | Participant access, message revisions/delivery, private file references, retention and report context. |
| Agreements and jobs | Quote revisions, explicit acceptance, immutable funded terms, progress/feedback and final-delivery workflow. |
| Payments | Funding sources, financial entries, partner identifiers, serialized operations, release/refund/payout, credits and reconciliation. |
| Subscriptions and rewards | Provider entitlements, renewal states, promotional subscription credits, earned benefits and funded cash-reward obligations. |
| Moderation and cases | Policy decisions, exact revision approvals, reports, appeals and support case decisions. |
| Notifications and jobs | Durable delivery intents, safe retries, scheduled work and delivery status. |

Each owner controls its writes and exposes behavior-level operations to callers. Prefer clear responsibilities and traceable failure paths over a blanket ban on useful abstractions. Payment, identity and moderation integrations have justified boundaries; do not build a speculative multi-vendor framework.

## Core records and behavior contracts

- **Account/guardian relationship:** the acting user, legal payer and provider can differ; preserve explicit ownership and authorization.
- **Listing:** offer or request with private draft and approved public revisions. Pausing/deleting public availability must not rewrite associated agreements.
- **Conversation/message/file:** access belongs to authorized participants; approval and delivery refer to the exact revision and attachments.
- **Agreement revision:** immutable scope, amounts/currency, participants, fulfillment, deadlines, evidence and approved release/refund terms. Both parties accept the same revision.
- **Job/progress stage:** execution and feedback state; progress-stage acceptance never invokes payout.
- **Payment operation:** recorded intent and idempotency identity, partner observations, funding allocation and reconciliation outcome.
- **Credit balance/source lot:** monetary liability owned by the legal payer, with atomic reservation/spend/refund and cumulative source limits.
- **Subscription/reward:** commercial entitlement and promotion state, separate from customer principal.
- **Moderation decision/case:** content revision, policy/model version, reason, review status, appeal and responsible operator.

Specific endpoint paths and persistence schemas belong in accepted feature briefs after Gate0; this document intentionally fixes ownership and invariants before provider-specific wire contracts are known. No public third-party developer API is a launch requirement.

## Financial and asynchronous correctness

Database transitions and external partner actions are not atomic together. Persist intent, commit, perform/retry the external operation with a stable key, then reconcile observed outcomes. Authenticate callbacks, reject invalid events, deduplicate legitimate repeats and tolerate ordering differences.

Serializing money decisions per job prevents conflicting payout/refund paths. Unknown outcomes block competing operations. Store exact minor-unit values and currency; refunds are bounded by remaining refundable amounts and per-source cumulative limits. Append financial corrections rather than rewriting previous entries.

Notification and moderation retries are also durable. A task's result applies only to the content revision it inspected. Access control must be enforced at data retrieval and file-link issuance, not only in rendered templates.

## Security and privacy baseline

Use managed framework support for sessions/authentication, CSRF protection, input validation and output escaping. Protect state-changing actions, ownership checks, secrets and session lifecycle. Add rate limits to abuse-sensitive registration, posting, messaging, uploads and review submissions.

Files need type/size limits, quarantine/scanning, private storage, authorized short-lived downloads and safe extraction. Do not preview arbitrary active content or trust a filename extension. Do not expose identity documents, precise locations, payment identifiers or conversation contents in public indexes or logs.

Admin MFA, minimum privileges and an audit trail are required. Moderation output is untrusted structured data; it cannot execute instructions, access administrative tools or move money.

## Verification and operations

Use Spring Boot-managed JUnit Jupiter/AssertJ, MockMvc and PostgreSQL Testcontainers for meaningful boundaries; use a small browser suite for end-to-end accessibility and permission flows. Follow the installed Java/JUnit skills while respecting the chosen framework's supported dependency versions.

Prioritize agreements/permissions, concurrent financial operations, mixed-source refunds, revision publication, attachments, guardian actions and retry/recovery behavior. Do not replace behavior checks with source-token or class-layout tests.

Private staging uses partner sandboxes and test identities where provided. This is technical verification, not the public prototype the user rejected. Exercise backups and restoration, monitor reconciliation failures, job queue age, moderation latency, payment exceptions, error rates and delivery failures. Capacity targets and alert thresholds come from the approved launch forecast and support model.

## Required coding guidance

Every planner, implementer and reviewer loads the current shared policy at `/home/paradis/.agents/skills/astra-flash-orchestrator/references/code-quality.md`. Java work also loads `/home/paradis/.agents/skills/java-coding-standards/SKILL.md` and relevant testing work loads `/home/paradis/.agents/skills/java-junit/SKILL.md`. Record actual reads and validation evidence in role reports. There are no existing project implementations to cite as local reference patterns.
