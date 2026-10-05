# Private synthetic application runtime

This is the single Changeo application under [S1](agent-work/changeo-implementation/sandbox-scope-contract.md), [F1](agent-work/changeo-implementation/foundations-contract-F1.md) and [F2](agent-work/changeo-implementation/foundations-contract-F2.md). Production, real people/minors, vendor integrations, customer money, production publication and launch remain gated. Real payments and marketplace workflows remain absent. Foundations and F3 are independently accepted; synthetic moderation awaits its separate independent review.

Use Java 25 JDK (a runtime alone is insufficient), Maven 3.9.11 via the official wrapper, and PostgreSQL 18.0 for the captured local checks. Spring Boot 4.1.1 artifact availability was verified from Maven Central on 1 October 2026; [official compatibility](https://docs.spring.io/spring-boot/system-requirements.html) supports Java 25. Workspace-local tool/cache files are ignored and are not application deliverables. The captured JDK path is `.local-tools/jdk-25.0.4.1+1`; a normal compatible JDK25 installation may be substituted.

From the repository root:

```bash
export JAVA_HOME="$PWD/.local-tools/jdk-25.0.4.1+1"
export MAVEN_USER_HOME="$PWD/.local-tools/maven-user"
scripts/local-db.sh
export CHANGEO_DATABASE_PASSWORD="$(cat .runtime/db.password)"
./mvnw -B -ntp test
./mvnw -B -ntp package -DskipTests
scripts/run-sandbox.sh
```

The local database script expects `.local-tools/postgres/bin`, or `CHANGEO_PG_BIN` pointing to PostgreSQL tools. It initializes a dedicated synthetic database, generates an owner-private random database password and binds `127.0.0.1:55432`. Stop with `"$CHANGEO_PG_BIN/pg_ctl" -D .runtime/pgdata stop`, setting that variable to the actual tools directory. Existing test data persists across restarts; no production migrations are authorized. Application test schemas are separate from normal sandbox state.

The HTTP site binds `127.0.0.1:8080`. Effective non-sandbox profiles, real-money enablement, missing/ambiguous/public application binds and public or unspecified separate management binds fail closed before HTTP startup. Never change the address to expose this app. The `.runtime` tree holds private material; exclude it from backups/snapshots shared with reviewers, ordinary logs and source archives. The scripts use owner-private creation permissions.

Registration accepts invented `.test` addresses only. The local mailbox stores raw single-use contact and recovery codes in owner-private files named by synthetic account UUID and purpose. Database storage is hash-only and bound to account, purpose and generation. Read a particular code privately as the local operator and paste it into the form; there is no HTTP inbox, no external email and no token URL. Contact codes expire after 30 minutes; recovery after 15. Resend supersedes old codes. Failed delivery remains pending and can be retried. Recovery acknowledgements remain generic. Password reset invalidates every prior ordinary/MFA session and preserves the enrolled factor.

## Local administrator provisioning

Choose only the roles needed: `IDENTITY_ADMIN`, `RESTRICTION_ADMIN`, `AUDIT_READER`, `MODERATION_REVIEWER`, `MODERATION_AUDITOR`. This deliberately invoked local tool creates or reprovisions a unique synthetic account/factor, rotates its security epoch and writes unique generated password and Base32 TOTP enrollment material to a new private file. It does not listen on HTTP. The web has no factor enrollment/replacement or role-grant endpoint.

```bash
scripts/run-sandbox.sh \
  --operator-contact=operator@example.test \
  --operator-roles=IDENTITY_ADMIN,RESTRICTION_ADMIN,AUDIT_READER \
  --operator-enrollment=.runtime/operator-enrollment.private
```

No default credentials/factor are shipped. Read the enrollment file privately; enter its password into `/login` and configure the Base32 factor in a local TOTP generator. Preserve `.runtime/mfa.key` on restart; it is an external owner-private AES-GCM key, separate from the database. Missing, invalid or wrong key with enrolled factors fails startup/privileged operations. Reprovisioning requires the existing key; it never repairs a lost key by silently removing MFA. Use a new enrollment path each time. Never copy passwords, keys, factors or codes into reports.

The additive [F3 identity direction](agent-work/changeo-implementation/foundations-extension-F3-brief.md) defines focused moderation eligibility. `MODERATION_REVIEWER` permits the `ADMIN_MODERATION_REVIEW` identity check with current role, unrestricted account, MFA and fresh password. It is intended for guarded review actions and bounded inspected-safe evidence; the future moderation/files owner must independently enforce exact resource, case, audience and byte-safety rules. `MODERATION_AUDITOR` permits `ADMIN_MODERATION_AUDIT` with current role, unrestricted account and MFA, without fresh password; this is metadata eligibility only and grants no raw payload or file access. Each role is separate; neither grants the other, identity/restriction/audit administration or financial authority. Ordinary account eligibility remains separately governed.

For a private review-only operator, use a new enrollment path and `--operator-roles=MODERATION_REVIEWER` with the provisioning command above. Add `MODERATION_AUDITOR` only when both separate roles are required. An existing identity administrator can remove either role through the current identity administration path, advancing the security epoch and invalidating old sessions. A moderation-only operator uses `/moderation/operator/signin` for its focused MFA and recent-password flow, then `/moderation/operator` for review or `/moderation/audit` for metadata. The separate identity dashboard remains forbidden without its own role. F3 is independently accepted. V1/V2 remain immutable; V3 changes only the identity role-name CHECK constraint, retaining existing roles and keys.

TOTP uses RFC6238 SHA1, 30-second steps, a ±1-step window and persisted advancing counter. MFA rotates the session ID and expires after ten minutes. Security mutations additionally require fresh password authentication within ten minutes and the current least-privilege role. Authentication abuse controls are bounded per account/source over a one-minute technical window. These are sandbox inputs, not approved production retention or service promises.

## Synthetic eligibility and guardian exercises

By default the synthetic eligibility adapter is disabled. To exercise it deliberately start with `--changeo.synthetic-evidence=true`. MFA and fresh-password identity administrators can record separate age, provider-policy, provider-KYC, draft-consent or optional-badge evidence for other synthetic accounts. Evidence and guardian authority expire after 24 hours of this technical exercise. No self-approval, public eligibility, legal policy or actual KYC is inferred. Optional badges grant no protected permissions.

Guardian requests start pending. An independent identity administrator reviews distinct adult/contact, minor/draft-consent and relationship evidence. There is one pending/verified relationship per synthetic minor; revoked/rejected history is terminal. Replacing a relationship creates a new ID and requires new per-reference consent. Local consent exercises accept UUID references and expressly certify no real job. A future job owner must validate actual job, participants and mode in the same protected transaction. Guardians control minor messages, contracts and prospective payer identity; under-16 providers remain denied, and 16–17 provider paths require explicit scoped synthetic policy evidence.

Support and rights screens remain available after restrictions/revocation but provide no unrelated private records or money. Future resource owners must check historical participant/payer rights. Coarse account snapshots are advisory and own-account only. All mutation forms retain CSRF protection, server owner checks, escaped output and no-store/no-referrer headers.

## Verification

```bash
export DOCKER_HOST=unix:///tmp/changeo-podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
./mvnw -B -ntp verify
```

The captured rootless Podman API uses only a private temporary Unix socket and temp-owned storage. Start it with `XDG_RUNTIME_DIR=/tmp/changeo-podman podman --root /tmp/changeo-podman-store --runroot /tmp/changeo-podman-run system service --time=0 unix:///tmp/changeo-podman/podman.sock` after making that owner-private runtime directory (`mkdir -p /tmp/changeo-podman; chmod 700 /tmp/changeo-podman`). Ryuk is disabled for this rootless runtime; JUnit owns and stops the PostgreSQL container. Its exposed port binds loopback explicitly. Stop the API afterward and verify no containers remain. This environment can require permission to create loopback sockets and download build artifacts; no host configuration change is required.

`test` runs focused crypto/safety, PostgreSQL transaction/token/guardian/restriction and MockMvc/framework-session tests against the local database. `verify` additionally runs the same security suite in a PostgreSQL Testcontainer and migration repeat/immutability checks. Required checks are never silently skipped when containers are unavailable. See the [developer report](agent-work/changeo-foundations/developer/report.md) for actual commands, first failures, browser evidence, preservation checks and remaining limits.

The reusable browser check uses workspace-local Playwright 1.58.2 and the available cached Firefox executable. Install this QA tool with `npm install --prefix .local-tools/browser playwright@1.58.2 --ignore-scripts --no-audit --no-fund`, then run `node scripts/browser-qa.cjs` while the synthetic app and deliberately provisioned QA operator are available. It reads token/factor files privately, logs assertion results only and closes its browser in `finally`. `node scripts/browser-qa.cjs --restart-check` verifies the last private QA fixture after an application restart. Executable paths can be adapted to another approved local Firefox installation. This tool is not an application dependency.

## Moderation workbench and private artifacts

Start the same loopback application with explicit review and worker opt-ins:

```bash
scripts/run-sandbox.sh --changeo.synthetic-evidence=true \
  --changeo.synthetic-moderation=true --changeo.moderation-worker=true
```

Synthetic identity evidence remains separate from content approval. The workbench currently accepts eligible adult or guardian-authored request/private-message fixtures; self-directed minor drafting and the real marketplace/conversation workflows belong to later canonical owners. `/moderation` saves private drafts; sending for review is a separate visible action. `/moderation/own/{id}` retains truthful unsubmitted, pending, manual, rejected, exhausted and awaiting-eligibility states. Edits get new revision/file identities while the previous unchanged approval can remain visible. Reports and appeals have versioned case ownership; human review can approve/correct/recall, record reversible warnings/strikes, or withdraw evidence. Every delivery still checks current approval and audience, including files. Missing owner bridges deny.

Recognized local text fixtures are `synthetic:clear`, `synthetic:tattoo`, `synthetic:medical`, `synthetic:adult`, `synthetic:prohibited`, `synthetic:doubt`, `synthetic:invalid` and `synthetic:outage`. Tattoos remain General. Medical content is Sensitive; Adult needs established adult eligibility and preference. Underlabelled fixtures hold for explicit correction. Arbitrary text/images hold for human review; unsafe/uninspectable artifacts cannot be manually approved. These fixtures do not establish Spanish production-model accuracy, malware protection or a real content policy.

Technical local bounds: 32 KiB UTF-8 text, four attachments per internal revision, 1 MiB per artifact, one upload per workbench form, 2 MiB HTTP multipart request, 4096 pixels per image dimension and 4 million total pixels. Only bounded UTF-8 plain text and decoded PNG/JPEG are inspected; PDF/STL/opaque documents remain quarantine metadata. Stored paths are opaque UUIDs in owner-private `.runtime/content-artifacts` (directory0700/files0600), outside HTTP static roots, with immutable digests and bounded copy verification. Downloads are attachments with no-store/nosniff; no reusable media grants or active previews. Preserve this private directory with the sandbox database for a restart check; no production retention policy has been chosen.

The existing bounded in-memory limiter admits ten new commands per actor and operation and twenty moderation HTTP mutations per remote source per minute. It trusts the actual remote address, not forwarded headers or form actors; global key cap4096 fails closed. Exact replays do not add artifacts/tasks or reset attempts. Deliberate technical retry advances one generation only. These are synthetic engineering limits; restarting the process resets this limiter and certifies no production abuse capacity. Queues/own resources/search candidates are bounded50, own revision history20, and the worker claims at most four tasks per one-second tick. Each claim has a30-second lease, at most three attempts, and 5/10-second retry delays before exhaustion. Expired claims are recoverable; stale tokens/generations cannot publish.

`node scripts/moderation-browser-qa.cjs` runs the owned local Firefox moderation workflow after deliberately provisioning the QA operator with identity/restriction/reviewer/auditor roles in `.runtime/m1-browser-enrollment.private`. It creates synthetic fixtures through HTML, reads private enrollment/mailbox in memory, emits assertions only and closes both browser contexts. After stopping/restarting only the owned app, `node scripts/moderation-browser-qa.cjs --restart-check` verifies persisted approval/status and file bytes. Private QA fixture/enrollment files must never enter reports/source snapshots. Reprovisioning requires a new enrollment path; no default credentials ship.

In a restricted environment that prevents Mockito self-attachment, the installed test agent can be passed explicitly without changing dependencies:

```bash
./mvnw -B -ntp \
  -DargLine="-javaagent:$PWD/.local-tools/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar" verify
```

Loopback/socket permissions are still required. Required container tests must actually run; no H2 or skipped-container substitute is accepted. See the moderation [developer evidence](agent-work/changeo-moderation/developer/report.md) for the exact tested state and remaining limits. Do not reset ordinary sandbox data; only isolated test schemas/owned synthetic fixtures may be reset for tests.
