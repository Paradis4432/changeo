create table marketplace_resource (
 id uuid primary key, subject_id uuid not null references identity_account(id), creator_id uuid not null references identity_account(id),
 type varchar(24) not null check(type in ('CUSTOMER_PROFILE','PROVIDER_PROFILE','OFFER','REQUEST')),
 lifecycle varchar(10) not null default 'OPEN' check(lifecycle in ('OPEN','PAUSED','CLOSED')),
 current_revision uuid, approved_revision uuid, version bigint not null default 0
);
create unique index marketplace_profile_subject on marketplace_resource(subject_id,type) where type in ('CUSTOMER_PROFILE','PROVIDER_PROFILE');
create table marketplace_revision (
 id uuid primary key, resource_id uuid not null references marketplace_resource(id),
 actor_id uuid not null references identity_account(id), subject_id uuid not null references identity_account(id),
 capability varchar(40) not null check(capability in ('DRAFT_REQUEST','DRAFT_OFFER','NEW_REQUEST','NEW_PROVIDER_PARTICIPATION')),
 title varchar(160) not null, body varchar(12000) not null, tags text[] not null, province varchar(80) not null, area varchar(80) not null,
 modes text[] not null check(cardinality(modes) between 1 and 3 and modes <@ array['LOCAL','REMOTE','SHIPPED']::text[]),
 availability varchar(300) not null, amount numeric(14,2) check(amount>=0), deadline date, badge_opt_in boolean not null,
 review_text varchar(32768) not null check(octet_length(review_text)<=32768),
 declared_label varchar(10) not null check(declared_label in ('GENERAL','SENSITIVE','ADULT')),
 digest char(64) not null check(digest ~ '^[a-f0-9]{64}$'), created_at timestamptz not null,
 search_vector tsvector generated always as (setweight(to_tsvector('spanish'::regconfig,title),'A') || setweight(to_tsvector('spanish'::regconfig,body),'B')) stored,
 unique(resource_id,id), check(cardinality(tags)<=8)
);
alter table marketplace_resource add foreign key(id,current_revision) references marketplace_revision(resource_id,id) deferrable initially deferred;
alter table marketplace_resource add foreign key(id,approved_revision) references marketplace_revision(resource_id,id) deferrable initially deferred;
create index marketplace_spanish_search on marketplace_revision using gin(search_vector);
create index marketplace_tags on marketplace_revision using gin(tags);
create index marketplace_modes on marketplace_revision using gin(modes);
create index marketplace_area on marketplace_revision(province,area);
create index marketplace_public on marketplace_resource(type,id) where lifecycle='OPEN' and approved_revision is not null;
create table marketplace_attachment (
 revision_id uuid not null references marketplace_revision(id), file_id uuid not null references files_artifact(id), digest char(64) not null,
 primary key(revision_id,file_id)
);
create table marketplace_receipt (
 actor_id uuid not null references identity_account(id), command_id uuid not null, operation varchar(20) not null,
 digest char(64) not null, resource_id uuid not null references marketplace_resource(id), revision_id uuid not null references marketplace_revision(id),
 primary key(actor_id,command_id)
);
create trigger marketplace_revision_immutable before update or delete on marketplace_revision for each row execute function moderation_immutable();
create trigger marketplace_attachment_immutable before update or delete on marketplace_attachment for each row execute function moderation_immutable();
create trigger marketplace_receipt_immutable before update or delete on marketplace_receipt for each row execute function moderation_immutable();
create function marketplace_binding_immutable() returns trigger language plpgsql as $$ begin
 if row(old.id,old.subject_id,old.creator_id,old.type) is distinct from row(new.id,new.subject_id,new.creator_id,new.type) then raise exception 'immutable marketplace binding'; end if;
 if old.lifecycle='CLOSED' and new.lifecycle<>'CLOSED' then raise exception 'closed marketplace resource'; end if;
 return new;
end $$;
create trigger marketplace_binding_immutable before update on marketplace_resource for each row execute function marketplace_binding_immutable();
