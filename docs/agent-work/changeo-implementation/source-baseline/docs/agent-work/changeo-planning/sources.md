# Research source index

Research carried forward from the planning conversation dated **24 September 2026**. This index records previously inspected primary sources and their scoped findings; its author did not browse or reverify the pages while writing this file. Product documentation, regulatory texts and supported versions can change. Recheck relevant sources at Gate0 and before implementation or launch.

**Unresolved:** no partner has been confirmed to provide lawful Argentina/ARS custody with release controlled by the agreed acceptance process. Country coverage, marketplace splitting and card authorization do not establish that capability. Legal applicability and partner approval remain evidence gates, not conclusions from this index.

## S01

**Mercado Pago: marketplace split integration**

[Integration configuration](https://www.mercadopago.com.ar/developers/es/docs/split-payments/split-1-1/integration-configuration/integrate-marketplace)

Documents OAuth-based seller authorization, marketplace splitting, seller-side fee deductions and refund constraints involving available balances. This informs fee gross-up and refund-funding discovery because Changeo promises providers the full agreed price.

**Status/caveat:** no documented buyer-controlled hold and acceptance release was established. Split payments alone do not meet the custody requirement.

## S02

**Mercado Pago: subscriptions and plan management**

[Subscriptions overview](https://www.mercadopago.com.ar/developers/es/docs/subscriptions/overview) · [Manage subscription plans](https://www.mercadopago.com.ar/developers/es/docs/subscription-plans/manage-subscription-plan)

Recurring monthly billing and trials are supported; subscription management includes amount changes and pauses. These are candidate building blocks for free founding access followed by trial and paid provider subscriptions.

**Status/caveat:** these pages do not prove an automatic loyalty-credit or coupon engine, nor the final commercial terms of Changeo's account.

## S03

**Mercado Pago: reserve an amount**

[Card authorization reserve](https://www.mercadopago.com.ar/developers/es/docs/checkout-api-payments/payment-management/make-value-reserve?scope=prod)

The inspected flow reserves a card amount through authorization lasting seven days.

**Status/caveat:** an authorization is not collected escrow. Its duration cannot support arbitrary job lengths or establish the requested custody and acceptance process.

## S04

**Escrow.com: coverage, currencies and milestones**

[Supported countries/regions](https://www.escrow.com/support/faqs/what-countries-regions-does-escrowcom-support) · [Supported currencies](https://www.escrow.com/support/supported-currencies) · [Milestones](https://www.escrow.com/milestones/how-it-works?amp=true)

Argentina appears in country coverage. The milestone flow describes inspection and acceptance before release. ARS was absent from supported currencies at the prior read.

**Status/caveat:** not selected. Country listing does not establish platform onboarding, every proposed service category, Argentine regulatory suitability or ARS settlement. Its milestone payouts also differ from Changeo's launch rule: feedback stages, release only at the end.

## S05

**BCRA: consolidated payment-service-provider rules**

[Sistema Nacional de Pagos — Proveedores de servicios de pago](https://www.bcra.gob.ar/archivos/Pdfs/Texord/t-snp-psp.pdf)

The inspected consolidated text was ordered 14 July 2026 and incorporated Communication A8454. It requires immediate availability of applicable customer balances, 100% backing in domestic peso sight accounts, separate operating funds, and express customer instruction for permitted domestic money-market investment with separate presentation.

**Status/caveat:** these are scoped PSP rules. They neither classify Changeo automatically as a PSP nor authorize Changeo to hold or invest customer funds. Counsel and an approved partner must resolve the actual structure.

## S06

**BCRA: Communication A8038**

[Communication A8038](https://www.bcra.gob.ar/archivos/Pdfs/comytexord/A8038.pdf)

Repealed the 2023 PSP requirement to pass through all relevant yield, effective 7 June 2024.

**Status/caveat:** repeal is not permission to invest, retain customer money or keep returns. Any lawful yield arrangement remains optional upside; subscriptions must sustain zero-yield economics.

## S07

**Argentina: Financial Institutions Law 21,526**

[Updated law](https://www.argentina.gob.ar/normativa/nacional/ley-21526-16071/actualizacion)

Establishes the financial-institution authorization framework relevant when considering bank-like intermediation.

**Status/caveat:** banks earning returns on funds is not a sufficient legal basis for Changeo's proposed model. Applicability requires qualified legal analysis.

## S08

**CNV: General Resolution 1147/2026**

[Resolution text](https://www.argentina.gob.ar/normativa/nacional/norma-426583/texto)

Relevant mutual-fund disclosures do not guarantee investment outcomes.

**Status/caveat:** no selected fund, principal guarantee, custody authorization or entitlement to returns follows. Do not promise a risk-free customer-fund investment.

## S09

**Argentina: age, capacity and adolescent work**

[Civil and Commercial Code, Law 26,994](https://www.argentina.gob.ar/normativa/nacional/ley-26994-235975/actualizacion) · [Law 26,390](https://www.argentina.gob.ar/normativa/nacional/141792/texto)

Code articles 25, 26, 683 and 684 address age, capacity and relevant parental/professional permissions. Law 26,390 generally prohibits work under 16, with a narrow exception and restrictions for adolescents.

**Status/caveat:** “anyone can join” cannot imply unrestricted contracting or provision of paid services. Guardian consent is not a universal exemption. Plan verified guardian ownership of permitted minor transactions; no ordinary under-16 provider workflow.

## S10

**Argentina: personal data and international transfers**

[Personal Data Protection Law 25,326](https://www.argentina.gob.ar/normativa/nacional/64790/actualizacion) · [AAIP international transfers](https://www.argentina.gob.ar/transferencias-internacionales) · [AAIP reform proposal](https://www.argentina.gob.ar/node/436471)

Existing data-protection and transfer requirements inform identity checks, message moderation and foreign service providers. The AAIP's 2024 age-16 digital-consent material describes a reform proposal.

**Status/caveat:** age 16 in that proposal is not an enacted universal consent rule. Determine the lawful basis, retention, processor and transfer arrangements for each actual data flow.

## S11

**Argentina: consumer withdrawal/cancellation and invoicing**

[Disposition 3/2026](https://www.argentina.gob.ar/normativa/nacional/disposici%C3%B3n-3-2026-423007/texto) · [ARCA: monotributo receipts](https://www.arca.gob.ar/facturacion/monotributo/comprobantes.asp)

Consumer withdrawal/cancellation requirements inform subscription and purchase UX. ARCA guidance concerns fiscal invoicing; payment-processor receipts do not by themselves establish compliance with invoicing obligations.

**Status/caveat:** counsel must establish which obligations and exceptions apply to each offering; accounting review must allocate provider and platform invoicing responsibilities.

## S12

**Technical baseline: Java, Spring Boot and PostgreSQL**

[Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) · [Adoptium support](https://adoptium.net/support) · [PostgreSQL full-text search](https://www.postgresql.org/docs/current/textsearch.html) · [Spring Boot testing](https://docs.spring.io/spring-boot/reference/testing/index.html)

At the previous inspection, Boot 4.1.1 documented Java 17–26 compatibility. Planning chooses Java 25 LTS, a supported Spring Boot 4 patch, PostgreSQL and server-rendered pages. PostgreSQL supplies native full-text search. Spring Boot supplies testing support and managed dependencies.

**Status/caveat:** pin and verify supported versions when building; these are moving documentation URLs. Do not mandate an exact JUnit 5 version independently of the chosen Boot dependency management. This is a proposed stack, not an implemented or benchmarked system.

## S13

**Competitors: Workana and Zolvers**

[Workana escrow](https://help.workana.com/hc/es/articles/360041401574-Dep%C3%B3sito-en-Garant%C3%ADa-Escrow) · [Zolvers](https://zolvers.com/)

Workana already describes fixed-price escrow protection. Zolvers serves local services, including some maintenance arrangements with direct payment.

**Status/caveat:** these examples disprove a confident “no competitors” claim; they do not constitute exhaustive market research. Changeo's differentiation and acquisition economics need validation.
