create table settlements (
    trade_id               varchar(64)  primary key,
    symbol                 varchar(16)  not null,
    price                  bigint       not null check (price > 0),
    quantity               bigint       not null check (quantity > 0),
    buyer_id               varchar(64)  not null,
    seller_id              varchar(64)  not null,
    status                 varchar(16)  not null check (status in ('PENDING', 'SETTLED', 'FAILED')),
    reason                 varchar(64),
    attempts               int          not null default 1,
    ledger_transaction_id  bigint,
    executed_at            timestamptz,
    created_at             timestamptz  not null default now(),
    updated_at             timestamptz  not null default now()
);

create index settlements_status on settlements (status, updated_at);

-- Consumer-side dedupe: one row per trade event we have fully handled.
create table processed_events (
    event_id      varchar(64) primary key,
    processed_at  timestamptz not null default now()
);

create table outbox (
    id            bigserial primary key,
    event_id      uuid         not null unique,
    topic         varchar(128) not null,
    event_key     varchar(128) not null,
    event_type    varchar(64)  not null,
    payload       jsonb        not null,
    created_at    timestamptz  not null default now(),
    published_at  timestamptz
);

create index outbox_unpublished on outbox (id) where published_at is null;
