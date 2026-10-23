create table moderation_preparation_dispatch (
 id uuid primary key,
 task_id uuid not null references moderation_task(id),
 submission_id uuid not null references moderation_snapshot(id),
 reference_id uuid not null references moderation_reference(id),
 revision_id uuid not null,
 generation bigint not null check(generation>=0),
 attempt_ordinal smallint not null check(attempt_ordinal between 1 and 3),
 claim_fingerprint char(64) not null check(claim_fingerprint ~ '^[a-f0-9]{64}$'),
 content_digest char(64) not null check(content_digest ~ '^[a-f0-9]{64}$'),
 manifest jsonb not null check(jsonb_typeof(manifest)='object' and octet_length(manifest::text)<=4096),
 manifest_digest char(64) not null check(manifest_digest ~ '^[a-f0-9]{64}$'),
 request_bytes integer not null check(request_bytes between 1 and 10485760),
 request_digest char(64) not null check(request_digest ~ '^[a-f0-9]{64}$'),
 wire_kind varchar(16) not null check(wire_kind in ('MODERATIONS','RESPONSES')),
 requested_model varchar(128) not null,
 schema_version varchar(64) not null check(schema_version='preparation-wire-v1'),
 prompt_version varchar(64) not null check(prompt_version='untrusted-content-v1'),
 draft_policy_version varchar(64) not null check(draft_policy_version='draft-observation-v1'),
 source varchar(32) not null check(source='LOCAL_PROTOCOL_FIXTURE'),
 endpoint_kind varchar(32) not null check(endpoint_kind='LITERAL_LOOPBACK'),
 dispatched_at timestamptz not null,
 foreign key(reference_id,revision_id) references moderation_snapshot(reference_id,revision_id),
 unique(task_id,attempt_ordinal,wire_kind),
 check((wire_kind='MODERATIONS' and requested_model='omni-moderation-latest') or (wire_kind='RESPONSES' and requested_model='gpt-6-luna'))
);
create table moderation_preparation_observation (
 dispatch_id uuid primary key references moderation_preparation_dispatch(id),
 outcome varchar(40) not null check(outcome in ('VALID','REFUSAL','INCOMPLETE','INVALID','UNAVAILABLE','UNKNOWN')),
 reason varchar(40) not null check(reason in ('PROTOCOL_VALID','REFUSAL','INCOMPLETE','PROTOCOL_INVALID','AUTHORIZATION','HTTP_REJECTED','REDIRECT','RESPONSE_BOUND','TRANSPORT','DEADLINE','INTERRUPTED','CLOSED','RATE_LIMIT','SERVER_ERROR')),
 http_status smallint check(http_status between 100 and 599),
 response_id varchar(128) check(response_id ~ '^[A-Za-z0-9_-]{1,128}$'),
 returned_model varchar(128) check(returned_model in ('omni-moderation-latest','gpt-6-luna')),
 response_digest char(64) check(response_digest ~ '^[a-f0-9]{64}$'),
 normalized jsonb not null check(jsonb_typeof(normalized)='object' and octet_length(normalized::text)<=16384),
 disposition varchar(24) not null check(disposition in ('APPLIED_TO_HOLD','HISTORICAL_ONLY')),
 received_at timestamptz not null,
 check((outcome='VALID' and response_id is not null and returned_model is not null and response_digest is not null and normalized<>'{}'::jsonb) or (outcome<>'VALID' and normalized='{}'::jsonb)),
 check(disposition<>'APPLIED_TO_HOLD' or outcome in ('VALID','REFUSAL','INCOMPLETE','INVALID'))
);
create trigger moderation_preparation_dispatch_immutable before update or delete on moderation_preparation_dispatch for each row execute function moderation_immutable();
create trigger moderation_preparation_observation_immutable before update or delete on moderation_preparation_observation for each row execute function moderation_immutable();
