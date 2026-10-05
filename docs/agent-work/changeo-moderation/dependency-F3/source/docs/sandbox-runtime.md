# Private synthetic foundation runtime

This is the single Changeo application under [S1](agent-work/changeo-implementation/sandbox-scope-contract.md), [F1](agent-work/changeo-implementation/foundations-contract-F1.md) and [F2](agent-work/changeo-implementation/foundations-contract-F2.md). Production, real people/minors, vendor integrations, customer money, publication and launch remain gated. No payment or public content module exists. Independent final acceptance is pending.

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

For a private review-only operator, use a new enrollment path and `--operator-roles=MODERATION_REVIEWER` with the provisioning command above. Add `MODERATION_AUDITOR` only when both separate roles are required. An existing identity administrator can remove either role through the current identity administration path, advancing the security epoch and invalidating old sessions. A moderation-only operator's current password reauthentication redirects to the identity dashboard, which remains forbidden; its own moderation interface is a later dependency and is not implemented by foundations. F3 independent acceptance is pending. V1/V2 remain immutable; V3 changes only the identity role-name CHECK constraint, retaining existing roles and keys.

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
