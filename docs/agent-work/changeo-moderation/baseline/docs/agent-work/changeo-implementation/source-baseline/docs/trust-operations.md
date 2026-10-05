# Trust, permissions, moderation and operations

## Verification without misleading guarantees

Offer optional identity verification to providers and customers, particularly useful for in-person arrangements. A badge states exactly what was checked and its validity; it does not establish competence, a clean background or personal safety. Optional badges are not a universal booking requirement.

Prefer an approved hosted identity flow and minimal verification status/reference storage rather than Changeo retaining raw identity-document images. Vendor support, cost, retention, checks and age/guardian features must be evaluated during Gate0. Payment-required KYC is mandatory where the chosen arrangement requires it, regardless of public badge choice.

Guardian identity and authority are separate from a public identity badge. Verifying that someone is an adult does not alone establish their authority over a minor's account. Document the approved relationship/consent verification method before implementation.

## Permission model

| Participant | General participation | Agreements and money | In-person activity | Adult/NSFW access |
| --- | --- | --- | --- | --- |
| Anonymous or age unknown | General public browsing; resolve age/consent before protected actions. | Unavailable until eligibility is established. | No private booking/location access. | Unavailable. |
| Eligible adult | Request/provide within service, billing and partner rules. | Own eligible agreements and payments. | Optional trust badges; ordinary safety/report controls. | Requires established adult eligibility and explicit preference, and only permits otherwise allowed content. |
| Minor through guardian-managed activity | Age-appropriate browsing and drafting within approved consent rules. | Verified guardian is the legal payer/contracting actor and controls marketplace messages. | Verified guardian gives job-specific permission; legal limits still apply. | Unavailable; guardian cannot override prohibited access. |
| Operator | Minimum permissions needed for assigned support work. | Only explicitly authorized financial actions with audit evidence. | Case handling, not unrestricted private-location browsing. | Restricted moderation access based on role. |

Keep public badges, verified guardian authority, provider KYC, age eligibility and content preferences as separate facts. Unknown age must not unlock adult access. Guardian permission is tied to the specific job; revocation prevents new actions and routes existing obligations to support rather than deleting records or money rights.

“Anyone can join” describes broad participation, not unrestricted contracting or paid work. Argentina's [Law 26,390](https://www.argentina.gob.ar/normativa/nacional/141792/texto) generally prohibits work under 16 with a narrow exception; adolescent work has further restrictions. No ordinary under-16 provider workflow is planned. Any 16–17 provider workflow requires validated legal/guardian/partner eligibility before release. Online work does not automatically avoid these rules.

Minor profiles must not expose personal contact details, precise locations or guardian/payment data. Marketplace supplier contact through messages, files and notifications stays within the guardian-managed permission model.

## Content classification and eligibility

| Label | Meaning and display |
| --- | --- |
| General | Ordinary content. Tattoos are not automatically NSFW. |
| Sensitive | Context-sensitive anatomical, medical or similar body-related references; use warnings and appropriate previews without automatically treating legitimate professional material as adult entertainment. |
| NSFW / Adult | Adult classification for moderation/access decisions; hidden from minors and age-unknown users. An adult preference is necessary but never sufficient to authorize prohibited material or services. |

Publishers must choose accurate labels; automated review checks omissions and context. Customer viewing preferences control eligible sensitive content. Never use a label or an “I am 18” toggle as a substitute for the required permission/eligibility checks.

The user requested flags for adult-related services, including explicit material. That source request is preserved in [Requirements](agent-work/changeo-planning/requirements.md). This implementation plan covers detection, restrictions and age controls; it excludes booking sexual services or commissioning pornography. Illegal, exploitative and nonconsensual material is prohibited. Service eligibility also depends on law and payment-provider rules.

Confirmed mislabeling leads to correction, warnings/strikes and proportionate account restrictions for repeated or serious abuse. Provide reasons and appeals. Do not impose irreversible penalties or monetary fines solely from a model judgment. Suspensions must preserve controlled support access to active jobs and valid financial claims.

## Review every user-authored surface

Review listing titles/descriptions/media, offers, requests, public posts, questions/comments, reviews, profile bios and display content, quote descriptions, job feedback, private messages and attachments. Review edits as new revisions. Trusted transactional system events have their own deterministic validation; an LLM never authorizes a financial action.

Pipeline:

1. Authenticate author, validate permissions, normalize supported input and apply size/type/rate limits.
2. Store a pending revision with private attachments. Perform file safety checks and supported text/image extraction.
3. Run policy-based automated review using validated structured outputs and relevant bounded context. Treat submitted content as untrusted data, not instructions to the model.
4. Publish/deliver a clear approved revision; hold uncertain or suspicious content for human review and appeal handling.
5. Record decision, policy/model version, relevant reason and reviewed revision. Restrict access to moderation evidence.

An approval only applies to the exact content and attachment revision inspected. A delayed result for an older revision cannot publish a newer edit. Previously approved unchanged content may remain visible while an edit is pending unless the old content has been recalled.

Apply the same approved-revision and audience checks to public pages, search, inboxes, previews, email/notifications, cached content and attachment downloads. Hiding a component in the browser is not access control. Block unapproved media URLs and notification excerpts.

Uninspectable documents or 3D files remain quarantined for supported extraction/manual review; do not silently skip them. New content remains pending during moderation outages, with user-visible status, retries and an operator queue. Time-sensitive contract actions cannot depend on a message that has not been delivered.

Select the production moderation vendor/model from measured Spanish-language performance, content-policy compatibility, latency, cost, privacy and transfer terms. Astra-only development-agent routing does not establish a production inference vendor or budget.

## Reports, appeals and operator tools

Users can report listings, profiles, comments, messages and jobs with structured reasons and relevant evidence. Offer blocking while preserving active job/support obligations. Avoid letting abusive reporting alone trigger irreversible enforcement.

The founder dashboard needs queues for moderation/appeals, identity/guardian exceptions, fraud concerns, payment exceptions, disputes/refunds and subscription support. Each case records ownership, status, evidence, decisions and notices. Set realistic support hours, escalation timing and case capacity before launch; do not advertise an unfunded 24/7 guarantee.

Dispute decisions follow published, legally reviewed criteria and accepted agreements. The model can assist triage but cannot decide entitlement to customer funds. Staff money actions require explicit permissions, fresh authentication where appropriate and audit records. Admin accounts require MFA.

## Privacy and retention

Explain automated screening of private messages and external processing before use; do not advertise end-to-end encryption that contradicts server-side review. Minimize data sent to vendors, exclude model training contractually where supported/required, and establish retention, subprocessors, international-transfer and rights-handling terms.

Handle identity, guardian, location, medical and other sensitive information with purpose-specific access and retention rules. Ordinary consent is not a blanket legal basis for every sensitive-data use. Applicable requirements must be reviewed under [Law 25,326](https://www.argentina.gob.ar/normativa/nacional/64790/actualizacion) and [AAIP transfer guidance](https://www.argentina.gob.ar/transferencias-internacionales).

Account deletion and data requests must distinguish optional profile data from legally retained financial/case records. Record retention periods during Gate0 rather than inventing indefinite storage. Guardian involvement does not automatically authorize unrestricted access to unrelated private data.
