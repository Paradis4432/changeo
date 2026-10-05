alter table identity_account_role drop constraint identity_account_role_role_check;
alter table identity_account_role add constraint identity_account_role_role_check
 check (role in ('IDENTITY_ADMIN','RESTRICTION_ADMIN','AUDIT_READER','MODERATION_REVIEWER','MODERATION_AUDITOR'));
