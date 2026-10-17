alter table jobs_job add column pre_hold_state varchar(40) check(pre_hold_state in ('NEGOTIATING','AGREED_AWAITING_FUNDING','IN_PROGRESS','FINAL_DELIVERY_PENDING_REVIEW','COMPLETED','CANCELLATION_REQUESTED','CANCELLED'));
alter table jobs_job add constraint jobs_selected_tuple unique(id,accepted_quote,accepted_revision,legal_payer);
alter table jobs_event alter column kind type varchar(48);
alter table jobs_event drop constraint jobs_event_kind_check;
alter table jobs_event add constraint jobs_event_kind_check check(kind in ('AGREED','STARTED','FEEDBACK_APPROVED','FINAL_DELIVERY_APPROVED','COMPLETED','CANCELLATION_REQUESTED','CANCELLED','SUPPORT_REQUESTED','DISPUTE_REQUESTED','CONTENT_RECALLED','CHANGE_PENDING_FINANCIAL_OWNER','AGREEMENT_REPLACED','CHANGE_REQUIRES_SETTLEMENT_AND_NEW_JOB','SUPPORT_RESUMED'));

create table jobs_selection_history (
 id uuid primary key, job_id uuid not null references jobs_job(id), old_quote uuid not null, old_revision uuid not null,
 new_quote uuid not null, new_revision uuid not null, legal_payer uuid not null references identity_account(id),
 new_version bigint not null check(new_version>0), occurred_at timestamptz not null,
 foreign key(old_quote,old_revision) references agreements_revision(quote_id,id),
 foreign key(new_quote,new_revision) references agreements_revision(quote_id,id),
 unique(job_id,new_version), check(row(old_quote,old_revision) is distinct from row(new_quote,new_revision))
);
create trigger jobs_selection_history_immutable before update or delete on jobs_selection_history for each row execute function moderation_immutable();
create or replace function jobs_accepted_binding() returns trigger language plpgsql as $$ begin
 if row(old.id,old.conversation_id,old.customer_id,old.provider_id,old.customer_actor,old.provider_actor,old.mode)
    is distinct from row(new.id,new.conversation_id,new.customer_id,new.provider_id,new.customer_actor,new.provider_actor,new.mode) then raise exception 'immutable job participants'; end if;
 if old.legal_payer is not null and old.legal_payer is distinct from new.legal_payer then raise exception 'immutable original legal payer'; end if;
 if old.accepted_revision is not null and row(old.accepted_quote,old.accepted_revision) is distinct from row(new.accepted_quote,new.accepted_revision) then
  if new.accepted_quote is null or new.accepted_revision is null or old.state<>'AGREED_AWAITING_FUNDING' or new.state<>'AGREED_AWAITING_FUNDING'
     or old.final_resource is not null or new.final_resource is not null or old.feedback_count<>0 or new.feedback_count<>0 or new.version<>old.version+1
     or exists(select 1 from jobs_event where job_id=old.id and kind in ('STARTED','FEEDBACK_APPROVED','FINAL_DELIVERY_APPROVED','COMPLETED'))
     or not exists(select 1 from jobs_selection_history where job_id=old.id and old_quote=old.accepted_quote and old_revision=old.accepted_revision
        and new_quote=new.accepted_quote and new_revision=new.accepted_revision and legal_payer=old.legal_payer and new_version=new.version)
     then raise exception 'immutable accepted agreement'; end if;
 end if;
 if old.state='SUPPORT_HOLD' and new.state='SUPPORT_HOLD' and old.pre_hold_state is distinct from new.pre_hold_state then raise exception 'immutable first hold state'; end if;
 return new;
end $$;

create table payments_capital (
 currency char(3) primary key check(currency='ARS'), endowed_minor bigint not null check(endowed_minor>=0),
 available_minor bigint not null check(available_minor>=0), reserved_minor bigint not null default 0 check(reserved_minor>=0),
 fee_minor bigint not null default 0 check(fee_minor>=0), recovery_minor bigint not null default 0 check(recovery_minor>=0),
 check(available_minor+reserved_minor+fee_minor+recovery_minor=endowed_minor)
);
create table payments_balance (
 payer_id uuid primary key references identity_account(id), version bigint not null default 0 check(version>=0)
);
create table payments_root (
 id uuid primary key, job_id uuid not null references jobs_job(id), original_quote uuid not null, original_revision uuid not null,
 payer_id uuid not null references payments_balance(payer_id), customer_id uuid not null, provider_id uuid not null,
 customer_actor uuid not null, provider_actor uuid not null, currency char(3) not null check(currency='ARS'),
 service_minor bigint not null check(service_minor>=0), delivery_minor bigint not null check(delivery_minor>=0),
 total_minor bigint not null check(total_minor=service_minor+delivery_minor),
 policy_scope varchar(32) not null check(policy_scope='PRIVATE_SYNTHETIC'),
 policy_version varchar(40) not null check(policy_version='S1_EXPLICIT_ACCEPTANCE_V1'),
 final_rule varchar(40) not null check(final_rule='EXPLICIT_CUSTOMER_ACCEPTANCE'),
 canonical_version bigint not null check(canonical_version>=0),
 lifecycle varchar(16) not null check(lifecycle in ('PROSPECTIVE','ACTIVE','RETIRED')),
 active_job uuid, active_quote uuid, active_revision uuid, active_payer uuid,
 funded_minor bigint not null default 0 check(funded_minor>=0), refunded_minor bigint not null default 0 check(refunded_minor>=0),
 released_minor bigint not null default 0 check(released_minor>=0), paid_minor bigint not null default 0 check(paid_minor>=0),
 deficit_minor bigint not null default 0 check(deficit_minor>=0), paused boolean not null default false,
 disputed boolean not null default false, bilateral_continuation boolean not null default false,
 version bigint not null default 0 check(version>=0), created_at timestamptz not null,
 unique(job_id,original_quote,original_revision), foreign key(original_quote,original_revision) references agreements_revision(quote_id,id),
 foreign key(active_job,active_quote,active_revision,active_payer) references jobs_job(id,accepted_quote,accepted_revision,legal_payer) deferrable initially deferred,
 check(funded_minor<=total_minor and refunded_minor<=funded_minor and released_minor<=funded_minor and paid_minor<=released_minor),
 check(customer_actor=payer_id),
 check((lifecycle='ACTIVE' and active_job=job_id and active_quote=original_quote and active_revision=original_revision and active_payer=payer_id)
    or (lifecycle<>'ACTIVE' and active_job is null and active_quote is null and active_revision is null and active_payer is null))
);
create unique index payments_one_active_root on payments_root(job_id) where lifecycle='ACTIVE';
create index payments_payer_history on payments_root(payer_id,created_at,id);
create table payments_source (
 id uuid primary key, payer_id uuid not null references payments_balance(payer_id), origin_root uuid not null references payments_root(id) deferrable initially deferred,
 original_minor bigint not null default 0 check(original_minor>=0), confirmed_minor bigint not null default 0 check(confirmed_minor>=0),
 backing_minor bigint not null default 0 check(backing_minor>=0), reversed_minor bigint not null default 0 check(reversed_minor>=0),
 cash_refunded_minor bigint not null default 0 check(cash_refunded_minor>=0), credit_minor bigint not null default 0 check(credit_minor>=0),
 reserved_credit_minor bigint not null default 0 check(reserved_credit_minor>=0), frozen boolean not null default false,
 version bigint not null default 0 check(version>=0),
 check(confirmed_minor<=original_minor and reversed_minor+cash_refunded_minor<=confirmed_minor),
 check(reserved_credit_minor<=credit_minor), check(credit_minor<=backing_minor or frozen)
);
create index payments_available_credit on payments_source(payer_id,id);
create table payments_allocation (
 id uuid primary key, root_id uuid not null references payments_root(id), source_id uuid not null references payments_source(id),
 kind varchar(10) not null check(kind in ('EXTERNAL','CREDIT')), original_minor bigint not null check(original_minor>0),
 confirmed_minor bigint not null default 0 check(confirmed_minor>=0), refunded_minor bigint not null default 0 check(refunded_minor>=0),
 reserved_refund_minor bigint not null default 0 check(reserved_refund_minor>=0),
 cash_refunded_minor bigint not null default 0 check(cash_refunded_minor>=0), credit_refunded_minor bigint not null default 0 check(credit_refunded_minor>=0),
 paid_minor bigint not null default 0 check(paid_minor>=0 and paid_minor<=confirmed_minor),
 unique(root_id,source_id), check(confirmed_minor<=original_minor),
 check(refunded_minor=cash_refunded_minor+credit_refunded_minor), check(refunded_minor+reserved_refund_minor<=confirmed_minor)
);
create index payments_source_exposure on payments_allocation(source_id,root_id);
create table payments_settlement (
 id uuid primary key, root_id uuid not null references payments_root(id), proposer uuid not null,
 refund_minor bigint not null check(refund_minor>0), election varchar(16) not null check(election in ('ORIGINAL_SOURCE','CREDIT')),
 continue_job boolean not null, remaining_minor bigint not null check(remaining_minor>=0),
 root_version bigint not null check(root_version>=0), created_at timestamptz not null,
 check(not continue_job or remaining_minor>0)
);
create table payments_assent (
 settlement_id uuid not null references payments_settlement(id), actor_id uuid not null,
 side varchar(10) not null check(side in ('CUSTOMER','PROVIDER')), assented_at timestamptz not null,
 primary key(settlement_id,side), unique(settlement_id,actor_id)
);
create table payments_operation (
 id uuid primary key, root_id uuid not null references payments_root(id), command_id uuid not null, actor_id uuid not null,
 kind varchar(16) not null check(kind in ('FUND','RELEASE','PAYOUT','REFUND','RECOVERY')),
 amount_minor bigint not null check(amount_minor>0), credit_minor bigint not null default 0 check(credit_minor>=0),
 external_minor bigint not null default 0 check(external_minor>=0), destination varchar(64) not null,
 currency char(3) not null check(currency='ARS'), stable_key uuid not null unique, predecessor uuid references payments_operation(id),
 settlement_id uuid references payments_settlement(id), fee_minor bigint not null default 0 check(fee_minor>=0),
 recovery_minor bigint not null default 0 check(recovery_minor>=0),
 state varchar(24) not null check(state in ('QUEUED','CLAIMED','DISPATCHING','UNKNOWN','RETRY_WAIT','SUCCESS','FAILED_NO_EFFECT','CANCELLED','RECOVERY')),
 generation bigint not null default 0 check(generation>=0), attempts integer not null default 0 check(attempts between 0 and 6),
 claim_token uuid, lease_until timestamptz, available_at timestamptz not null, dispatched_at timestamptz,
 reason varchar(40) not null default 'QUEUED', applied boolean not null default false, created_at timestamptz not null,
 observed_minor bigint not null default 0 check(observed_minor>=0), applied_minor bigint not null default 0 check(applied_minor>=0 and applied_minor<=amount_minor),
 check((state='CLAIMED' and claim_token is not null and lease_until is not null) or state<>'CLAIMED'),
 check(kind not in ('FUND','REFUND') or amount_minor=credit_minor+external_minor)
);
create index payments_due_operation on payments_operation(available_at,id) where state in ('QUEUED','CLAIMED','DISPATCHING','UNKNOWN','RETRY_WAIT');
create index payments_root_operations on payments_operation(root_id,created_at,id);
create table payments_operation_share (
 operation_id uuid not null references payments_operation(id), allocation_id uuid not null references payments_allocation(id),
 amount_minor bigint not null check(amount_minor>0), destination varchar(16) not null check(destination in ('ORIGINAL_SOURCE','CREDIT')),
 primary key(operation_id,allocation_id)
);
create table payments_payout_origin (
 payout_id uuid primary key references payments_operation(id), release_id uuid not null references payments_operation(id)
);
create table payments_receipt (
 actor_id uuid not null, command_id uuid not null, operation varchar(24) not null, digest char(64) not null,
 outcome_id uuid not null, created_at timestamptz not null, primary key(actor_id,command_id)
);
create table payments_applied_share (
 event_id uuid not null, operation_id uuid not null references payments_operation(id), allocation_id uuid not null references payments_allocation(id),
 amount_minor bigint not null check(amount_minor>0), destination varchar(16) not null check(destination in ('ORIGINAL_SOURCE','CREDIT')),
 primary key(event_id,allocation_id)
);
create table payments_observation (
 event_id uuid primary key, operation_id uuid not null references payments_operation(id), stable_key uuid not null,
 outcome varchar(24) not null check(outcome in ('SUCCESS','FAILED_NO_EFFECT','ABSENT','PARTIAL','REVERSED')),
 amount_minor bigint not null check(amount_minor>=0), fingerprint char(64) not null, observed_at timestamptz not null
);
create table payments_pause (
 id uuid primary key, root_id uuid not null references payments_root(id), command_id uuid not null, actor_id uuid not null,
 dispute boolean not null, disposition varchar(24) not null check(disposition in ('PAUSED','RECONCILE','RECOVERY')),
 created_at timestamptz not null, unique(root_id,command_id)
);
create table payments_ledger (
 batch_id uuid not null, line integer not null check(line>=0), root_id uuid references payments_root(id), source_id uuid references payments_source(id),
 account varchar(32) not null check(account in ('CUSTODY_CASH','JOB_LIABILITY','CREDIT_LIABILITY','PROVIDER_LIABILITY','PLATFORM_CASH','PLATFORM_CAPITAL','PLATFORM_EXPENSE','PLATFORM_RECOVERY_EXPENSE','RECOVERY_RECEIVABLE')),
 signed_minor bigint not null check(signed_minor<>0), currency char(3) not null check(currency='ARS'),
 created_at timestamptz not null, primary key(batch_id,line)
);
create function payments_balanced_batch() returns trigger language plpgsql as $$ begin
 if (select sum(signed_minor::numeric) from payments_ledger where batch_id=new.batch_id)<>0 then raise exception 'unbalanced financial batch'; end if;
 return null;
end $$;
create constraint trigger payments_balanced_batch after insert on payments_ledger deferrable initially deferred for each row execute function payments_balanced_batch();
create table payments_gateway_truth (
 stable_key uuid primary key, operation_id uuid not null, root_id uuid not null, kind varchar(16) not null,
 amount_minor bigint not null check(amount_minor>=0), destination varchar(64) not null, currency char(3) not null check(currency='ARS'),
 fingerprint char(64) not null, outcome varchar(24) not null check(outcome in ('SUCCESS','FAILED_NO_EFFECT','PARTIAL','REVERSED')),
 observed_minor bigint not null check(observed_minor>=0 and observed_minor<=amount_minor), event_id uuid not null unique, created_at timestamptz not null
);
create table payments_gateway_history (
 event_id uuid primary key, stable_key uuid not null, operation_id uuid not null, root_id uuid not null,
 kind varchar(16) not null, requested_minor bigint not null check(requested_minor>=0), destination varchar(64) not null,
 currency char(3) not null check(currency='ARS'), fingerprint char(64) not null,
 outcome varchar(24) not null check(outcome in ('SUCCESS','FAILED_NO_EFFECT','ABSENT','PARTIAL','REVERSED')),
 observed_minor bigint not null check(observed_minor>=0 and observed_minor<=requested_minor), created_at timestamptz not null
);
create table payments_exception (
 id uuid primary key, root_id uuid not null references payments_root(id), operation_id uuid not null references payments_operation(id),
 event_id uuid not null, fingerprint char(64) not null, reason varchar(40) not null, created_at timestamptz not null,
 unique(operation_id,event_id,fingerprint,reason)
);
create trigger payments_settlement_immutable before update or delete on payments_settlement for each row execute function moderation_immutable();
create trigger payments_assent_immutable before update or delete on payments_assent for each row execute function moderation_immutable();
create trigger payments_operation_share_immutable before update or delete on payments_operation_share for each row execute function moderation_immutable();
create trigger payments_payout_origin_immutable before update or delete on payments_payout_origin for each row execute function moderation_immutable();
create trigger payments_receipt_immutable before update or delete on payments_receipt for each row execute function moderation_immutable();
create trigger payments_applied_share_immutable before update or delete on payments_applied_share for each row execute function moderation_immutable();
create trigger payments_observation_immutable before update or delete on payments_observation for each row execute function moderation_immutable();
create trigger payments_pause_immutable before update or delete on payments_pause for each row execute function moderation_immutable();
create trigger payments_ledger_immutable before update or delete on payments_ledger for each row execute function moderation_immutable();
create trigger payments_gateway_history_immutable before update or delete on payments_gateway_history for each row execute function moderation_immutable();
create trigger payments_exception_immutable before update or delete on payments_exception for each row execute function moderation_immutable();
create function payments_gateway_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' then raise exception 'immutable gateway truth'; end if;
 if to_jsonb(old)-array['outcome','observed_minor','event_id'] is distinct from to_jsonb(new)-array['outcome','observed_minor','event_id']
    or old.outcome in ('FAILED_NO_EFFECT','REVERSED') or new.observed_minor<old.observed_minor
    or (old.outcome='SUCCESS' and new.outcome<>'REVERSED')
    or (old.observed_minor>0 and new.outcome='FAILED_NO_EFFECT') then raise exception 'immutable terminal gateway binding'; end if;
 return new;
end $$;
create trigger payments_gateway_binding before update or delete on payments_gateway_truth for each row execute function payments_gateway_binding();
create function payments_root_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' then raise exception 'immutable financial root'; end if;
 if row(old.id,old.job_id,old.original_quote,old.original_revision,old.payer_id,old.customer_id,old.provider_id,old.customer_actor,old.provider_actor,old.currency,
    old.service_minor,old.delivery_minor,old.total_minor,old.policy_scope,old.policy_version,old.final_rule,old.canonical_version,old.created_at)
    is distinct from row(new.id,new.job_id,new.original_quote,new.original_revision,new.payer_id,new.customer_id,new.provider_id,new.customer_actor,new.provider_actor,new.currency,
    new.service_minor,new.delivery_minor,new.total_minor,new.policy_scope,new.policy_version,new.final_rule,new.canonical_version,new.created_at) then raise exception 'immutable financial binding'; end if;
 if row(old.lifecycle,old.active_job,old.active_quote,old.active_revision,old.active_payer)
    is distinct from row(new.lifecycle,new.active_job,new.active_quote,new.active_revision,new.active_payer) then
  if old.lifecycle='RETIRED' or (old.lifecycle='ACTIVE' and new.lifecycle<>'RETIRED')
     or exists(select 1 from payments_operation where root_id=old.id)
     or exists(select 1 from payments_allocation where root_id=old.id)
     or exists(select 1 from payments_settlement where root_id=old.id)
     or exists(select 1 from payments_pause where root_id=old.id)
     or exists(select 1 from payments_ledger where root_id=old.id)
     then raise exception 'financial history freezes selected binding'; end if;
 end if;
 return new;
end $$;
create trigger payments_root_binding before update or delete on payments_root for each row execute function payments_root_binding();
create function payments_operation_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' then raise exception 'immutable financial operation'; end if;
 if to_jsonb(old)-array['state','generation','attempts','claim_token','lease_until','available_at','dispatched_at','reason','applied','observed_minor','applied_minor']
    is distinct from to_jsonb(new)-array['state','generation','attempts','claim_token','lease_until','available_at','dispatched_at','reason','applied','observed_minor','applied_minor'] then raise exception 'immutable operation intent'; end if;
 if new.observed_minor<old.observed_minor or new.applied_minor<old.applied_minor then raise exception 'immutable confirmed amounts'; end if;
 if old.applied and not new.applied then raise exception 'immutable applied effect'; end if;
 return new;
end $$;
create trigger payments_operation_binding before update or delete on payments_operation for each row execute function payments_operation_binding();
create function payments_source_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' or row(old.id,old.payer_id,old.origin_root) is distinct from row(new.id,new.payer_id,new.origin_root) then raise exception 'immutable backing source'; end if;
 if old.original_minor<>0 and old.original_minor is distinct from new.original_minor then raise exception 'immutable original source amount'; end if;
 return new;
end $$;
create trigger payments_source_binding before update or delete on payments_source for each row execute function payments_source_binding();
create function payments_allocation_binding() returns trigger language plpgsql as $$ begin
 if tg_op='DELETE' or row(old.id,old.root_id,old.source_id,old.kind,old.original_minor)
    is distinct from row(new.id,new.root_id,new.source_id,new.kind,new.original_minor) then raise exception 'immutable source allocation'; end if;
 return new;
end $$;
create trigger payments_allocation_binding before update or delete on payments_allocation for each row execute function payments_allocation_binding();
