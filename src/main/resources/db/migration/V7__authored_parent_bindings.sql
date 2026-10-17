alter table conversations_authored add column listing_kind varchar(24) check(listing_kind in ('OFFER_BODY','REQUEST_BODY'));
alter table conversations_authored add check(thread_id is not null or listing_kind is not null);
create function conversations_authored_binding() returns trigger language plpgsql as $$ begin
 if to_jsonb(old)-array['current_revision','approved_revision','version'] is distinct from to_jsonb(new)-array['current_revision','approved_revision','version'] then raise exception 'immutable authored message binding'; end if;
 return new;
end $$;
create trigger conversations_authored_binding before update on conversations_authored for each row execute function conversations_authored_binding();
create function agreements_quote_binding() returns trigger language plpgsql as $$ begin
 if row(old.id,old.job_id,old.kind) is distinct from row(new.id,new.job_id,new.kind) then raise exception 'immutable quote binding'; end if;
 return new;
end $$;
create trigger agreements_quote_binding before update on agreements_quote for each row execute function agreements_quote_binding();
create function jobs_authored_binding() returns trigger language plpgsql as $$ begin
 if to_jsonb(old)-array['current_revision','approved_revision','version'] is distinct from to_jsonb(new)-array['current_revision','approved_revision','version'] then raise exception 'immutable job content binding'; end if;
 return new;
end $$;
create trigger jobs_authored_binding before update on jobs_authored for each row execute function jobs_authored_binding();
