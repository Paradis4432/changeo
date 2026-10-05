# Changeo planning documentation

Status: foundations independently accepted; private synthetic moderation implementation awaiting its separate review. Updated: 1 October 2026. Production and real money remain gated.

Changeo is an Argentina-wide marketplace where people offer services or publish requests for services they need. The two starting journeys are **Ofrezco** and **Necesito**. Service names are freeform; fulfillment can be local, remote or shipped. Customers pay the agreed service price; providers fund the platform through subscriptions, with Changeo covering processing costs.

The instruction “write to docs” replaced the earlier `plan/` destination. The founder subsequently selected **Mercado Pago for payments**, covering job payments and provider billing planning. This selection does not establish account eligibility, a custody/credit arrangement, commercial terms, spending authority or launch readiness.

## Read the plan

| Document | Responsibility |
| --- | --- |
| [Product and design](product-design.md) | Audiences, journeys, launch features, screens, accessibility and visual direction. |
| [Payments](payments.md) | Feasibility gate, agreements, custody, release, refunds, credits, disputes and optional yield. |
| [Business](business.md) | Subscriptions, staged loyalty benefits, financial scenarios, funding and operating costs. |
| [Trust and operations](trust-operations.md) | Verification, guardians, content permissions, all-surface moderation, support and privacy. |
| [Architecture](architecture.md) | Java stack, ownership boundaries, persistence, integration contracts and deployment. |
| [Marketing](marketing.md) | Provider recruitment, paid acquisition, positioning, measurement and retention. |
| [Delivery](delivery.md) | Dependency order, external gates, agent workflow, verification and launch acceptance. |

## Evidence and provenance

- [Original user requirements and structured answers](agent-work/changeo-planning/requirements.md): preserves wording, corrections and the distinction between user choices and planner defaults.
- [Research source index](agent-work/changeo-planning/sources.md): official references and the limits of the findings.
- [Independent planning review](agent-work/changeo-planning/planning-review.md): accepted the planning program, not a payment implementation or legal conclusion.
- [Documentation fidelity review](agent-work/changeo-planning/documentation-review.md): accepted the written package against the original decisions.
- [Checkpoint](agent-work/changeo-planning/CHECKPOINT.md): current artifact status and exact next action.

## Confirmed decisions

| Area | Decision |
| --- | --- |
| Market | Argentina only; broad permitted services, named by their publishers. |
| Delivery | In-person, remote and shipped custom outputs. |
| Launch | One public production launch with real payments; resolve payment feasibility before production and real money; private synthetic development authorized under S1. |
| Money | Negotiate full job price; fund upfront; final-only release under accepted per-job terms; Changeo absorbs processing costs. |
| Payment provider | Mercado Pago selected for job-payment and provider-billing planning; exact supported arrangement and commercial approval remain Gate0 outputs. |
| Refunds | Original payment method or explicitly chosen customer credits. |
| Progress | Work/feedback stages are supported; intermediate approval releases no money. |
| Revenue | Monthly provider subscriptions; free founding launch, then trials. Customers have no platform subscription. |
| Loyalty | Subscription credits, badges, visibility benefits and cash bonuses, introduced in stages. |
| Yield | Optional lawful income from legitimately held funds; zero-yield base budget. |
| Design | Clean, minimal, readable; avoid dense information displays. |
| Trust | Optional public verification badges for both parties; guardian/payment checks remain separate. |
| Younger users | Guardian-managed activity; verified guardian chooses job-specific in-person permission, within legal limits. |
| Moderation | Automatic review of every user-authored content surface and edit; clear content passes, doubtful content waits for a human. |
| Content labels | General, Sensitive and NSFW; tattoos are not automatically NSFW. Labels never override eligibility rules. |
| Operations | Founder/small team initially handles support and disputes. |
| Growth | Founder recruitment plus substantial marketing investment, with budget modeled before spending. |
| Technology | Prefer Java and low-maintenance hosting. |
| Agents | Current GPT-6.1 Sol/high; distinct coordination, implementation and independent review roles. Historical Astra preference is superseded for this goal. |

## Planning defaults and boundaries

Defaults are Argentine Spanish, ARS, one account with requesting/providing permissions, one provider per job, and a Java/Spring Boot/PostgreSQL web application. These are implementation planning choices, not additional claims about what the user explicitly said.

Adult-content classification and age controls are covered. Booking sexual services and commissioning pornography are outside this implementation plan. The original request is retained in the source record rather than rewritten to suggest the user withdrew it.

Mercado Pago is selected. No paid-provider price, trial length, bonus threshold, holding limit, legal entity, payment account/contract, AI vendor, host, launch date or advertising budget has been approved. The supplied monthly envelope and solo regular-hours support are recorded in [Questions](questions.md). Remaining values have named discovery outputs in [Delivery](delivery.md); they are not free choices for an implementer.

## First gate

Establish a supported Mercado Pago Argentina arrangement for full-price funding, accepted final release, refunds, credit backing, eligible users and processing-cost subsidy. The [selected-provider research](gate0-research.md#mercado-pago-selection-and-remaining-capability--r3) has not publicly established the complete required flow. A normal marketplace split, temporary card authorization or scheduled transfer is not proof of the requested custody mechanism.

If the gate fails, return to the founder for a product-model decision. Do not silently weaken payment protection or promise an unverified launch.

## Private development authorization and current foundations

The [complete sandbox scope S1](agent-work/changeo-implementation/sandbox-scope-contract.md) records explicit authorization for private synthetic development before Gate0, with real money disabled. Current [identity contract F1](agent-work/changeo-implementation/foundations-contract-F1.md) and [protected-transaction/security contract F2](agent-work/changeo-implementation/foundations-contract-F2.md) apply to later callers. Advisory permission decisions never replace a resource owner's membership, moderation, terms, billing or money checks. Protected writes use the same caller transaction and current identity locks through commit; historical legal-payer rights are preserved.

The local foundation provides contact verification, authentication/recovery, preferences, guardian/consent exercises and MFA-protected identity administration. Synthetic evidence never certifies production eligibility. [Runtime instructions](sandbox-runtime.md) describe local tools, private token/factor files and loopback operation. Foundations and F3 are independently accepted; moderation awaits its separate independent review; Gate0, vendors, production policies, public deployment and real money remain unapproved. Current execution uses GPT-6.1 Sol/high; historical sealed planning/checkpoint/routing records remain unchanged.

## Private synthetic moderation and publication

The foundation and focused moderation roles are independently accepted under [F3](agent-work/changeo-implementation/foundations-contract-F3.md). The current moderation implementation is readying an independent review under [M1](agent-work/changeo-implementation/moderation-contract-M1.md), [M1A](agent-work/changeo-implementation/moderation-contract-M1A.md), [M1B](agent-work/changeo-implementation/moderation-contract-M1B.md), [M1C](agent-work/changeo-implementation/moderation-contract-M1C.md) and [M1D](agent-work/changeo-implementation/moderation-contract-M1D.md). This does not close Gate0 or certify production content, vendors or capacity.

The local `/moderation` workbench saves guarded private drafts, queues exact immutable revisions, retains an unchanged approved revision during edits, and exposes review/retry/report/appeal states. `/content` retrieves only currently approved and audience-eligible synthetic examples; detail, search, preview, notification projections and downloads use current owner checks. A committed draft that was not submitted explicitly says it still needs review. Current workbench owners cover request-body and private-message fixtures only; the marketplace workflows and their actual authored surfaces remain later dependencies.

Canonical workbench resources, membership, revisions and publication pointers are separate from moderation reference barriers, immutable review snapshots, decisions, tasks, cases and ID-only signals. Installed trusted owners lock/revalidate canonical bindings after current identity authorization. Submission joins the owner's transaction before canonical locks; a downstream caught failure still rolls back that whole submission. Background results never lock canonical workbench records. Every positive automated/manual/appeal/label-correction decision records a durable activation intent; a fresh identity-first transaction changes the owner's pointer and completes its signal/task together. Files owns each raw-read transaction and rejects an outer caller transaction; its current bridge authorizes before bounded immutable byte copying. The independent test-owned canonical bridge demonstrates this boundary without workbench records; future real owners still require their own concrete integration tests.

Only an explicit local opt-in enables deterministic review fixtures. Arbitrary or invalid output holds for human review, outages retry, and unsupported files stay quarantined. Operator review requires the focused current role, MFA and fresh authentication; metadata audit requires its own role and MFA. Human warnings/strikes are reversible evidence and do not automatically restrict identity or move money. Restricted authors retain controlled status/help/appeal access. Production Spanish model performance, legal content policy, privacy processing, retention and support capacity remain unverified.
