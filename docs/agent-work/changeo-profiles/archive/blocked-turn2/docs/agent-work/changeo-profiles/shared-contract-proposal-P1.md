# P1: concrete profiles/listings owner integration

Pending root decision; no affected implementation has begun. S1/F1/F2/F3/M1–M1D/M1C1 remain binding. This is private synthetic scope only.

## Owner dispatch collision

Current OwnerRegistry uses one ContentOwner per SurfaceKind. WorkbenchOwner already claims REQUEST_BODY; the actual request owner needs that same surface. Renaming existing requests or claiming a different content surface loses exact ownership semantics and accepted workbench behavior.

Propose additive `ContentOwner.owns(ContentReference)` for nonlocking canonical reference discovery. Registry indexes lists by SurfaceKind, resolves exactly one installed owner that recognizes the complete immutable reference, and denies zero or ambiguous matches. Discovery grants no authorization and changes no lock order; lockRevision independently verifies exact resource/kind/revision/author/current membership under canonical locks. Workbench and test owners implement owns against their own canonical store only; actual new owner does the same. Immutable globally random UUID records never change owners or acquire client-assigned IDs. Existing behavioral tests must still pass; add unknown, ambiguous and cross-owner spoof tests.

## Whole immutable authored revision

Each profile/listing revision contains all public authored fields: display/title, bio/body, tags, coarse area, coverage/availability, pricing/deadline expectations and media. Canonical owner builds a deterministic bounded review text containing those fields and exact attachments. One moderation snapshot approves the entire exact revision (PROFILE_BIO, OFFER_BODY or REQUEST_BODY); all projected fields and bytes derive from that same immutable reviewed revision. No independently mutable title/media fields or implicit approval from one unrelated surface. This avoids unsafe cross-submission aggregation/activation and uses existing moderation callbacks unchanged. Tests independently edit every authored field/media and prove no pending value leaks to detail/search/preview/share/files. Optional system-owned badge and future completed-job reputation are separate projections and cannot be authored rating claims.

## Owner-neutral bounded operator evidence

ContentReader.evidence currently reads WorkbenchStore for every reference, so current operator queue cannot inspect actual owners. Propose route it through installed ContentOwner after current focused reviewer identity guard, lockRevision and moderation barrier/submission, independently compare exact canonical digest/text/files/author against snapshot, then expose bounded safe review text/FileReferences. Keep workbench ContentView shape for template compatibility; ordinary public retrieval remains each canonical owner's responsibility. No raw unsafe bytes or audit-role payload; files current bridge remains required.

## Public optional badge projection

Identity already stores expiring OPTIONAL_BADGE evidence; snapshot is own-only and has no badge result. Profiles must not query identity persistence or duplicate evidence eligibility. Propose identity-owned additive behavioral API returning only optional current scoped badge evidence (`scope`, `checked`, `validUntil`, synthetic provenance), never age, contact, guardian, KYC, document or other private fields. Publication requires explicit profile opt-in, current valid unrestricted evidence and public approved profile. Badge label explicitly synthetic; absence/expiry hides badge; grants no permission. API implementation must preserve identity ownership and current lock/snapshot behavior and requires responsible-owner review under root decision. Root may route a focused foundations extension before the main builder or authorize exact identity-owned projection paths in this bundle with affected acceptance explicitly reopened; coordinator does not assume source takeover.

## Future owners

Profiles/listings API exposes immutable ID/revision and approved public summaries, controlled historical listing references, current open-for-response validation and participant/subject identity through behavior operations. No repository/entity exposure. Conversations/jobs later own their actual membership/agreements; closing a listing never deletes revisions/references or rewrites future obligations. Billing remains a later entitlement dependency; this bundle advertises no chosen paid/trial duration and uses explicitly synthetic provider eligibility only. Root decision records exact API guarantees before downstream dispatch.

Concrete risk requires distinct high-risk plan review: new canonical lifecycle/authorization, overlap dispatch, identity badge projection, current exact retrieval/file races and persisted search. Final review remains separate.

Migration compatibility: ModerationContainerIT currently asserts fixed total migrationsExecuted=4. New V5 must retain exact V1–V4 script/checksum/apply/repeat assertions while allowing additive owner migrations, matching accepted F3 identity assertion design. Permit that narrow test change and new V5+ only; never edit historical migration bytes.
