create table recon_runs (
    id           bigserial primary key,
    started_at   timestamptz not null default now(),
    finished_at  timestamptz,
    status       varchar(16) not null,
    mismatches   int         not null default 0
);

create table recon_mismatches (
    id          bigserial primary key,
    run_id      bigint      not null references recon_runs (id),
    check_name  varchar(32) not null,
    reference   varchar(64) not null,
    expected    bigint      not null,
    actual      bigint      not null,
    detail      varchar(256)
);

create index recon_mismatches_run on recon_mismatches (run_id);
