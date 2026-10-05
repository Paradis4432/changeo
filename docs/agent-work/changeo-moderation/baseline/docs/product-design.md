# Product and design

Authority: [original requirements](agent-work/changeo-planning/requirements.md). Dependencies: [trust rules](trust-operations.md) and [payment feasibility](payments.md).

## Purpose and audience

Help a person offering a service find customers, and help a person with an unusual need find someone able to deliver it. Support individual providers and eligible businesses in Argentina, subject to the final legal and partner rules. Users can be both customers and providers without maintaining separate logins.

Service titles and descriptions are freeform. Tags and suggested terms help search but do not force a fixed service taxonomy. Broad scope still has eligibility, regulated-service and prohibited-content limits.

Argentine Spanish and ARS are launch defaults. The product is a responsive website. Native apps, multi-country operations and multi-currency transactions are not initial requirements.

## End-to-end journeys

### A provider offers 3D printing

1. Create an account and complete provider eligibility, subscription/free-period and any required payment onboarding.
2. Publish a service title, capabilities, portfolio, fulfillment area/mode, availability and optional pricing expectations. “A convenir” is valid.
3. Automated review approves the exact listing revision or routes it to a human.
4. A customer finds it and privately supplies requirements, measurements, images or a model file through the approved attachment workflow.
5. Clarify scope and issue a versioned quote covering final price, shipping/pickup, timing, deliverables and release terms.
6. Both parties accept the same revision; the customer funds the full price using supported methods.
7. Provider completes work, optionally using agreed feedback stages. Stage approval releases no funds.
8. Final acceptance or the specifically agreed release process authorizes payout. Issues go through the dispute/refund process.
9. Record completion and allow job-linked reviews. The original service offer remains available for another customer.

### A customer requests a hard-to-find service

1. Describe the need in ordinary language, with optional examples, location/delivery mode, deadline and budget expectation.
2. Publish after moderation; providers can discover and respond without the customer knowing specialist category names.
3. Ask public clarification questions or discuss details privately. Personal addresses and private files are not public listing content.
4. Compare proposals and select one provider for the job. Close the request to new proposals while preserving its history.
5. Continue through the same agreement, funding, progress, acceptance and review flow.

No-response requests get clear status and edit/share actions. The product does not promise a successful match.

## Launch feature inventory

| Area | Required behavior |
| --- | --- |
| Accounts | Registration, verified contact channel, sign-in/recovery, profile, requesting/providing permissions, guardian-managed participation and account settings. |
| Provider profiles | Service portfolio, fulfillment coverage, availability, completed-job reputation and optional verification badge with precise meaning. |
| Offers/requests | Draft, preview, publish after review, edit, pause, close, report and share; preserve historical agreements when a listing changes or closes. |
| Discovery | Text search, optional tags, location and local/remote/shipped filters; separate offer/request views; clear empty states. |
| Conversations | Private listing/proposal conversations, incremental updates, delivery/pending status, attachments, report/block actions and notifications. |
| Public discussion | Moderated listing questions/comments; no public leak of private quote, location or contact details. |
| Agreements | Negotiation, explicit acceptance of a revision, immutable funded terms, change proposals and visible deadlines. |
| Jobs | Funding status, progress/feedback stages, final delivery, acceptance, cancellation, dispute and refund history. |
| Reputation | Reviews tied to real completed jobs, moderation and appeals; no fabricated seed reviews or purchased reputation. |
| Provider billing | Free-period/trial status, subscription terms, invoices/receipts as applicable, renewal/cancellation and reward progress. |
| Support/admin | Moderation queue, cases, identity/guardian checks, disputes, approved financial actions, reconciliation and audit history. |

A request can have competing proposals, but the initial contract is one provider per job. Multi-provider contracts and intermediate financial releases require a later separately reviewed change.

## Agreement content

Collect scope, final price/currency, fulfillment mode, delivery costs, deliverables, completion deadline, included feedback/revisions, evidence of delivery, review-window trigger, final acceptance/release condition, and cancellation/dispute terms. Show relevant details progressively rather than placing every field on the first posting form.

A quote can be exploratory before all fields are settled. Funding is unavailable until required agreement fields and participant permissions are valid. Changing accepted terms requires an explicit new agreement; neither party can silently rewrite funded obligations.

## Screen structure

| Screen | Primary purpose |
| --- | --- |
| Home | Search and clear **Necesito** / **Ofrezco** actions. |
| Search results | Scan relevant listings with essential metadata and a compact filter control. |
| Listing | Understand the service/need, provider/requester, fulfillment and next action. |
| Create/edit | Short staged form, preview, content label, moderation status and actionable errors. |
| Profile | Portfolio, relevant history and clearly scoped verification information. |
| Inbox | Conversation and quote context; visible pending/moderated message status. |
| Agreement/checkout | Exact scope and price, release/refund terms, payer identity and explicit acceptance. |
| Job | Current status, next action, progress/feedback, final acceptance and help. |
| Provider dashboard | Active offers/jobs, subscription and earned reward progress. |
| Settings/support | Privacy, content preferences, guardian permissions, billing and cases. |
| Admin | Focused queues and auditable case actions, not a public-facing analytics wall. |

## Visual and interaction principles

- Use generous spacing, readable text, restrained color and one clear primary action per step.
- Cards show title, concise description, delivery/location, price expectation and relevant trust information. Expand details on demand.
- Prefer familiar forms and semantic HTML. Maintain keyboard access, visible focus, meaningful labels, contrast, readable errors and touch-friendly controls.
- Never communicate financial or moderation status using color alone.
- Show pending/failed/retry states explicitly; do not imply a payment or message succeeded before confirmation.
- Default public location to an appropriate coarse area. Share an exact meeting address only through authorized job communication.
- Avoid “scam-proof,” “safe person” or similar guarantees. Explain the actual protection or verification performed.

## Product acceptance

Both example journeys work from discovery through final settlement. Local appointments, remote deliverables and shipped prints use the same core job model without pretending their delivery evidence is identical. A paused listing, expired subscription or restricted account does not destroy existing agreements, cases or monetary rights. All visible user content follows the moderation and permission contract before reaching its audience.
