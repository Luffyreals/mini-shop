-- V2: BR-003-1, spec 003 §7. Emails are always stored trimmed and lower-case; the service normalises
-- them, this CHECK rejects any write path that bypasses the service. Portable between PostgreSQL and H2.

alter table users add constraint ck_users_email_lowercase check (email = lower(trim(email)));
