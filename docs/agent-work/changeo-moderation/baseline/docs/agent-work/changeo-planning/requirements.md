# Changeo: original requirements and decisions

Source: this Codex conversation, 24 September 2026. Workspace: `/home/paradis/changeo`.

This file preserves user wording and structured answers. Later notes override conflicting option labels. The working specification lives in [the documentation index](../../README.md). Quoted requests are source evidence, not a claim that every requested activity has been approved or is feasible.

## Original request

> fully using astra. i want to work on a website to allow people to request / propose any kind of service to anyone. i.e. i want to sell my 3d printing services and i dont know how to find people, ill post it in changeo and wait for someone to request it. or i might need some random service thats hard to find, ill port a request in changeo.
> i want to start working on a plan of all features, from usage, design, payments, marketing, etc all in this plan mode, write it all down to plan/ and then get agents working on it.

## Discovery answers, in order

### Market, services and monetization

- Launch area: selected **One city / region**, with overriding note: **“argentina only”**. No particular city was chosen.
- Service breadth: selected **All permitted categories**, with note: **“people name their services, can be anything they want”**.
- Initial transaction answer: selected **Contact and quotes first**, with note:

> they will discuss a price and then the payer will send that exact amount. i want to charge a monthly subscriptions to the people offering a service instead so the end user that requests something doesnt have to pay and then also pay the producer. only the producer pays, and then to retain him add some bonus whenever we completes X amount of jobs like uber does

### Checkout, subscriptions and rewards

- Job payment: selected **Checkout in Changeo**, superseding the initial contact-only answer:

> i think pay changeo and then make changeo pay the producer after X time so the user cant get scammed. let changeo hold the money until the product was confirmed to be good

- Subscription start: **Free launch, then trial**. Offered description: recruit founding providers free; later give newcomers a trial before charging to offer services or answer requests.
- Reward form: rejected a single reward type and wrote:

> all of the above. i want to really benefit the loyal producers.

“All” refers to subscription credits, cash bonuses, and visibility/badges.

- Processing costs: **Changeo covers fees**. Offered description: provider receives the full agreed price; platform funds/subscriptions cover processing costs.
- Release policy: **Decide per agreement**. Both parties choose release terms before paying.
- Reward rollout: **Add rewards in stages**. Plan all three benefits; start credits/badges, then cash and visibility boosts once funding and fraud checks are proven.

### Launch and operating constraints

- Payment gate: **Resolve payments first**, with note:

> single launch, no prototype stuff. on release payments should already work

- Fulfillment: **Local, remote, shipped**. Delivery arrangements and their cost belong in the agreed quote.
- Operating budget: **Model costs first**. Compare scenarios, then set prices and spending caps; no spending was authorized.

### Interest on held funds

> add somewhere than i plan on retaining the money so i can invest that money safely, this way i can get a small amount of monney out of the customers without actually charging them, banks do this and give a small % for giving them the money

- Subsequent choice: **Optional upside**. Offered description: subscriptions must sustain the business without it; explore lawful partner arrangements using only the agreed holding period.
- No investment vehicle, custody authority, guaranteed yield, or right to retain returns was established.

### Design, operations, setup and technology

- Design: **Clean practical tool**, with note:

> clean minimalistic style, easy to read and understand, not a bucket of information

- Operations: **Me / small founding team** handles disputed jobs, refunds, reports and provider support.
- Business setup: **Starting from zero**.
- Acquisition: selected **Founder-led organic launch**, with overriding addition:

> i will invest heavily in marketing at first to kickstart the website providers / requesters

- Technology: selected **Choose low-maintenance stack**, with note:

> ideally java because thats what i like the most, unless theres a much better/cheaper to host option

### Work stages, refunds and moderation

- Payment stages: selected **Whole-job payment first**, with note:

> user pays in full and can be refunded in credits or original payment method if desired, but there should also be stages for more complex jobs / feedback.

- Publication: selected **Publish with safeguards**, with note:

> use llms to review posts from both producers and consumers

- Follow-up stage payout choice: **Release only at end**. Stages track feedback/progress; all money remains held until final acceptance under the agreed terms.
- LLM publication choice: **Approve clear; flag doubts**. Clear posts publish automatically; suspicious/uncertain posts wait for human review, with appeals.

### Content permissions and account age

> add nsfw permissions, flags for posts, automatic review of every comment / post / request / message

- Initial account-age answer: selected **Include younger users**, with note: **“anyone can join”**.
- Initial NSFW clarification:

> producer flags the post as nsfw if it is. if he doesnt he gets punished. consumers can decide if they want to include nsfw posts or not.

### Identity verification and guardian clarification

> add a way to verify producers / consumers specially the ones that have to meet in person

- Identity gate: **Optional trust badges**. Users choose verification; mandatory payment-provider checks still apply.
- Minor permissions: **Guardian-managed activity**. Offered description: younger users browse/draft; verified adult handles agreements, payments, messages and in-person arrangements. User note:

> yes i think this is best, have guardians that after verification can choose if the minor can meet in person. i think online can be kept for anyone.

- Latest NSFW answer selected **Non-explicit sensitive content**, but its note supersedes that option's narrower wording:

> nsfw for literal adult related services. things like tattos are not nsfw. maybe add a middle point thats in between full nsfw and normal for things like anatomy or medicine, which are related to human body parts but are also accepted. explicit porn related services must be flagged as nsfw

The assistant subsequently stated the implementation boundary: adult-content detection and age controls can be planned; booking sexual services or commissioning pornography is excluded. This is a disclosed assistance boundary, not a claim the user withdrew the quoted request. Labels do not authorize prohibited activities.

## Documentation handoff

> lmk when you have the plans ready and ill remove plan mode

The consolidated plan was presented and independently accepted as a discovery, documentation and dependency-gated delivery program, not as a validated payment implementation specification.

After the environment switched to Default mode, the latest instruction was:

> write to docs

**Destination decision:** use `docs/` instead of the earlier `plan/`. This turn authorizes writing the documentation package, not initiating product implementation, third-party outreach, spending, account creation or deployment.

## Planning defaults, not independently supplied user requirements

- Argentine Spanish and ARS for launch.
- One account with requesting/providing capabilities; one provider per job.
- Java 25 LTS, supported Spring Boot 4 patch, PostgreSQL, server-rendered web application.
- Original-source refund by default; credits require an explicit customer election.
- Full/partial refunds only through approved settlement/cancellation rules, not an arbitrary buyer amount.
- Minor transactions belong to a verified guardian; no ordinary under-16 provider workflow.
- Moderation outages keep new content pending; edits require approval of the exact new revision.
- No guaranteed scam prevention, investment return, identity-based safety or launch date.

## Applicable workflow and permissions

The latest user-provided AGENTS policy requires a thin Astra root, fresh Astra feature coordinators, distinct implementers and independent final reviewers, durable original requirements, explicit shared contracts, and bounded role contexts. All coding planners, implementers and reviewers must load `/home/paradis/.agents/skills/astra-flash-orchestrator/references/code-quality.md` plus applicable skills. The user's explicit **Astra-only** request overrides the policy's default Flash implementer route.

No automatic staging, commit, merge, push, deployment, external communications, paid probes or global configuration changes follow from completing a feature. Only the root may manage an explicitly requested overall goal; no goal was created for this documentation task. This repository is not Smash, so Smash-specific board/server restrictions do not apply.

Initial baseline: empty `plan/`, no application source, no usable Git repository exposed, protected `.git`, `.agents` and `.codex` placeholders. Do not overwrite those directories to manufacture a repository.
