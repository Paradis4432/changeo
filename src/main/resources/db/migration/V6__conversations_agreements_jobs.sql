create table conversations_thread (
 id uuid primary key, job_id uuid not null unique, listing_kind varchar(24) not null check(listing_kind in ('OFFER_BODY','REQUEST_BODY')),
 listing_id uuid not null, listing_revision uuid not null, listing_digest char(64) not null,
 listing_label varchar(10) not null check(listing_label in ('GENERAL','SENSITIVE','ADULT')),
 listing_title varchar(160) not null, listing_snapshot text not null check(octet_length(listing_snapshot)<=32768),
 customer_id uuid not null references identity_account(id), provider_id uuid not null references identity_account(id),
 customer_actor uuid not null references identity_account(id), provider_actor uuid not null references identity_account(id),
 mode varchar(10) not null check(mode in ('LOCAL','REMOTE','SHIPPED')), version bigint not null default 0,
 created_at timestamptz not null, check(customer_id<>provider_id), check(customer_actor<>provider_actor),
 foreign key(listing_id,listing_revision) references marketplace_revision(resource_id,id),
 unique(listing_id,customer_id,provider_id)
);
create table conversations_membership (
 thread_id uuid not null references conversations_thread(id), subject_id uuid not null references identity_account(id),
 active boolean not null default true, archived boolean not null default false, primary key(thread_id,subject_id)
);
create table conversations_block (
 subject_id uuid not null references identity_account(id), blocked_subject uuid not null references identity_account(id),
 primary key(subject_id,blocked_subject), check(subject_id<>blocked_subject)
);
create table conversations_authored (
 id uuid primary key, thread_id uuid references conversations_thread(id), listing_id uuid,
 listing_revision uuid, listing_subject uuid references identity_account(id), listing_actor uuid references identity_account(id),
 kind varchar(24) not null check(kind in ('PRIVATE_MESSAGE','PROPOSAL','QUESTION','COMMENT')),
 actor_id uuid not null references identity_account(id), subject_id uuid not null references identity_account(id),
 current_revision uuid, approved_revision uuid, version bigint not null default 0,
 foreign key(listing_id,listing_revision) references marketplace_revision(resource_id,id),
 check((kind in ('PRIVATE_MESSAGE','PROPOSAL') and thread_id is not null and listing_id is null and listing_revision is null)
    or (kind in ('QUESTION','COMMENT') and thread_id is null and listing_id is not null and listing_revision is not null and listing_subject is not null and listing_actor is not null))
);
create table conversations_revision (
 id uuid primary key, resource_id uuid not null references conversations_authored(id),
 body varchar(12000) not null, declared_label varchar(10) not null check(declared_label in ('GENERAL','SENSITIVE','ADULT')),
 digest char(64) not null check(digest ~ '^[a-f0-9]{64}$'), created_at timestamptz not null,
 unique(resource_id,id)
);
alter table conversations_authored add foreign key(id,current_revision) references conversations_revision(resource_id,id) deferrable initially deferred;
alter table conversations_authored add foreign key(id,approved_revision) references conversations_revision(resource_id,id) deferrable initially deferred;
create table conversations_attachment (
 revision_id uuid not null references conversations_revision(id), file_id uuid not null references files_artifact(id),
 digest char(64) not null, primary key(revision_id,file_id)
);
create table conversations_event (
 sequence bigint generated always as identity primary key, thread_id uuid not null references conversations_thread(id),
 resource_id uuid not null references conversations_authored(id), revision_id uuid not null references conversations_revision(id),
 kind varchar(10) not null check(kind in ('DELIVERED','RECALLED')), occurred_at timestamptz not null,
 unique(resource_id,revision_id,kind)
);
create index conversations_incremental on conversations_event(thread_id,sequence);
create table conversations_receipt (
 actor_id uuid not null references identity_account(id), command_id uuid not null, operation varchar(24) not null,
 digest char(64) not null, outcome_id uuid not null, primary key(actor_id,command_id)
);
create table jobs_reputation_cohort (
 subject_id uuid primary key references identity_account(id), version bigint not null default 0
);
create table jobs_job (
 id uuid primary key, conversation_id uuid not null unique references conversations_thread(id),
 customer_id uuid not null references identity_account(id), provider_id uuid not null references identity_account(id),
 customer_actor uuid not null references identity_account(id), provider_actor uuid not null references identity_account(id),
 mode varchar(10) not null check(mode in ('LOCAL','REMOTE','SHIPPED')),
 state varchar(40) not null default 'NEGOTIATING' check(state in ('NEGOTIATING','AGREED_AWAITING_FUNDING','IN_PROGRESS','FINAL_DELIVERY_PENDING_REVIEW','COMPLETED','CANCELLATION_REQUESTED','CANCELLED','SUPPORT_HOLD')),
 accepted_quote uuid, accepted_revision uuid, legal_payer uuid references identity_account(id),
 final_resource uuid, final_revision uuid, final_approved_at timestamptz, review_due_at timestamptz,
 feedback_count integer not null default 0 check(feedback_count>=0), version bigint not null default 0,
 check(customer_id<>provider_id), check(customer_actor<>provider_actor),
 check((accepted_quote is null and accepted_revision is null and legal_payer is null) or (accepted_quote is not null and accepted_revision is not null and legal_payer is not null)),
 check((final_resource is null and final_revision is null and final_approved_at is null and review_due_at is null)
    or (final_resource is not null and final_revision is not null and final_approved_at is not null and review_due_at is not null))
);
alter table conversations_thread add foreign key(job_id) references jobs_job(id) deferrable initially deferred;
create table marketplace_request_selection (
 listing_id uuid primary key, revision_id uuid not null, job_id uuid not null unique references jobs_job(id),
 customer_id uuid not null references identity_account(id), provider_id uuid not null references identity_account(id),
 selector_id uuid not null references identity_account(id), foreign key(listing_id,revision_id) references marketplace_revision(resource_id,id)
);
create table agreements_quote (
 id uuid primary key, job_id uuid not null references jobs_job(id), kind varchar(10) not null check(kind in ('INITIAL','CHANGE')),
 current_revision uuid, approved_revision uuid, version bigint not null default 0
);
create table agreements_revision (
 id uuid primary key, quote_id uuid not null references agreements_quote(id), actor_id uuid not null references identity_account(id),
 subject_id uuid not null references identity_account(id), terms text not null check(octet_length(terms)<=32768),
 review_text text not null check(octet_length(review_text)<=32768), declared_label varchar(10) not null check(declared_label in ('GENERAL','SENSITIVE','ADULT')),
 digest char(64) not null check(digest ~ '^[a-f0-9]{64}$'), created_at timestamptz not null, unique(quote_id,id)
);
alter table agreements_quote add foreign key(id,current_revision) references agreements_revision(quote_id,id) deferrable initially deferred;
alter table agreements_quote add foreign key(id,approved_revision) references agreements_revision(quote_id,id) deferrable initially deferred;
alter table jobs_job add foreign key(accepted_quote,accepted_revision) references agreements_revision(quote_id,id) deferrable initially deferred;
create table agreements_attachment (
 revision_id uuid not null references agreements_revision(id), file_id uuid not null references files_artifact(id), digest char(64) not null,
 primary key(revision_id,file_id)
);
create table agreements_acceptance (
 quote_id uuid not null, revision_id uuid not null, side varchar(10) not null check(side in ('CUSTOMER','PROVIDER')),
 actor_id uuid not null references identity_account(id), subject_id uuid not null references identity_account(id),
 legal_payer uuid references identity_account(id), accepted_at timestamptz not null, primary key(quote_id,revision_id,side),
 foreign key(quote_id,revision_id) references agreements_revision(quote_id,id)
);
create table agreements_receipt (
 actor_id uuid not null references identity_account(id), command_id uuid not null, operation varchar(24) not null,
 digest char(64) not null, outcome_id uuid not null, primary key(actor_id,command_id)
);
create table jobs_authored (
 id uuid primary key, job_id uuid not null references jobs_job(id), kind varchar(16) not null check(kind in ('PROGRESS','FEEDBACK','FINAL_DELIVERY','SUPPORT_NOTE','REVIEW')),
 actor_id uuid not null references identity_account(id), subject_id uuid not null references identity_account(id),
 target_subject uuid references identity_account(id), term_quote uuid not null, term_revision uuid not null,
 current_revision uuid, approved_revision uuid, version bigint not null default 0,
 foreign key(term_quote,term_revision) references agreements_revision(quote_id,id),
 check((kind='REVIEW' and target_subject is not null and target_subject<>subject_id) or (kind<>'REVIEW' and target_subject is null))
);
create unique index jobs_review_side on jobs_authored(job_id,subject_id) where kind='REVIEW';
create table jobs_revision (
 id uuid primary key, resource_id uuid not null references jobs_authored(id), body varchar(12000) not null,
 score integer check(score between 1 and 5), declared_label varchar(10) not null check(declared_label in ('GENERAL','SENSITIVE','ADULT')),
 digest char(64) not null check(digest ~ '^[a-f0-9]{64}$'), created_at timestamptz not null, unique(resource_id,id)
);
alter table jobs_authored add foreign key(id,current_revision) references jobs_revision(resource_id,id) deferrable initially deferred;
alter table jobs_authored add foreign key(id,approved_revision) references jobs_revision(resource_id,id) deferrable initially deferred;
alter table jobs_job add foreign key(final_resource,final_revision) references jobs_revision(resource_id,id) deferrable initially deferred;
create table jobs_attachment (
 revision_id uuid not null references jobs_revision(id), file_id uuid not null references files_artifact(id), digest char(64) not null,
 primary key(revision_id,file_id)
);
create table jobs_event (
 id uuid primary key, job_id uuid not null references jobs_job(id), actor_id uuid references identity_account(id),
 kind varchar(32) not null check(kind in ('AGREED','STARTED','FEEDBACK_APPROVED','FINAL_DELIVERY_APPROVED','COMPLETED','CANCELLATION_REQUESTED','CANCELLED','SUPPORT_REQUESTED','DISPUTE_REQUESTED','CONTENT_RECALLED','CHANGE_PENDING_FINANCIAL_OWNER')),
 resource_id uuid, revision_id uuid, occurred_at timestamptz not null
);
create table jobs_receipt (
 actor_id uuid not null references identity_account(id), command_id uuid not null, operation varchar(32) not null,
 digest char(64) not null, outcome_id uuid not null, primary key(actor_id,command_id)
);
create table notifications_intent (
 id uuid primary key, recipient_id uuid not null references identity_account(id), owner_kind varchar(32) not null,
 resource_id uuid not null, revision_id uuid not null, state varchar(16) not null default 'PENDING' check(state in ('PENDING','CLAIMED','RETRY_WAIT','DELIVERED','FAILED','UNAVAILABLE')),
 generation bigint not null default 0, attempts integer not null default 0 check(attempts between 0 and 3),
 claim_token uuid, lease_until timestamptz, available_at timestamptz not null, reason varchar(32) not null default 'QUEUED',
 created_at timestamptz not null, delivered_at timestamptz,
 unique(recipient_id,owner_kind,resource_id,revision_id),
 check((state='CLAIMED' and claim_token is not null and lease_until is not null) or (state<>'CLAIMED' and claim_token is null and lease_until is null))
);
create table notifications_local_delivery (
 intent_id uuid primary key references notifications_intent(id), observed_at timestamptz not null
);
create index notifications_claim on notifications_intent(available_at,id) where state in ('PENDING','CLAIMED','RETRY_WAIT');
create trigger conversations_revision_immutable before update or delete on conversations_revision for each row execute function moderation_immutable();
create trigger conversations_attachment_immutable before update or delete on conversations_attachment for each row execute function moderation_immutable();
create trigger conversations_event_immutable before update or delete on conversations_event for each row execute function moderation_immutable();
create trigger conversations_receipt_immutable before update or delete on conversations_receipt for each row execute function moderation_immutable();
create trigger marketplace_selection_immutable before update or delete on marketplace_request_selection for each row execute function moderation_immutable();
create trigger agreements_revision_immutable before update or delete on agreements_revision for each row execute function moderation_immutable();
create trigger agreements_attachment_immutable before update or delete on agreements_attachment for each row execute function moderation_immutable();
create trigger agreements_acceptance_immutable before update or delete on agreements_acceptance for each row execute function moderation_immutable();
create trigger agreements_receipt_immutable before update or delete on agreements_receipt for each row execute function moderation_immutable();
create trigger jobs_revision_immutable before update or delete on jobs_revision for each row execute function moderation_immutable();
create trigger jobs_attachment_immutable before update or delete on jobs_attachment for each row execute function moderation_immutable();
create trigger jobs_event_immutable before update or delete on jobs_event for each row execute function moderation_immutable();
create trigger jobs_receipt_immutable before update or delete on jobs_receipt for each row execute function moderation_immutable();
create function jobs_accepted_binding() returns trigger language plpgsql as $$ begin
 if row(old.id,old.conversation_id,old.customer_id,old.provider_id,old.customer_actor,old.provider_actor,old.mode)
    is distinct from row(new.id,new.conversation_id,new.customer_id,new.provider_id,new.customer_actor,new.provider_actor,new.mode) then raise exception 'immutable job participants'; end if;
 if old.accepted_revision is not null and row(old.accepted_quote,old.accepted_revision,old.legal_payer)
    is distinct from row(new.accepted_quote,new.accepted_revision,new.legal_payer) then raise exception 'immutable accepted agreement'; end if;
 return new;
end $$;
create trigger jobs_accepted_binding before update on jobs_job for each row execute function jobs_accepted_binding();
create function conversations_thread_binding() returns trigger language plpgsql as $$ begin
 if to_jsonb(old)-'version' is distinct from to_jsonb(new)-'version' then raise exception 'immutable conversation context'; end if;
 return new;
end $$;
create trigger conversations_thread_binding before update on conversations_thread for each row execute function conversations_thread_binding();
