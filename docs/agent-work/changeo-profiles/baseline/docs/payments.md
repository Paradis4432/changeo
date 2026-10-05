# Payments, refunds and held funds

Status: Mercado Pago selected for job payments and provider billing planning on 30 September 2026; custody/credit capability, legal structure, account eligibility and commercial terms remain unverified. Private synthetic development is authorized under S1 with real money disabled; no real-money operation or production release may start before [Gate0](delivery.md#gate0-feasibility-before-product-implementation) passes.

## Required money flow

The customer and provider negotiate a full job price. The customer pays that exact amount upfront. Changeo covers processing costs, rather than adding a customer fee or deducting them from the provider's agreed service amount. Mandatory taxes/withholdings and their presentation require separate validation.

Funds remain in the approved custody arrangement until the job's agreed final release condition is met or an authorized refund/settlement occurs. Progress stages organize feedback; they do not pay installments to the provider. Customer protection is a defined process, not a guarantee against every scam, quality dispute or chargeback.

## Feasibility evidence required

The founder owns business setup and commercial/legal decisions. The coordinator records Mercado Pago source evidence and the [prepared capability inquiry](gate0-research.md#mercado-pago-inquiry--prepared-not-sent); provider choice is answered. Written Mercado Pago confirmation and applicable professional review must establish:

- Argentina/ARS eligibility for the platform, providers, customers and guardian-paid jobs.
- Permitted services and supported payment methods.
- Legal/account ownership at collection, holding, credit issuance, refund and payout.
- Acceptance-controlled release, allowed review windows, maximum holding time, disputed funds and inactivity handling.
- Refunds before/after payout, partial refunds, chargebacks, unavailable seller balances and failed payouts.
- The platform's ability to absorb processing and payout costs while preserving the agreed customer charge and provider service amount.
- Customer refund-credit backing, reuse, mixed funding, account closure and any redemption rights.
- Onboarding/KYC, tax/invoicing, reserves, reconciliation, authenticated events and idempotent operations.

The public [Mercado Pago split flow](https://www.mercadopago.com.ar/developers/es/docs/split-payments/split-1-1/integration-configuration/integrate-marketplace) allocates payment to seller/marketplace and deducts processing commission from seller proceeds. It does not establish the requested final-acceptance custody control; its localized examples include BRL and do not establish this account's Argentina eligibility. Its [seven-day card authorization](https://www.mercadopago.com.ar/developers/es/docs/checkout-api-payments/payment-management/make-value-reserve?scope=prod) is not collected escrow and cannot be assumed to cover long jobs. The [R3 findings](gate0-research.md#mercado-pago-selection-and-remaining-capability--r3) distinguish Argentina/ARS Payouts and seller-balance availability from approved custody, and original-method refunds from backed reusable credits. Historical alternative-provider comparisons remain research provenance; Mercado Pago is the selected provider.

If no arrangement satisfies the requirements, hold affected production/money work and return for a product-model decision. Do not route customer funds through an ordinary Changeo operating account or silently substitute immediate split payments.

## Agreement and release contract

The funded agreement records an immutable accepted revision: participants and legal payer; price/currency; scope; delivery mode/cost; deliverables; deadlines; feedback stages; completion evidence; review-window trigger/deadline; acceptance method; release condition; cancellation and dispute route.

Per-agreement flexibility is limited to validated, executable terms supported by the partner and law. Free text cannot create arbitrary release logic. Both sides must accept any changed terms before the affected action. The exact supported term choices are a Gate0 output.

- Full payment is confirmed before the provider sees the job as funded.
- Completion evidence and a review period are distinct from a provider merely clicking “done.”
- Customer acceptance may authorize final release under the accepted terms.
- Silence authorizes release only if the exact timer rule was disclosed, accepted and approved for the payment arrangement.
- Explicit-approval-only agreements require a support resolution path for inactive customers within lawful/partner holding limits.
- A dispute pauses a still-controllable release and enters a timed support process. Funds cannot be held indefinitely.
- Intermediate feedback approval has no payout effect.

## One financial authority

The payments module owns all provider calls, funding allocations, payment/payout/refund identifiers, credit reservations and reconciliation. Other modules request actions; they do not mutate money state directly.

For each funded job, serialize competing release/refund/dispute decisions. Persist operation intent before contacting the partner, use stable idempotency keys, authenticate and deduplicate callbacks, and record observed outcomes. A timeout is an unknown outcome, not proof of failure. Block competing actions until reconciliation resolves it.

A dispute arriving before irreversible release prevents release. During an uncertain/in-flight release, reconcile before issuing a conflicting operation. After an irreversible payout, support uses the approved recovery/chargeback/reserve process; the application cannot manufacture a reversal. Keep job completion, money release and bank payout states separate.

Use exact minor-unit amounts and explicit currency. Financial entries are auditable; corrections use reversal/adjustment entries rather than rewriting history. Reconcile partner balances and transactions against internal obligations. Customer principal cannot cover Changeo's fees or losses.

## Refund policy and customer choice

Full and partial refunds are supported through mutually accepted settlements or authorized support decisions under the final published cancellation/dispute policy. A buyer cannot unilaterally invent a refund amount. Specific rights, deadlines, evidence rules, return-shipping treatment and recovery obligations are Gate0 outputs, not invented legal terms.

Original funding source is the default. For externally paid funds, the legal payer may explicitly choose approved Changeo refund credits instead. Record that election. A failed original-method refund remains pending with support/retry handling; never silently substitute credits.

## Credit ownership and accounting

| Balance | Owner and purpose |
| --- | --- |
| Customer refund credit | Original legal payer, including a guardian; backed value usable for eligible future jobs. |
| Provider subscription credit | Promotional reduction of platform subscription charges; not customer money. |
| Provider cash reward payable | Platform-funded reward obligation; distinct from job proceeds and refund balances. |

Defaults: customer credits are nontransferable, have no paid top-up feature and do not expire. Closure/restriction does not erase monetary rights; access and redemption follow the approved support/legal process. A minor's browsing profile does not own a guardian's refund balance.

Do not issue spendable credit until the approved funds arrangement backs it. Reserve and debit credits atomically. Credits used to fund a new job retain source allocations, so the platform can reconcile its obligations without treating them as revenue.

For mixed funding, restore the credit-funded portion to its source balance and refund the externally funded portion to its original method unless the payer explicitly chooses credit for that portion. For partial refunds, allocate proportionally against **remaining refundable source balances**, with deterministic minor-unit rounding and cumulative per-source caps. Repeated one-cent refunds must not over-refund one source. Funding retries, reversals and chargebacks cannot duplicate credits or allow both cash and credit reimbursement for the same amount.

## Optional income from held funds

Preserve the founder's intention: explore lawful income generated while job funds are legitimately awaiting completion, without increasing the negotiated customer charge. This is optional upside; the base business model assumes zero yield.

No investment, guaranteed return, ownership of interest or authority to invest customer principal has been established. Evaluate regulated-partner custody and any permissible commercial revenue share separately from customer-directed investment. [BCRA PSP rules](https://www.bcra.gob.ar/archivos/Pdfs/Texord/t-snp-psp.pdf) distinguish safeguarded payment balances, instructed investments and operating funds. The former 2023 all-yield pass-through requirement was repealed by [A8038](https://www.bcra.gob.ar/archivos/Pdfs/comytexord/A8038.pdf); repeal is not blanket authorization for Changeo's proposal.

Any future arrangement needs documented ownership, beneficiary, liquidity, principal-loss responsibility, taxes, fees and insolvency treatment. Never extend agreed release periods to earn yield, and never spend held principal on operations or rewards.

## Required financial acceptance tests

- Exact customer charge and provider amount, including platform-funded fees and validated tax presentation.
- Duplicate/out-of-order callbacks, API timeouts, retries and reconciliation after an unknown outcome.
- Concurrent acceptance, refund, dispute and chargeback events; no competing unauthorized settlement.
- Early, in-flight and post-payout disputes follow different valid recovery paths.
- Full and partial original-method/credit refunds; failed refunds do not change destination silently.
- Mixed funding, concurrent credit spending and successive one-cent partial refunds.
- Feedback-stage approval never releases money; an unaccepted quote revision cannot be funded.
- Restrictions, guardian revocation and subscription expiry do not strand valid financial obligations.

## Private development authorization and current foundations

The [complete sandbox scope S1](agent-work/changeo-implementation/sandbox-scope-contract.md) records explicit authorization for private synthetic development before Gate0, with real money disabled. Current [identity contract F1](agent-work/changeo-implementation/foundations-contract-F1.md) and [protected-transaction/security contract F2](agent-work/changeo-implementation/foundations-contract-F2.md) apply to later callers. Advisory permission decisions never replace a resource owner's membership, moderation, terms, billing or money checks. Protected writes use the same caller transaction and current identity locks through commit; historical legal-payer rights are preserved.

The local foundation provides contact verification, authentication/recovery, preferences, guardian/consent exercises and MFA-protected identity administration. Synthetic evidence never certifies production eligibility. [Runtime instructions](sandbox-runtime.md) describe local tools, private token/factor files and loopback operation. Foundations and F3 are independently accepted; moderation awaits its separate independent review; Gate0, vendors, production policies, public deployment and real money remain unapproved. Current execution uses GPT-6.1 Sol/high; historical sealed planning/checkpoint/routing records remain unchanged.
