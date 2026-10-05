create table identity_account (
 id uuid primary key, contact varchar(254) not null unique,
 password_hash varchar(255) not null, contact_verified boolean not null default false,
 security_epoch bigint not null default 0, contact_generation bigint not null default 0,
 recovery_generation bigint not null default 0, sensitive_preference boolean not null default false,
 adult_preference boolean not null default false, version bigint not null default 0,
 check (contact = lower(contact) and contact ~ '^[a-z0-9._+%-]+@[a-z0-9.-]+\.test$')
);
create table identity_account_role (
 account_id uuid not null references identity_account(id), role varchar(40) not null,
 primary key (account_id, role), check (role in ('IDENTITY_ADMIN','RESTRICTION_ADMIN','AUDIT_READER'))
);
create table identity_verification_evidence (
 id uuid primary key, account_id uuid not null references identity_account(id),
 kind varchar(40) not null, age integer, expires_at timestamptz not null,
 scope varchar(40) not null check (scope = 'SANDBOX_SYNTHETIC'), provenance varchar(100) not null,
 created_at timestamptz not null, check (age is null or age between 0 and 120)
);
create index identity_evidence_account on identity_verification_evidence(account_id,kind);
create table identity_contact_token (
 id uuid primary key, account_id uuid not null references identity_account(id),
 purpose varchar(20) not null check (purpose in ('CONTACT','RECOVERY')),
 token_hash varchar(64) not null unique, generation bigint not null,
 expires_at timestamptz not null, consumed boolean not null default false
);
create index identity_token_account on identity_contact_token(account_id,purpose);
create table identity_guardian_link (
 id uuid primary key, guardian_id uuid not null references identity_account(id),
 minor_id uuid not null references identity_account(id),
 status varchar(20) not null check (status in ('PENDING','VERIFIED','REJECTED','REVOKED')),
 authority_expires_at timestamptz, provenance varchar(100), version bigint not null default 0,
 check (guardian_id <> minor_id)
);
create unique index identity_one_guardian on identity_guardian_link(minor_id) where status in ('PENDING','VERIFIED');
create table identity_job_consent (
 id uuid primary key, link_id uuid not null references identity_guardian_link(id),
 job_id uuid not null, mode varchar(20) not null check (mode = 'LOCAL'),
 status varchar(20) not null check (status in ('ACTIVE','REVOKED')), version bigint not null default 0
);
create unique index identity_active_consent on identity_job_consent(link_id,job_id,mode) where status = 'ACTIVE';
create table identity_restriction (
 id uuid primary key, account_id uuid not null references identity_account(id),
 active boolean not null, reason varchar(40) not null, created_at timestamptz not null
);
create index identity_restriction_account on identity_restriction(account_id,active);
create table identity_admin_mfa (
 account_id uuid primary key references identity_account(id), encrypted_secret text not null,
 last_counter bigint not null default -1
);
create table identity_audit_event (
 id uuid primary key, actor_id uuid references identity_account(id), subject_id uuid references identity_account(id),
 action varchar(60) not null, occurred_at timestamptz not null, scope varchar(40) not null
);
create function identity_immutable_audit() returns trigger language plpgsql as $$
begin raise exception 'Identity audit is append-only'; end $$;
create trigger identity_audit_immutable before update or delete on identity_audit_event for each row execute function identity_immutable_audit();
create function identity_immutable_participants() returns trigger language plpgsql as $$
begin
 if old.guardian_id <> new.guardian_id or old.minor_id <> new.minor_id then
  raise exception 'Guardian participants are immutable';
 end if;
 return new;
end $$;
create trigger identity_link_participants before update on identity_guardian_link for each row execute function identity_immutable_participants();
