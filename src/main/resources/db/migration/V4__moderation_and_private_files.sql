create table workbench_resource (
 id uuid primary key, kind varchar(40) not null check(kind in ('REQUEST_BODY','PRIVATE_MESSAGE')), actor_id uuid not null references identity_account(id),
 subject_id uuid not null references identity_account(id), capability varchar(40) not null check (capability in ('NEW_REQUEST','DRAFT_REQUEST','SEND_MARKETPLACE_MESSAGE')),
 audience varchar(10) not null check (audience in ('PUBLIC','PRIVATE')), current_revision uuid, approved_revision uuid, version bigint not null default 0
);
create table workbench_member (
 resource_id uuid not null references workbench_resource(id), account_id uuid not null references identity_account(id), primary key(resource_id,account_id)
);
create table workbench_revision (
 id uuid primary key, resource_id uuid not null references workbench_resource(id), text varchar(32768) not null,
 declared_label varchar(10) not null check (declared_label in ('GENERAL','SENSITIVE','ADULT')), digest char(64) not null check(digest ~ '^[a-f0-9]{64}$'),
 created_at timestamptz not null, unique(resource_id,id)
);
alter table workbench_resource add foreign key(id,current_revision) references workbench_revision(resource_id,id) deferrable initially deferred;
alter table workbench_resource add foreign key(id,approved_revision) references workbench_revision(resource_id,id) deferrable initially deferred;
create table files_artifact (
 id uuid primary key, command_id uuid not null, actor_id uuid not null references identity_account(id), resource_id uuid not null, revision_id uuid not null,
 owner_kind varchar(40) not null, digest char(64) not null check(digest ~ '^[a-f0-9]{64}$'), media_type varchar(80) not null, size bigint not null check(size between 1 and 1048576),
 safe boolean not null, reason varchar(40) not null, created_at timestamptz not null, unique(actor_id,command_id)
);
create table workbench_attachment (
 revision_id uuid not null references workbench_revision(id), file_id uuid not null references files_artifact(id), digest char(64) not null,
 primary key(revision_id,file_id)
);
create table moderation_reference (
 id uuid primary key, owner_kind varchar(40) not null, resource_id uuid not null, unique(owner_kind,resource_id)
);
create table moderation_snapshot (
 id uuid primary key, reference_id uuid not null references moderation_reference(id), revision_id uuid not null,
 actor_id uuid not null references identity_account(id), subject_id uuid not null references identity_account(id), capability varchar(40) not null,
 job_id uuid, job_mode varchar(10), text varchar(32768) not null, declared_label varchar(10) not null check(declared_label in ('GENERAL','SENSITIVE','ADULT')),
 digest char(64) not null check(digest ~ '^[a-f0-9]{64}$'), created_at timestamptz not null, unique(reference_id,revision_id),
 check((job_id is null and job_mode is null) or (job_id is not null and job_mode is not null and job_mode in ('LOCAL','REMOTE','SHIPPED')))
);
create table moderation_snapshot_attachment (
 snapshot_id uuid not null references moderation_snapshot(id), file_id uuid not null references files_artifact(id), digest char(64) not null,
 primary key(snapshot_id,file_id)
);
create table moderation_submission (
 id uuid primary key references moderation_snapshot(id), state varchar(32) not null check(state in ('PENDING','MANUAL_REVIEW','APPROVED','REJECTED','FAILED','AWAITING_ELIGIBILITY','RECALLED')),
 label varchar(10) not null check(label in ('GENERAL','SENSITIVE','ADULT')), reason varchar(40) not null,
 recalled boolean not null default false, decision_id uuid, generation bigint not null default 0, version bigint not null default 0
);
create table moderation_decision (
 id uuid primary key, submission_id uuid not null references moderation_submission(id), actor_id uuid references identity_account(id),
 action varchar(10) not null check(action in ('APPROVE','HOLD','REJECT','RECALL')), label varchar(10) not null check(label in ('GENERAL','SENSITIVE','ADULT')),
 reason varchar(40) not null, policy varchar(40) not null, model varchar(40) not null, scope varchar(40) not null check(scope='LOCAL_SYNTHETIC'), created_at timestamptz not null
);
alter table moderation_submission add foreign key(decision_id) references moderation_decision(id);
create table moderation_task (
 id uuid primary key, submission_id uuid not null references moderation_submission(id), reference_id uuid not null references moderation_reference(id),
 kind varchar(10) not null check(kind in ('REVIEW','ACTIVATE')), generation bigint not null,
 state varchar(16) not null check(state in ('PENDING','CLAIMED','RETRY_WAIT','COMPLETE','FAILED')),
 attempts integer not null default 0 check(attempts between 0 and 3), claim_token uuid, lease_until timestamptz, available_at timestamptz not null,
 reason varchar(40) not null, decision_id uuid references moderation_decision(id), unique(submission_id,kind,generation)
);
create index moderation_task_claim on moderation_task(state,available_at);
create table moderation_signal (
 id uuid primary key, reference_id uuid not null references moderation_reference(id), snapshot_id uuid not null references moderation_snapshot(id),
 decision_id uuid not null unique references moderation_decision(id), event_type varchar(20) not null check(event_type='ACTIVATED'), version bigint not null, created_at timestamptz not null
);
create table moderation_case (
 id uuid primary key, reference_id uuid not null references moderation_reference(id), submission_id uuid not null references moderation_submission(id),
 reporter_id uuid not null references identity_account(id), kind varchar(10) not null check(kind in ('REPORT','APPEAL')),
 reason varchar(40) not null, note varchar(2000) not null, state varchar(16) not null check(state in ('OPEN','OWNED','RESOLVED')),
 owner_id uuid references identity_account(id), version bigint not null default 0, created_at timestamptz not null
);
create table moderation_evidence (
 id uuid primary key, reference_id uuid not null references moderation_reference(id), submission_id uuid not null references moderation_submission(id),
 kind varchar(10) not null check(kind in ('WARNING','STRIKE')), actor_id uuid not null references identity_account(id), reason varchar(40) not null,
 withdrawn boolean not null default false, version bigint not null default 0, created_at timestamptz not null
);
create table moderation_audit (
 id uuid primary key, actor_id uuid references identity_account(id), reference_id uuid references moderation_reference(id), submission_id uuid references moderation_submission(id),
 case_id uuid references moderation_case(id), action varchar(40) not null, created_at timestamptz not null
);
create table moderation_receipt (
 actor_id uuid not null references identity_account(id), command_id uuid not null, operation varchar(20) not null, digest char(64) not null,
 outcome_id uuid not null, primary key(actor_id,command_id)
);
create table workbench_receipt (
 actor_id uuid not null references identity_account(id), command_id uuid not null, digest char(64) not null, resource_id uuid not null references workbench_resource(id),
 revision_id uuid not null references workbench_revision(id), primary key(actor_id,command_id)
);
create function moderation_immutable() returns trigger language plpgsql as $$ begin raise exception 'immutable content record'; end $$;
create trigger workbench_revision_immutable before update or delete on workbench_revision for each row execute function moderation_immutable();
create trigger workbench_attachment_immutable before update or delete on workbench_attachment for each row execute function moderation_immutable();
create trigger files_artifact_immutable before update or delete on files_artifact for each row execute function moderation_immutable();
create trigger moderation_reference_immutable before update or delete on moderation_reference for each row execute function moderation_immutable();
create trigger moderation_snapshot_immutable before update or delete on moderation_snapshot for each row execute function moderation_immutable();
create trigger moderation_snapshot_attachment_immutable before update or delete on moderation_snapshot_attachment for each row execute function moderation_immutable();
create trigger moderation_decision_immutable before update or delete on moderation_decision for each row execute function moderation_immutable();
create trigger moderation_signal_immutable before update or delete on moderation_signal for each row execute function moderation_immutable();
create trigger moderation_audit_immutable before update or delete on moderation_audit for each row execute function moderation_immutable();
create trigger moderation_receipt_immutable before update or delete on moderation_receipt for each row execute function moderation_immutable();
create trigger workbench_receipt_immutable before update or delete on workbench_receipt for each row execute function moderation_immutable();
create function workbench_binding_immutable() returns trigger language plpgsql as $$ begin
 if row(old.id,old.kind,old.actor_id,old.subject_id,old.capability,old.audience) is distinct from row(new.id,new.kind,new.actor_id,new.subject_id,new.capability,new.audience) then raise exception 'immutable resource binding'; end if;
 return new;
end $$;
create trigger workbench_binding_immutable before update on workbench_resource for each row execute function workbench_binding_immutable();
