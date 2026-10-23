create table files_blob_binding (
 file_id uuid primary key references files_artifact(id),
 backend varchar(2) not null check (backend = 'R2'),
 store_id char(64) not null check (store_id ~ '^[a-f0-9]{64}$'),
 object_key varchar(50) not null check (object_key = 'private-blobs/' || file_id::text),
 etag varchar(256) not null check (length(etag) between 1 and 256 and etag ~ '^[!-~]+$'),
 unique (store_id, object_key)
);
create trigger files_blob_binding_immutable before update or delete on files_blob_binding for each row execute function moderation_immutable();
