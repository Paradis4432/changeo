create function identity_immutable_consent_reference() returns trigger language plpgsql as $$
begin
 if old.link_id <> new.link_id or old.job_id <> new.job_id or old.mode <> new.mode then
  raise exception 'Consent reference is immutable';
 end if;
 if old.status = 'REVOKED' and new.status <> 'REVOKED' then
  raise exception 'Consent revocation is terminal';
 end if;
 return new;
end $$;
create trigger identity_consent_reference before update on identity_job_consent for each row execute function identity_immutable_consent_reference();
alter table identity_verification_evidence add column ordinal bigint generated always as identity;
create function identity_terminal_link() returns trigger language plpgsql as $$
begin
 if old.status in ('REVOKED','REJECTED') and new.status <> old.status then
  raise exception 'Guardian terminal state cannot be revived';
 end if;
 return new;
end $$;
create trigger identity_terminal_link before update on identity_guardian_link for each row execute function identity_terminal_link();
create function identity_immutable_token() returns trigger language plpgsql as $$
begin
 if old.account_id <> new.account_id or old.purpose <> new.purpose or old.token_hash <> new.token_hash or old.generation <> new.generation or old.expires_at <> new.expires_at or (old.consumed and not new.consumed) then
  raise exception 'Token binding and consumption are immutable';
 end if;
 return new;
end $$;
create trigger identity_immutable_token before update on identity_contact_token for each row execute function identity_immutable_token();
create trigger identity_evidence_immutable before update or delete on identity_verification_evidence for each row execute function identity_immutable_audit();
create function identity_immutable_contact() returns trigger language plpgsql as $$
begin
 if old.contact <> new.contact then raise exception 'Synthetic contact is immutable'; end if;
 return new;
end $$;
create trigger identity_immutable_contact before update on identity_account for each row execute function identity_immutable_contact();
