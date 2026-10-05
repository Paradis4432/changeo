# Foundations independent final review

Verdict: **accepted** for private synthetic foundations under S1/F1/F2. Review date: 1 October 2026. Reviewer `/root/changeo_foundations/foundation_final_review`, host `01a0f5cd-46b6-79a2-a968-d2d031494a8d`. This same independent reviewer inspected the complete cumulative original-baseline patch and accepted the corrected implementation. Production, live money, public launch and Gate0 remain unapproved.

FR1 is resolved. Independent current wrapper verification exited0 with **50 tests, zero failures/errors/skips**, including the actual PostgreSQL Testcontainer. Independent live HTTP checks now enforce canonical login/recovery account caps and both account/source reauthentication caps. Unchanged browser/mobile, actual restart persistence/in-window TOTP replay and startup-refusal evidence from the first independent review remains valid for the unchanged flows. There are no remaining implementation findings or local verification blockers.

## Correction disposition

**FR1 P2 resolved**, at `AccountService.java:120`, `SecurityConfig.java:44`, `WebsiteController.java:50`, `AdminSecurityService.java:48` and `WebsiteSecurityTest.java:106`. The former raw-contact counters and combined reauth actor/source counter allowed the same account to exceed its cap. [First independent report](report.before-FR1.md) and its sanitized original probes preserve the finding/evidence; they are historical changes_requested, not the current verdict.

AccountService now owns one `canonicalContact` rule: null becomes an empty value; non-null contact uses trim plus Locale.ROOT lowercase. Registration validation, credential lookup, recovery lookup and both account counter keys use that rule. Invalid/unknown contacts remain generic and bounded. Reauthentication uses independent five-attempt account and source counters in the existing bounded limiter, matching the MFA ownership pattern. No schema, IdentityAccess contract, token effect, epoch, guardian lifecycle, role, factor or permission rule changed. The correction is cohesive and minimal without replacing useful boundaries or introducing a limiter framework.

Ten additional HTTP cases cover known/unknown aliases, malformed/blank input, distinct-account source limits, both reauthentication dimensions, denial without fresh-password proof and recovery after the controlled expiry window. Existing session/MFA/CSRF tests remain. Disabling MockMvc request dumps is appropriate because tests pass private generated credentials as request parameters. Builder's before-fix focused run genuinely failed8 cases; after-fix13 HTTP cases passed. This reviewer inspected the actual delta, full affected source and tests, updated artifacts, fingerprint/preservation evidence and first/final logs before independently exercising the correction.

Independent **live** results from [fr1-live-review.json](logs/fr1-live-review.json):

| Boundary | Observed result |
| --- | --- |
| Same canonical login account, case/space aliases, distinct sources | First10 password checks; attempts11–12 redirect to limited. |
| Same canonical recovery contact, aliases, distinct sources | First5 generic acknowledgments; sixth403 limited. |
| Login source across10 distinct existing synthetic accounts | Next attempt limited. |
| Recovery source across5 distinct existing synthetic accounts | Sixth limited. |
| Same administrator actor across distinct sources | First5 invalid-password checks; sixth limited, even with correct password. |
| Reauthentication source across6 distinct actors | First5 invalid-password checks; sixth limited, even with correct password. |

The live probe ran normal framework HTML/CSRF/session requests against 127.0.0.1 using private loopback source addresses, with synthetic identities only. Contacts, passwords, cookies and CSRF values stayed in process/private runtime; output contains only assertion results. Its underlying command exited0. The live probe and real HTTP regression cases independently corroborate the code, rather than checking source spelling.

## Complete acceptance matrix

The detailed first-review code/behavior mapping remains in [report.before-FR1.md](report.before-FR1.md); all original requirements and sources remain in their authoritative directories. The current assessment includes that complete independently inspected patch plus the sole five-file correction, not only the FR1 delta.

| Obligation | Evidence on accepted cumulative implementation | Assessment |
| --- | --- | --- |
| F-A1 runtime/persistence | Java25/Boot4.1.1 official wrapper packages; current real PostgreSQL suite/container migration-repeat tests; prior independently observed actual restart preserves contact/preferences. | Accepted. |
| F-A2 accounts/tokens/abuse | Atomic owner/purpose/generation/expiry/single-use tokens, private delivery/failure, duplicate/invalid input, password hashing and reset epochs tested against PostgreSQL/HTTP. FR1 canonical account and source caps verified with current tests/live probes. | Accepted. |
| F-A3 permissions/owner/CSRF | Actual current matrix, unknown/adult/minor/provider/guardian/payer/context denials, under16 prohibition and 16–17 scoped evidence; trusted principal HTTP actor and own safe snapshots. PostgreSQL guards lock complete sorted account/guardian sets through protected commit; both conflict orders, role/guardian/minor restrictions, revoke and caught-denial rollback pass. | Accepted. |
| F-A4 retained rights | Self-only controlled support/rights/existing-obligation eligibility after restrictions/revoke/expiry/relink; historical payer never replaced; no unrelated private records or financial writes. | Accepted. |
| F-A5 administration | Unique local provisioning, encrypted external private factor key, password-only enrollment denial, persisted atomic replay and actual in-window restart replay; current epoch/role/MFA/fresh-password and append-only audit tests. FR1 independent reauth dimensions verified. | Accepted. |
| F-A6 interface | Full Spanish synthetic/pending/disabled/error screens, semantic forms/focus; independently observed keyboard registration/contact/settings/admin and390px layouts without overflow/page errors; saved screenshots visually inspected. Unchanged resources retained. | Accepted within small keyboard/visual scope. |
| F-A7 safety | Initializer rejects effective non-sandbox/public/ambiguous binds and money. Independently executed actual CLI/environment/missing-key refusal matrix and reused matching invalid/wrong-key evidence; no server started. Current unchanged safety tests pass. | Accepted. |
| F-A8 documentation/preservation | Independent271 original snapshot hash matches;85 final source hash matches; only assigned5 docs and root-owned ledger differ. S1/F1/F2/current Sol-high accurately reconciled while original question map/history/Gate0 remain intact. | Accepted. |
| PR1 commit serialization | Mandatory caller transaction, identity-local sorted stable-row locks before refresh, relationship/account-set revalidation, guarded owner commit and rollback tests; no exposed entities/repos/locks or external atomicity claim. | Accepted. |
| PR2 factor/session lifecycle | Unique local enrollment, fail-closed encrypted-key path, no password-only reset/removal, persisted TOTP and epoch-bound step-up/freshness/current roles; corrected per-actor/source reauth caps. | Accepted. |
| PR3 token/session lifecycle | Hash-bound account/purpose/generation effects and reset/supersession, private delivery/generic failure, ordinary/MFA epoch invalidation; corrected canonical abuse counters. | Accepted. |
| PR4 exact matrix/rights | Required/forbidden contexts, actor/subject presence, self/guardian distinctions, optional prospective payer limited to new agreement/funding; later resource owner retains historical membership/payer rules. | Accepted. |
| PR5 guardian/consent history | Pending-only requests, partial uniqueness, independent distinct evidence review, immutable participants/references, expected versions and terminal states, same-transaction linked consent revoke, replacement needing fresh consent; PostgreSQL concurrency tests. | Accepted. |

Identity decisions remain eligibility only. Later jobs/money/moderation owners must validate actual resource membership, participants/mode, exact approved revision, terms, commercial eligibility and historical payer in their own protected transaction. Foundation synthetic consent references certify no actual jobs. No public user-authored surface precedes moderation. Those accepted ownership conditions remain binding for subsequent features.

## Final cumulative design and preservation

The entire initial untracked Java/application/resources/migrations/tests/scripts/wrapper patch and five source-doc amendments were independently inspected in the first review. Before acceptance, this reviewer independently checked the complete current85-file manifest against actual files, compared every manifest entry with pre-FR1 fingerprints, and verified exactly four production-owner files plus WebsiteSecurityTest changed. All remaining product source is byte-identical to the independently inspected state; no cumulative patch area was omitted or accepted only from Git HEAD.

[fr1-preservation-review.json](logs/fr1-preservation-review.json) records all85 matching final source fingerprints and271 matching immutable baseline snapshots. Current baseline differences remain `docs/README.md`, `docs/architecture.md`, `docs/delivery.md`, `docs/payments.md`, `docs/questions.md`, and separately root-owned `docs/agent-work/changeo-implementation/GOAL-CHECKPOINT.md`. No original content, question-ID map, historical seal/routing or pre-existing source was lost/reverted. The root ledger is not builder attribution. Source docs describe their review-handoff state; this independent report records actual acceptance.

Quality assessment remains sound: explicit state/persistence/lifecycle ownership, immutable behavioral API records, conventional Java/Spring services, current facts and traceable failure paths, standard cryptography/framework sessions, private local delivery and deterministic PostgreSQL behavior tests. No unnecessary application comments/Javadoc, redundant overrides, leaked persistence entities/lock handles, duplicated canonical contact rule or speculative frameworks found. Official licensed/generated wrapper content retained. The FR1 fix corrects the natural domain owner and reuses the existing limiter; it preserves useful modularity.

## Commands, exits and durable evidence

| Independent command/check | Exact outcome | Full evidence |
| --- | --- | --- |
| Current `./mvnw -B -ntp verify` with Java25 JDK, private DB loading, workspace Maven/cache, temporary Unix DOCKER_HOST and Ryuk disabled | **Exit0**,35 local/unit/HTTP +15 actual PostgreSQL container tests, zero failures/errors/skips; packaged jar. | [fr1-verify-review.log](logs/fr1-verify-review.log) |
| `scripts/run-sandbox.sh --changeo.synthetic-evidence=true` | Reviewer app1848710 starts127.0.0.1:8080; subsequently intentionally terminated143. | [fr1-app.log](logs/fr1-app.log) |
| `python3 .../reviewer/logs/fr1-live-review.py` | **Exit0**, six account/source boundaries pass through live HTML/CSRF/session flows; no private request data printed. | [fr1-live-review.log](logs/fr1-live-review.log), [probe script](logs/fr1-live-review.py), [result](logs/fr1-live-review.json) |
| Python cumulative fingerprint/delta/baseline check | **Exit0**,85 source hashes match, exactly5 FR1 changes,271 snapshots preserved, expected6 current baseline differences. | [fr1-preservation-review.json](logs/fr1-preservation-review.json) |
| Terminate only reviewer-owned1848710 | TERM command0; assigned app session exit143 expected. | Host tool transcript; runtime evidence below |
| Host `ps`, `ss`, temporary Podman `ps -a --format json` cleanup harness | Harness0, each command0; app1848710 absent, no8080 listener, PostgreSQL1730365/Podman1742781 retained, containers[]. | [fr1-runtime-review.json](logs/fr1-runtime-review.json) |

First-run correction evidence was inspected and retained: builder `fr1-test-first.log` exit1/8 genuine behavioral failures, `fr1-test-corrected.log` exit0/13 HTTP cases, `fr1-verify.log` exit0/50 tests. Independent earlier restricted-JVM/socket failure, duplicate startup and pre-fix live probes remain in this reviewer directory and archived report; they were not overwritten by successful output.

Unchanged earlier independent `verify-host.log`40 tests, browser keyboard/mobile/admin evidence, actual app-restart persistence and in-window TOTP replay, and actual `startup-review.json` refusal processes are explicitly reused for their matching unchanged behaviors. No fresh FR1 browser/restart/startup refusal is claimed; fresh impacted behavior was verified with full regression and live HTTP. No compiler/H2/source-token or silently skipped container substitute was used.

The restricted namespace's earlier false process-absence inference was corrected, not repeated. Both builder FR1 and final reviewer host-visible runtime evidence establish actual app termination. Private factor/key/mailbox/enrollment material was excluded from every source snapshot/report/output. All assertions and underlying command exits are durable, with original first-review combined-replay-exit limitation described honestly in the archived report.

## Originals, guidance and limits

The same reviewer retains complete first-review reads: all user originals, S1/F1/F2, nine authoritative product docs, planning originals/checkpoint, prompt, baseline metadata/relevant full sources, plan/F2 amendment, current/archive plan review, builder reports/artifacts, full application source and required guidance. They are unchanged and their full contents remained available; no compaction-based summary replaced originals. FR1 recheck additionally loaded complete correction assignment, cumulative developer report/checkpoint/notes/questions, actual five-file delta and affected source/test contents, first/current logs and manifests. The authoritative originals remain separately preserved.

| Guidance | Full source/load | Application |
| --- | --- | --- |
| astra-flash-orchestrator | Read `/home/paradis/.agents/skills/astra-flash-orchestrator/SKILL.md`, `references/review.md`, `references/routing.md`, root `routing.json`; unchanged full instructions reused | Distinct independent acceptance, full cumulative baseline, same writer/reviewer and no reviewer source edits/delegation/goals. |
| Shared quality/Ponytail full | Fully injected `/home/paradis/.agents/skills/astra-flash-orchestrator/references/code-quality.md` plus full Ponytail controls; unchanged retained | Natural canonical rule owner, explicit state/lock boundaries, useful interfaces, conventional commentless implementation and meaningful behavioral verification. |
| java-coding-standards | Read `/home/paradis/.agents/skills/java-coding-standards/SKILL.md`; retained full | Immutable records/Optional, cohesive injected services, domain failures, conventional Java and Maven layout. |
| java-junit | Read `/home/paradis/.agents/skills/java-junit/SKILL.md`; retained full | Jupiter/AssertJ/MockMvc, parameterized known/unknown boundaries, controlled-clock expiry, real PostgreSQL concurrency/session coverage. |
| caveman ultra | Read `/home/paradis/.agents/skills/caveman/SKILL.md`; retained | Concise chat only, full evidence/code quality unchanged. |

Builder guidance claims were spot-checked against delivered/read instructions and code. No pre-existing good local implementation was invented; authoritative references are architecture ownership and trust permission matrix. Actual host metadata in `../role-routing.json` records this same separate Sol/high reviewer and signed parent transport; downstream inference provider/per-request correlation remain honestly unverified, with no probes/substitution.

Acceptance is limited to the authorized private synthetic foundation feature. Production legal/guardian/age/privacy/retention/vendor/custody/commercial/capacity, real people/minors, public launch and live-money readiness remain pending under S1. Small browser keyboard/mobile QA is not comprehensive assistive-technology certification. Later marketplace/payment/subscription modules and whole-goal launch acceptance are outside this card. No source/global/protected-metadata edits, external messages, real funds/data, public bind, staging/commit/push/deployment occurred in this review.

Reviewer runtime1848710 is stopped, all previous browser contexts closed; PostgreSQL/temporary Podman remain available for coordinator-owned next work, with no containers left. No new founder question, correction or local environment blocker remains. Coordinator may record this accepted feature and release the next ordered dependency under S1/F2; root alone owns the overall goal lifecycle.
