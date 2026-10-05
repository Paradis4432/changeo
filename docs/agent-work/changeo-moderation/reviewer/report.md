# Independent final review: moderation and controlled publication

Verdict: **changes_requested**. The delivered private implementation is not accepted. Two independently reproduced defects violate the durable activation and fail-closed structured-output contracts. Required current-access file races also need concrete byte-boundary verification. Passing existing tests does not resolve these findings.

Reviewer: /root/changeo_moderation/moderation_final_review, host01a0f6a6-743f-7c42-aaf2-67e616a4cb8d. The same independent reviewer was retained through native provider interruptions and recovery. Sole dispatcher is /root/changeo_moderation; sole product writer remains /root/changeo_moderation/moderation_builder. Root owns the overall goal. This review changed only its assigned reviewer evidence; it made no product edits, delegated no work and changed no goal, provider or configuration.

## Required corrections

### MFR1 — High: transient activation failure destroys the recoverable approval state

Location: `src/main/java/ar/changeo/moderation/ModerationWorker.java`, lines49–52,89,97–106; related deliberate retry boundary `ModerationService.java`, lines71–73.

An ACTIVATE task catches `TransientDataAccessException` and invokes `failure(claim,"OUTAGE",false)`. The task becomes RETRY_WAIT, but the submission becomes PENDING. On the next attempt, activation requires APPROVED, so it completes the task as SUPERSEDED without publishing. The submission remains PENDING and the explicit author retry rejects that state. One transient database failure therefore permanently strands an already durable positive decision.

The independent probe injected `TransientDataAccessResourceException` at the trusted owner activation boundary in the actual PostgreSQL fixture. After rollback and the first failure, it observed `submission=PENDING task=RETRY_WAIT`. After removing the injected failure and advancing the deterministic clock, it observed `submission=PENDING task=COMPLETE reason=SUPERSEDED`; ordinary retrieval remained unavailable. See [probe source](logs/ReviewerRegressionProbe.java) and [completed reproduction](logs/regression-probe-final.log). Probe exit0 means the reproduction completed, not that the behavior passed acceptance.

Correct the lifecycle at its owner: keep the durable positive decision recoverable through transient ACTIVATE retries and preserve current identity, exact decision/generation/claim, canonical revision and recall checks. Do not introduce a permissive PENDING activation bypass or cached eligibility flag. Add a real PostgreSQL regression that fails one activation transaction, recovers on retry and proves exactly one pointer/signal effect. Also prove bounded repeated activation outages reach a truthful recoverable terminal state and that deliberate retry cannot duplicate effects. Preserve denial, recall, stale-claim and restart behavior. This blocks M-A2/M-A5 and the shared M1A recovery promise.

### MFR2 — High: contradictory machine action and reason can become a positive decision

Location: `src/main/java/ar/changeo/moderation/ReviewResult.java`, lines6–19; positive decision owner `DecisionService.java`, lines16–25.

Structured validation checks reason membership but not action/reason compatibility. An otherwise exact, correctly labeled `synthetic:clear` result with APPROVE/GENERAL/PROHIBITED returns APPROVE/PROHIBITED. The independent probe reproduced that exact tuple. `DecisionService` checks content/file approvability and then records the action and reason, so it does not repair this contradiction before creating durable ACTIVATE intent. MANUAL, APPEAL and LABEL_CORRECTION are also in the same indiscriminate machine reason set despite their human provenance.

Define and enforce coherent local adapter action/label/reason combinations at the validation boundary. Contradictory tuples must become nonpositive INVALID results. Keep human decision reasons in their human path rather than accepting them as machine provenance. Preserve escaped arbitrary text/manual review, prohibited fixtures, exact binding and attachment checks. Add focused tuple regressions and an actual worker/database assertion that contradictory output creates no positive decision, ACTIVATE task or publication signal. This blocks M-A5 and the original invalid/contradictory-output fail-closed requirement. It does not claim that real prohibited content currently bypasses `SyntheticPolicy`; the reproduced defect is contradictory structured output on a clear fixture.

### MFR3 — Medium: required current-access file transaction races are not established

Location: `src/test/java/ar/changeo/moderation/ModerationPostgresTest.java`, lines239–250,277–288,339–348, inherited by `ModerationContainerIT`.

The two recall race tests meaningfully exercise both orders around `FileAccess.read`. The private-membership test removes a participant only between completed reads. Its preference test exercises resource retrieval without an attachment. Submission/activation restriction and guardian races, and unchanged foundation guard races, do not exercise the new files-owned transaction through bounded byte materialization.

M1A and the final-review brief explicitly require current restriction, preference and membership race assessment at this byte-read boundary. Add deterministic actual PostgreSQL tests in both applicable orders for ordinary file read versus current identity restriction, relevant content-preference withdrawal and private membership removal. Hold a real current bridge/transaction lock through the byte-copy boundary, demonstrate the competing mutation cannot commit first when the permitted read wins, and demonstrate a read waiting behind a committed denial returns no bytes. Cover the exact installed bridge once per read and release all latches/executors. Use existing identity and resource owners; no stronger ordinary security-epoch API is required. This is a verification gap, not an independently reproduced leakage claim. It blocks the affected M-A3/M-A4 acceptance evidence.

## Scope, source inspection and design assessment

The independent review read the complete final-reviewer brief, eight user originals, product/source documents and planning originals, S1/F1/F2/F3 and accepted M1–M1D decisions, local amendments including M1C1, current high-risk plan-review evidence, developer delivery and accepted foundation/F3 evidence. Required truncation follow-ups were completed during the retained review. Recovery reloaded the complete reviewer brief, originals and current shared moderation decisions before finalizing this report; saved independent code and runtime work was retained.

All63 changed moderation source/document/test/template/script paths were independently inspected, including new/untracked files. The cumulative patch comes from the captured original289 baseline plus independently accepted F3 supplement, not Git HEAD. The independent preservation helper regenerated [original cumulative patch](logs/cumulative-original-baseline.patch), [accepted F3 delta](logs/accepted-F3.patch) and [moderation after F3](logs/moderation-after-F3.patch). All three match the delivered patches byte for byte. The original Git state remains unborn master with HEAD exit128 and no staged changes. Root/foundation checkpoint changes are separate ownership and excluded from developer attribution.

Moderation decisions/tasks/cases are separated from canonical workbench resources and private-file state. OwnerRegistry and behavioral ContentOwner/AttachmentAccessPolicy contracts are useful real boundaries. Generic submissions use an owner-neutral reference barrier and immutable review snapshots; the second independent test-owned canonical bridge performs submit/result/activation/recall/file access without workbench rows. This is meaningful owner-neutral contract evidence, not end-to-end acceptance of future listings, messages, quotes, jobs or notifications.

The direct submit boundary participates MANDATORY in the caller transaction, invokes current F2 identity before the installed canonical owner callback and persists the snapshot/task afterward. Caught callback/binding/admission failures roll back the participating transaction. The workbench's separately committed guarded draft has a truthful unsubmitted state. Background results use reference barrier/submission/task order without later canonical/identity locks; activation separately uses identity/canonical owner/barrier/submission/task. File reads reject an active outer transaction and retain their current bridge locks through bounded private byte copy/hash before returning materialized data. These ownership choices are coherent. MFR1 is a concrete lifecycle error within this design; repair that lifecycle without adding overlapping permission flags or another task framework.

Current eligibility, age, guardian, audience, preferences, immutable reference/digest, file safety and operator proof checks remain owner responsibilities. Ordinary security epochs are checked at request admission under M1B: reset afterward need not cancel an admitted request; next old request is denied. Administrator current epoch MFA/freshness remains stronger. No ordinary epoch API change or foundation source takeover is required by this review.

The bounded services use existing Spring transactions/JDBC/PostgreSQL and immutable API values. The review found no additional blocking unnecessary abstraction, vendor/queue framework, pass-through override, explanatory Java comment/Javadoc or transferred money/identity-write ownership. Files enforce bounded type/encoding/image/byte checks and private immutable paths, forbid unsupported/quarantined raw reads and expose audit metadata without raw payload. The implementation does not constitute malware certification. Operator self-review checks compare both persisted actor and subject; current test evidence is stronger for adult self-review than the guardian-as-operator combination, which remains a specific coverage limitation rather than a reproduced bypass.

## Acceptance map

| Criterion | Independent evidence and disposition |
| --- | --- |
| M-A1 persistence/migrations/restart | Actual local PostgreSQL plus45 Testcontainers executions, V4 validation/repeat, immutable payload/file binding and independent packaged restart. Existing paths supported; overall acceptance withheld. |
| M-A2 exact revision/current activation | Pending edit retains prior approved pointer; stale result, recall, durable approval before activation, denial/retry, independent canonical owner and identity ordering exercised. MFR1 blocks recoverable transient activation. |
| M-A3 audience and projections | HTTP/service evidence covers anonymous/unknown/minor/adult/guardian/private/restricted routes, escaped projections, no pending raw/public file links and independent CSRF/MFA browser checks. MFR3 current file access races incomplete. Later owners remain unbuilt. |
| M-A4 private files | Inspector tests, PostgreSQL binding/quarantine/corruption/outer-transaction/storage confinement and both recall byte-read orders pass. MFR3 restriction/preference/membership race proof incomplete. |
| M-A5 worker/fail-closed/recovery | Claim concurrency, leases/stale token, bounded review outage/manual retry, explicit opt-in and durable activation are exercised. Independent MFR1/MFR2 show uncovered required failures; not accepted. |
| M-A6 operator/support | Focused current-role MFA/freshness, auditor metadata only, case ownership/version, report/appeal/correction/reversible warnings and positive-decision shared owner inspected/tested. Guardian-as-operator self-review combination was not separately reproduced. No financial/irreversible model action. |
| M-A7 responsive workflow | Builder full workflow evidence reused with matching delivered source/seals. Independent reviewer ran actual packaged restart/files, keyboard focus,390px author/operator layouts and missing-CSRF denial; four screenshots visually inspected. No comprehensive accessibility certification. |
| M-A8 preservation/docs | All289 original snapshots,87 F3 snapshots,six F3 seals, foundation identity/API/tests/V1–V3/history/question IDs/protected inventory preserved. All143 current source hashes and19 authoritative developer seals match. Exact full artifact manifest has one explained retained API-log append described below. |

## Independent verification, commands and reuse

Full first logs and underlying exits remain in reviewer/logs. [Recovered native command catalog](logs/recovered-command-catalog.json) records actual historical tool input/call IDs; [saved command summary](logs/command-summary.json) preserves early evidence. Relevant command bodies from the workspace are listed below. Password values were loaded privately and never printed/copied into evidence.

```bash
JAVA_HOME="$PWD/.local-tools/jdk-25.0.4.1+1" \
MAVEN_USER_HOME="$PWD/.local-tools/maven-user" \
CHANGEO_DATABASE_PASSWORD="$(cat .runtime/db.password)" \
DOCKER_HOST=unix:///tmp/changeo-podman/podman.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./mvnw -B -ntp \
-DargLine="-javaagent:$PWD/.local-tools/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar" verify

.local-tools/jdk-25.0.4.1+1/bin/javac \
-cp "$(cat docs/agent-work/changeo-moderation/reviewer/logs/probe-classpath.txt)" \
-d docs/agent-work/changeo-moderation/reviewer/logs/probe-classes \
docs/agent-work/changeo-moderation/reviewer/logs/ReviewerRegressionProbe.java

CHANGEO_DATABASE_PASSWORD="$(cat .runtime/db.password)" \
.local-tools/jdk-25.0.4.1+1/bin/java \
-javaagent:.local-tools/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
-cp "docs/agent-work/changeo-moderation/reviewer/logs/probe-classes:$(cat docs/agent-work/changeo-moderation/reviewer/logs/probe-classpath.txt)" \
ar.changeo.moderation.ReviewerRegressionProbe

JAVA_HOME="$PWD/.local-tools/jdk-25.0.4.1+1" scripts/run-sandbox.sh \
--changeo.synthetic-evidence=true --changeo.synthetic-moderation=true \
--changeo.moderation-worker=true

node docs/agent-work/changeo-moderation/reviewer/logs/browser-qa.cjs --restart-check --visual-check
python3 docs/agent-work/changeo-moderation/reviewer/logs/preservation.py
python3 docs/agent-work/changeo-moderation/reviewer/logs/evidence_integrity.py
```

| Check | Underlying exit | Actual result |
| --- | --- | --- |
| verify-first | 1 | Restricted socket environment: `java.net.SocketException: Operation not permitted`;76 attempted,64 context errors. Preserved, not hidden or attributed to product. |
| verify-host | 0 | Authorized host invocation:121 executions,76 local/unit/HTTP plus45 actual PostgreSQL Testcontainers;zero failures/errors/skips,38.332s. Complete independent regression. |
| probe-compile / probe-compile-final | 0 / 0 | Reviewer-only Java evidence compiled against actual Surefire classpath. |
| regression-probe | 1 | Findings already reproduced; runner context cleanup order failed afterward. First log preserved. |
| regression-probe-final | 0 | Only reviewer runner cleanup ordering corrected; both product defects reproduced again in actual PostgreSQL context. |
| browser-first | 0 | Existing approval/file/appeal state survives packaged app startup; exact approved bytes, visible keyboard focus, missing CSRF denied and author/operator desktop/mobile evidence. |
| preservation-first | 0 | Independent regenerated baseline/F3/source/history/docs checks pass;63 allowed changes,143 source hashes,108 checked local links. |
| evidence-integrity-command | 1 | All19 authoritative seals,143 current hashes and all three patch comparisons pass;177/178 full developer manifest entries match. One mutable API log differs. |

The full manifest mismatch is `developer/logs/api-start.log`: sealed596 bytes, current745 bytes. [Append assessment](logs/api-log-append-assessment.json) independently proves the first596 bytes still match the seal. The149 added bytes are one retained Podman API IdleTracker warning timestamped at the independent Maven run. This is retained shared runtime output, not a source change or overwritten historical prefix. The exhaustive checker correctly remains exit1; this report does not claim all178 original full-file hashes match. [Integrity detail](logs/evidence-integrity.json) records every comparison.

The independent preservation helper was copied only after inspection and changed only its evidence destination from developer to reviewer. The browser helper was copied only after inspection and changed its installed Playwright import, working root and evidence destination. Product scripts and developer evidence were not rewritten. The builder's unchanged full browser workflow, first failed logs, corrected final logs, exact command catalog and final delivery were reviewed/reused for registration/evidence/report/appeal/technical exhaustion/edit flows. Those full flows were not all independently rerun in the reviewer browser subset. Recovery reran no unchanged Maven/browser check solely to format the report. Matching source hashes and artifacts justify reuse within this stated coverage; MFR1/MFR2 demonstrate why passing121 existing tests is insufficient.

## Guidance and routing attribution

Read and applied `/home/paradis/.agents/skills/astra-flash-orchestrator/SKILL.md`, its `references/code-quality.md`, `references/review.md`, `references/routing.md`, `references/coordination.md`, `references/execution.md` and `routing.json`. The shared policy was also fully injected; actual installed instructions were read. Used role separation, cumulative dirty-baseline review, explicit lifecycle/transaction ownership and evidence reuse rules; no reviewer orchestration.

Read and applied `/home/paradis/.agents/skills/java-coding-standards/SKILL.md` and `/home/paradis/.agents/skills/java-junit/SKILL.md` to Java/API immutability, conventional Spring boundaries, deterministic PostgreSQL/barrier tests and focused regressions. Read `/home/paradis/.codex/plugins/cache/personal/ponytail/4.10.0+codex.20260924041343/skills/ponytail/SKILL.md`; full shared maintainability/commentless policy applied. Read `/home/paradis/.agents/skills/caveman/SKILL.md`; ultra affects chat only, not this report or code.

Read `/home/paradis/.codex/skills/dignified-python/SKILL.md`, `dignified-python-core.md`, `versions/python-3.12.md`, `references/module-design.md` and `subprocess.md` for the evidence helper review/copy and reviewer integrity helper. Used explicit encoding, pathlib, explicit subprocess checks in the copied helper and main-guarded reviewer module. No Python product changes.

Actual captured host metadata is in [role-routing.json](../role-routing.json): this reviewer, developer and separate plan reviewer use gpt-6.1-sol/high, host transport codex-router-signed. Downstream inference provider and request correlation remain unverified. No self-identification, paid certification/probe or silent replacement was used. Prior native upstream503 interruption evidence remains coordinator-owned and does not change the implementation verdict.

## Runtime release, limits and next action

The reviewer-owned app was PID2287567/native16306. Root explicitly authorized the coordinator to revalidate and stop that exact app during provider interruption. [Actual cleanup](../logs/reviewer-app-cleanup.json) records UID1000/workspace JDK/executable/cwd/jar/loopback8080 before TERM, signal exit0, app absent and no8080 afterward. PostgreSQL1730365/loopback55432 and private Podman API2094717 remain retained. This recovery independently retrieved native app session exit143. Browser helper closes contexts/Firefox in finally; actual browser command exited0. No app restart was needed for report formatting. Earlier Testcontainers run completed cleanup; no unrelated process/service was stopped by this reviewer.

S1 synthetic private development only remains binding. Real-money execution and public launch remain disabled. Unbuilt owner surfaces, real Spanish moderation accuracy, vendor processing/privacy/retention, malware certification, production capacity, custody/legal/commercial choices and all production Gate0 groups remain unresolved. In-memory admission resets on restart are documented technical behavior. No founder choice is invented or needed for MFR1–MFR3.

Coordinator should batch these three findings to the same sole builder, reassess design if corrections materially change lifecycle/contracts, and return corrected delivery to this same independent reviewer. Correct product state and meaningful PostgreSQL regressions are required; changing reports or relaxing assertions alone cannot satisfy acceptance. Re-run affected and full checks after relevant changes, regenerate source/evidence seals and release owned runtime before re-review. Later listings work remains gated on actual independent moderation acceptance.
