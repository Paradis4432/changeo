# Changeo planning documentation

Status: planning and discovery program accepted; product implementation is gated. Updated: 24 September 2026.

Changeo is an Argentina-wide marketplace where people offer services or publish requests for services they need. The two starting journeys are **Ofrezco** and **Necesito**. Service names are freeform; fulfillment can be local, remote or shipped. Customers pay the agreed service price; providers fund the platform through subscriptions, with Changeo covering processing costs.

The latest instruction, “write to docs,” replaces the earlier `plan/` destination. This package records the planning conversation; it does not establish a payment partnership, authorize spending or declare the product ready to launch.

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
| Launch | One public production launch with real payments; resolve payment feasibility before product implementation. |
| Money | Negotiate full job price; fund upfront; final-only release under accepted per-job terms; Changeo absorbs processing costs. |
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
| Agents | All Astra; distinct coordination, implementation and independent review roles. |

## Planning defaults and boundaries

Defaults are Argentine Spanish, ARS, one account with requesting/providing permissions, one provider per job, and a Java/Spring Boot/PostgreSQL web application. These are implementation planning choices, not additional claims about what the user explicitly said.

Adult-content classification and age controls are covered. Booking sexual services and commissioning pornography are outside this implementation plan. The original request is retained in the source record rather than rewritten to suggest the user withdrew it.

No paid-provider price, trial length, bonus threshold, holding limit, legal entity, payment partner, AI vendor, host, launch date or advertising budget has been approved. These have named discovery outputs in [Delivery](delivery.md); they are not free choices for an implementer.

## First gate

Obtain a viable business/payment arrangement that supports the required Argentina funds flow, full-price funding, accepted release conditions, refunds, credit backing, eligible users and processing-cost subsidy. Public documentation has not established a suitable partner. A normal marketplace split or temporary card authorization is not proof of the requested holding mechanism.

If the gate fails, return to the founder for a product-model decision. Do not silently weaken payment protection or promise an unverified launch.
