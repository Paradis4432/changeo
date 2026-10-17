create table subscriptions_policy (
 version varchar(64) primary key, digest char(64) not null unique, gross_minor bigint not null check(gross_minor>100),
 founding_days integer not null check(founding_days>=0), trial_days integer not null check(trial_days>=0),
 grace_days integer not null check(grace_days>=0), credit_minor bigint not null check(credit_minor=100 and credit_minor<gross_minor),
 fee_minor bigint not null check(fee_minor>=0), zone varchar(64) not null check(zone='America/Argentina/Buenos_Aires'),
 disclosure text not null, published_at timestamptz not null
);
create table subscriptions_policy_current (
 slot integer primary key check(slot=1), policy_version varchar(64) not null references subscriptions_policy(version),
 version bigint not null default 0 check(version>=0)
);
create table subscriptions_campaign (
 code varchar(64) primary key check(code='SL1B-LOYALTY-2'), rule_version varchar(64) not null check(rule_version=code),
 credit_minor bigint not null check(credit_minor=100), minimum_service_minor bigint not null check(minimum_service_minor=5000),
 counterparties integer not null check(counterparties=2), reverse_days integer not null check(reverse_days=30),
 version bigint not null default 0 check(version>=0)
);
create table subscriptions_provider (
 provider_id uuid primary key references identity_account(id), generation bigint not null default 0 check(generation>=0),
 active_enrollment uuid, founding_used boolean not null default false, trial_used boolean not null default false,
 public_achievements boolean not null default false, version bigint not null default 0 check(version>=0)
);
create table subscriptions_enrollment (
 id uuid primary key, provider_id uuid not null references subscriptions_provider(provider_id), generation bigint not null check(generation>0),
 original_actor uuid not null references identity_account(id), original_payer uuid not null references identity_account(id),
 policy_version varchar(64) not null references subscriptions_policy(version),
 state varchar(24) check(state in ('FREE_FOUNDING','TRIAL','PAID','PAST_DUE','CANCELED')),
 benefit_start timestamptz, benefit_end timestamptz, benefit_kind varchar(24) check(benefit_kind in ('FREE_FOUNDING','TRIAL')),
 benefit_policy_version varchar(64) references subscriptions_policy(version), consent_id uuid, cycle_start timestamptz, anchor_day integer check(anchor_day between 1 and 31),
 stop_renewal boolean not null default false, version bigint not null default 0 check(version>=0), created_at timestamptz not null,
 unique(provider_id,generation), unique(id,provider_id,generation), check(original_actor=original_payer),
 check((benefit_start is null and benefit_end is null and benefit_kind is null and benefit_policy_version is null)
    or (benefit_start is not null and benefit_end is not null and benefit_kind is not null and benefit_policy_version is not null and benefit_end>benefit_start))
);
alter table subscriptions_provider add constraint subscriptions_active_enrollment foreign key(active_enrollment,provider_id,generation)
 references subscriptions_enrollment(id,provider_id,generation) deferrable initially deferred;
create table subscriptions_consent (
 id uuid primary key, enrollment_id uuid not null references subscriptions_enrollment(id), actor_id uuid not null,
 policy_version varchar(64) not null references subscriptions_policy(version), policy_digest char(64) not null,
 mode varchar(16) not null check(mode in ('TRIAL','PAID','RETRY','RESUME')), use_credit boolean not null,
 period_start timestamptz not null, period_end timestamptz not null check(period_end>period_start),
 consent_version bigint not null check(consent_version>0), accepted_at timestamptz not null,
 unique(enrollment_id,consent_version)
);
alter table subscriptions_enrollment add constraint subscriptions_current_consent foreign key(consent_id)
 references subscriptions_consent(id) deferrable initially deferred;
create table subscriptions_receipt (
 actor_id uuid not null, command_id uuid not null, operation varchar(24) not null, digest char(64) not null,
 enrollment_id uuid not null references subscriptions_enrollment(id), occurred_at timestamptz not null,
 primary key(actor_id,command_id)
);
create table subscriptions_event (
 id uuid primary key, enrollment_id uuid not null references subscriptions_enrollment(id), kind varchar(32) not null,
 policy_version varchar(64) not null references subscriptions_policy(version), occurred_at timestamptz not null
);
create table subscriptions_pair_claim (
 campaign varchar(64) not null references subscriptions_campaign(code), provider_payer uuid not null,
 customer_payer uuid not null, completed_event uuid not null, completed_at timestamptz not null,
 version bigint not null default 0 check(version>=0), primary key(campaign,provider_payer,customer_payer),
 check(provider_payer<>customer_payer)
);
create table subscriptions_qualification (
 id uuid primary key, campaign varchar(64) not null references subscriptions_campaign(code), provider_id uuid not null references subscriptions_provider(provider_id),
 billing_payer uuid not null, job_id uuid not null, completed_event uuid not null, customer_payer uuid not null,
 evidence_digest char(64) not null, rule_version varchar(64) not null, confirmed_at timestamptz not null,
 unique(campaign,job_id,completed_event,rule_version)
);
create table subscriptions_evidence (
 provider_id uuid not null references subscriptions_provider(provider_id), billing_payer uuid not null references identity_account(id),
 job_ids uuid[] not null check(cardinality(job_ids)<=2),
 evidence_digest char(64) not null, valid boolean not null, reason varchar(40) not null,
 milestone integer not null check(milestone between 0 and 2), version bigint not null default 0 check(version>=0), checked_at timestamptz not null,
 primary key(provider_id,billing_payer)
);
create table subscriptions_appeal (
 id uuid primary key, enrollment_id uuid not null references subscriptions_enrollment(id), reason varchar(32) not null check(reason in ('QUALIFICATION','RECIPROCAL_HOLD','PROMOTION_DEBT','BACKING_PENDING')),
 state varchar(16) not null check(state in ('OPEN','RECHECKED','RESOLVED')), version bigint not null default 0 check(version>=0), created_at timestamptz not null
);
create table subscriptions_appeal_event (
 id uuid primary key, appeal_id uuid not null references subscriptions_appeal(id), state varchar(16) not null check(state in ('OPEN','RECHECKED','RESOLVED')),
 reason varchar(40) not null, occurred_at timestamptz not null
);
create table payments_subscription_budget (
 kind varchar(16) primary key check(kind in ('FEE','PROMOTION')), endowed_minor bigint not null check(endowed_minor>=0),
 available_minor bigint not null check(available_minor>=0), reserved_minor bigint not null default 0 check(reserved_minor>=0),
 spent_minor bigint not null default 0 check(spent_minor>=0), version bigint not null default 0 check(version>=0),
 check(available_minor+reserved_minor+spent_minor=endowed_minor)
);
create table payments_promotion_wallet (
 id uuid primary key, campaign varchar(64) not null check(campaign='SL1B-LOYALTY-2'), provider_id uuid not null, payer_id uuid not null,
 outstanding_minor bigint not null default 0 check(outstanding_minor>=0), reserved_minor bigint not null default 0 check(reserved_minor>=0),
 redeemed_minor bigint not null default 0 check(redeemed_minor>=0), returned_minor bigint not null default 0 check(returned_minor>=0),
 debt_minor bigint not null default 0 check(debt_minor in (0,100)), invalid boolean not null default false,
 evidence_digest char(64), version bigint not null default 0 check(version>=0), unique(campaign,provider_id,payer_id),
 check(reserved_minor<=outstanding_minor), check(outstanding_minor+redeemed_minor+returned_minor in (0,100))
);
create table payments_promotion_award (
 id uuid primary key, wallet_id uuid not null unique references payments_promotion_wallet(id), campaign varchar(64) not null,
 provider_id uuid not null, payer_id uuid not null, evidence_digest char(64) not null,
 face_minor bigint not null check(face_minor=100), created_at timestamptz not null,
 unique(campaign,provider_id), unique(campaign,payer_id)
);
create table payments_billing_root (
 id uuid primary key, wallet_id uuid not null references payments_promotion_wallet(id), enrollment_id uuid not null,
 generation bigint not null check(generation>0), provider_id uuid not null, original_actor uuid not null, payer_id uuid not null,
 consent_id uuid not null, policy_version varchar(64) not null, policy_digest char(64) not null,
 period_start timestamptz not null, period_end timestamptz not null check(period_end>period_start),
 gross_minor bigint not null check(gross_minor>100), currency char(3) not null check(currency='ARS'),
 created_at timestamptz not null, unique(enrollment_id,period_start), check(original_actor=payer_id)
);
create table payments_billing_operation (
 id uuid primary key, root_id uuid not null references payments_billing_root(id), command_id uuid not null,
 stable_key uuid not null unique, predecessor uuid references payments_billing_operation(id),
 permitted_promotion_minor bigint not null check(permitted_promotion_minor in (0,100)), promotion_minor bigint not null check(promotion_minor in (0,100)),
 external_minor bigint not null check(external_minor>0), fee_minor bigint not null check(fee_minor>=0),
 state varchar(24) not null check(state in ('QUEUED','CLAIMED','DISPATCHING','UNKNOWN','SUCCESS','FAILED_NO_EFFECT','CANCELLED','REVERSED','RECOVERY')),
 collected_minor bigint not null default 0 check(collected_minor>=0 and collected_minor<=external_minor),
 returned_minor bigint not null default 0 check(returned_minor>=0 and returned_minor<=collected_minor),
 promotion_redeemed boolean not null default false, reservations_restored boolean not null default false,
 fee_spent boolean not null default false, success_applied boolean not null default false,
 generation bigint not null default 0 check(generation>=0), claim_token uuid, lease_until timestamptz, dispatched_at timestamptz,
 available_at timestamptz not null, reason varchar(40) not null, version bigint not null default 0 check(version>=0), created_at timestamptz not null,
 unique(root_id,command_id), check(not promotion_redeemed or success_applied),
 check(not success_applied or collected_minor=external_minor), check(not fee_spent or collected_minor>0)
);
create index payments_billing_due on payments_billing_operation(available_at,id) where state in ('QUEUED','CLAIMED','DISPATCHING','UNKNOWN');
create table payments_billing_observation (
 event_id uuid primary key, operation_id uuid not null references payments_billing_operation(id), stable_key uuid not null,
 outcome varchar(24) not null check(outcome in ('SUCCESS','FAILED_NO_EFFECT','ABSENT','PARTIAL','REVERSED')),
 amount_minor bigint not null check(amount_minor>=0), fingerprint char(64) not null, observed_at timestamptz not null
);
create table payments_subscription_ledger (
 batch_id uuid not null, line integer not null check(line>=0), wallet_id uuid references payments_promotion_wallet(id),
 operation_id uuid references payments_billing_operation(id), account varchar(40) not null,
 signed_minor bigint not null check(signed_minor<>0), currency char(3) not null check(currency='ARS'), occurred_at timestamptz not null,
 primary key(batch_id,line)
);
create table payments_promotion_adjustment (
 id uuid primary key, wallet_id uuid not null references payments_promotion_wallet(id), evidence_digest char(64) not null,
 kind varchar(32) not null check(kind in ('INVALIDATED','REINSTATED','DEBT','DEBT_CLEARED','HELD')),
 amount_minor bigint not null check(amount_minor in (0,100)), occurred_at timestamptz not null
);
create table payments_billing_exception (
 id uuid primary key, operation_id uuid not null references payments_billing_operation(id), event_id uuid not null,
 fingerprint char(64) not null, reason varchar(40) not null, occurred_at timestamptz not null,
 unique(operation_id,event_id,fingerprint,reason)
);
create function payments_subscription_balanced_batch() returns trigger language plpgsql as $$ begin
 if (select sum(signed_minor::numeric) from payments_subscription_ledger where batch_id=new.batch_id)<>0 then raise exception 'unbalanced subscription batch'; end if;
 return null;
end $$;
create constraint trigger payments_subscription_balanced_batch after insert on payments_subscription_ledger deferrable initially deferred for each row execute function payments_subscription_balanced_batch();
create function payments_billing_amount_binding() returns trigger language plpgsql as $$ declare gross bigint; begin
 select gross_minor into strict gross from payments_billing_root where id=new.root_id for key share;
 if new.external_minor::numeric+new.promotion_minor::numeric<>gross::numeric or new.permitted_promotion_minor<>new.promotion_minor then raise exception 'billing amount differs from immutable root'; end if;
 return new;
end $$;
create trigger payments_billing_amount_binding before insert or update on payments_billing_operation for each row execute function payments_billing_amount_binding();
create function subscriptions_enrollment_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' or row(old.id,old.provider_id,old.generation,old.original_actor,old.original_payer,old.created_at)
    is distinct from row(new.id,new.provider_id,new.generation,new.original_actor,new.original_payer,new.created_at) then raise exception 'immutable subscription binding'; end if;
 return new;
end $$;
create trigger subscriptions_enrollment_binding before update or delete on subscriptions_enrollment for each row execute function subscriptions_enrollment_binding();
create function subscriptions_provider_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' or old.provider_id<>new.provider_id or new.generation<old.generation
    or (old.founding_used and not new.founding_used) or (old.trial_used and not new.trial_used) then raise exception 'immutable subscription eligibility history'; end if;
 return new;
end $$;
create trigger subscriptions_provider_binding before update or delete on subscriptions_provider for each row execute function subscriptions_provider_binding();
create function subscriptions_evidence_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' then raise exception 'immutable evidence ownership'; end if;
 if row(old.provider_id,old.billing_payer) is distinct from row(new.provider_id,new.billing_payer) then raise exception 'immutable evidence ownership'; end if;
 return new;
end $$;
create trigger subscriptions_evidence_binding before update or delete on subscriptions_evidence for each row execute function subscriptions_evidence_binding();
create function subscriptions_pair_claim_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' or row(old.campaign,old.provider_payer,old.customer_payer) is distinct from row(new.campaign,new.provider_payer,new.customer_payer)
    or new.completed_at<=old.completed_at or new.completed_event=old.completed_event or new.version<>old.version+1 then raise exception 'immutable historical payer claim'; end if;
 return new;
end $$;
create trigger subscriptions_pair_claim_binding before update or delete on subscriptions_pair_claim for each row execute function subscriptions_pair_claim_binding();
create function payments_billing_operation_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' or to_jsonb(old)-array['state','collected_minor','returned_minor','promotion_redeemed','reservations_restored','fee_spent','success_applied','generation','claim_token','lease_until','dispatched_at','available_at','reason','version']
    is distinct from to_jsonb(new)-array['state','collected_minor','returned_minor','promotion_redeemed','reservations_restored','fee_spent','success_applied','generation','claim_token','lease_until','dispatched_at','available_at','reason','version']
    or new.collected_minor<old.collected_minor or new.returned_minor<old.returned_minor or (old.success_applied and not new.success_applied)
    or (old.fee_spent and not new.fee_spent) or (old.promotion_redeemed and not new.promotion_redeemed)
    or (old.reservations_restored and not new.reservations_restored) then raise exception 'immutable billing operation'; end if;
 return new;
end $$;
create trigger payments_billing_operation_binding before update or delete on payments_billing_operation for each row execute function payments_billing_operation_binding();
create trigger subscriptions_policy_immutable before update or delete on subscriptions_policy for each row execute function moderation_immutable();
create trigger subscriptions_consent_immutable before update or delete on subscriptions_consent for each row execute function moderation_immutable();
create trigger subscriptions_receipt_immutable before update or delete on subscriptions_receipt for each row execute function moderation_immutable();
create trigger subscriptions_event_immutable before update or delete on subscriptions_event for each row execute function moderation_immutable();
create trigger subscriptions_qualification_immutable before update or delete on subscriptions_qualification for each row execute function moderation_immutable();
create trigger subscriptions_appeal_event_immutable before update or delete on subscriptions_appeal_event for each row execute function moderation_immutable();
create trigger payments_promotion_award_immutable before update or delete on payments_promotion_award for each row execute function moderation_immutable();
create trigger payments_billing_root_immutable before update or delete on payments_billing_root for each row execute function moderation_immutable();
create trigger payments_billing_observation_immutable before update or delete on payments_billing_observation for each row execute function moderation_immutable();
create trigger payments_subscription_ledger_immutable before update or delete on payments_subscription_ledger for each row execute function moderation_immutable();
create trigger payments_promotion_adjustment_immutable before update or delete on payments_promotion_adjustment for each row execute function moderation_immutable();
create trigger payments_billing_exception_immutable before update or delete on payments_billing_exception for each row execute function moderation_immutable();
