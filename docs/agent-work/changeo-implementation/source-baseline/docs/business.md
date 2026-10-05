# Business model, loyalty and economics

## Revenue commitments

Providers pay a monthly subscription to offer services and respond as providers after their applicable free period/trial. Customers can request services without a platform subscription. A dual-role account remains able to act as a customer even if its provider subscription expires.

Founding launch is free, followed by a defined trial/subscription policy. The duration, start trigger, subscription price, renewal notice, grace period and cancellation/refund terms are not yet approved. Determine them from willingness-to-pay research, actual partner costs and the financial model before implementation. Obtain explicit renewal/payment authorization; a free founding account is not permission for a surprise charge.

Changeo absorbs processing costs. This is separate from charging no platform transaction commission: payment providers can still charge Changeo. At high transaction volumes a provider may cost more to serve than their subscription contributes; the model must expose that risk.

## Subscription behavior

- Show the current free/trial/paid/past-due/canceled status and next relevant date clearly.
- Use approved partner billing features and server-confirmed events for entitlements.
- Explain recurring amount and cancellation before enrollment; reflect legally required notices and withdrawal routes.
- Subscription loss may restrict new provider offers/proposals, but must preserve existing job communication, obligations, refunds, disputes and billing access.
- Keep subscription promotional credits separate from job-payment/refund money.
- Record invoices and receipts according to the final tax setup; do not assume a processor receipt is a fiscal invoice.

## Loyalty program

The required long-term program includes all three financial/visibility benefits, introduced in stages:

| Stage | Benefits | Preconditions |
| --- | --- | --- |
| Initial | Subscription credits and nonmisleading achievement badges. | Approved credit budget, clear eligibility and auditable qualifying jobs. |
| Expansion | Earned visibility benefits. | Label placement honestly, preserve relevant discovery and measure customer outcomes. |
| Later | Cash bonuses. | Approved cash funding, settlement rules, anti-abuse controls and tested payout handling. |

Reward thresholds and amounts remain outputs of the financial model, not invented defaults. Track progress toward concrete published milestones only after those terms are approved. Reputation, identity verification and loyalty achievements are different signals; a reward badge cannot claim identity or safety verification.

Qualifying activity comes from completed, valid jobs, with published treatment of cancellations, refunds and disputes. Do not count feedback stages as separate jobs. Check self-dealing, duplicate accounts, collusive counterparties and artificially split jobs. Award idempotently; corrections and appeals have an audit trail. Cash rewards are funded from Changeo's own approved budget, never held customer principal.

## Cost model

Model low, base and high scenarios without assuming investment income or an unsupported acquisition cost:

```text
monthly operating result =
    net collected provider subscriptions
  - job collection, holding, payout and refund costs
  - subscription collection costs
  - rewards and promotional liabilities
  - expected fraud, disputes and chargeback losses
  - automated moderation and human review
  - support, infrastructure, administration and compliance
  - customer and provider acquisition spend
```

Net subscription revenue excludes applicable taxes, refunds and free memberships. Cash-flow planning separately tracks customer liabilities, credit backing, reserves, payout timing and reward obligations; collected customer funds are not operating revenue or launch capital.

| Input | What to establish |
| --- | --- |
| Providers | Active/free/trial/paid counts, subscription conversion, retention and support load. |
| Jobs | Count, average price, duration, fulfillment mix, repeat activity and seasonal variation. |
| Payments | Percentage/fixed fees, subsidy needed to preserve agreed amounts, payout/holding charges and refund losses. |
| Trust | Every-message/revision moderation volume, media costs, manual appeals, identity/guardian checks and abuse handling. |
| Growth | Paid and organic acquisition costs, activation rates, completion conversion and repeat usage. |
| Operations | Founder/staff time, hosting, database/backups, storage, email, accountant/legal setup and reserves. |
| Rewards | Outstanding credits, expected redemption, cash bonus budget and fraud-adjusted cost. |

Calculate contribution by provider/customer cohort, break-even paid-provider count, runway during free founding months and acquisition payback. More jobs can increase losses when the platform subsidizes fees; volume is not automatically a success metric.

## Funding and decision outputs

Before product implementation, the founder must accept modeled subscription terms, free/trial periods, reward limits, subsidy exposure, support capacity, reserve funding and initial marketing budget. Gate0 must record exact approved values and evidence; developers do not choose them.

No budget amount or launch date was committed in the conversation. Marketing investment is intended to be substantial, but this documentation does not authorize spending. Optional held-fund yield is a separate feasibility hypothesis in [Payments](payments.md), with zero contribution in the base case.
