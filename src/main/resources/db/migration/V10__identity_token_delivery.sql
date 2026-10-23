create table identity_token_delivery (
 id uuid primary key,
 token_id uuid not null unique references identity_contact_token(id),
 account_id uuid not null references identity_account(id),
 purpose varchar(20) not null check (purpose in ('CONTACT','RECOVERY')),
 generation bigint not null,
 security_epoch bigint not null,
 expires_at timestamptz not null,
 state varchar(20) not null check (state in ('PENDING','DISPATCHING','UNKNOWN','ACCEPTED','DELIVERED','FAILED','CANCELLED')),
 created_at timestamptz not null,
 available_at timestamptz,
 dispatched_at timestamptz,
 uncertain_at timestamptz,
 accepted_at timestamptz,
 delivered_at timestamptz,
 observed_at timestamptz,
 attempts integer not null default 0 check (attempts between 0 and 3),
 observations integer not null default 0 check (observations between 0 and 6),
 lease_generation bigint not null default 0,
 lease_token uuid,
 lease_until timestamptz,
 provider_id uuid,
 reason varchar(60) not null,
 invalid_reason varchar(40),
 check ((lease_token is null) = (lease_until is null)),
 check (state <> 'CANCELLED' or dispatched_at is null),
 check (state not in ('ACCEPTED','DELIVERED') or (provider_id is not null and accepted_at is not null)),
 check (state <> 'DELIVERED' or delivered_at is not null)
);
create index identity_token_delivery_due on identity_token_delivery(available_at,id) where available_at is not null;
create function identity_delivery_history() returns trigger language plpgsql as $$
begin
 if (old.id,old.token_id,old.account_id,old.purpose,old.generation,old.security_epoch,old.expires_at,old.created_at)
  is distinct from (new.id,new.token_id,new.account_id,new.purpose,new.generation,new.security_epoch,new.expires_at,new.created_at)
  or (old.dispatched_at is not null and old.dispatched_at is distinct from new.dispatched_at)
  or (old.uncertain_at is not null and old.uncertain_at is distinct from new.uncertain_at)
  or (old.accepted_at is not null and old.accepted_at is distinct from new.accepted_at)
  or (old.delivered_at is not null and old.delivered_at is distinct from new.delivered_at)
  or (old.provider_id is not null and old.provider_id is distinct from new.provider_id)
  or new.attempts < old.attempts or new.observations < old.observations or new.lease_generation < old.lease_generation then
  raise exception 'Token delivery binding and effect history are immutable';
 end if;
 return new;
end $$;
create trigger identity_delivery_immutable before update on identity_token_delivery for each row execute function identity_delivery_history();
