-- Rates in millionths: 83.250000 INR per USD is stored as 83250000.
create table fx_rates (
    base        varchar(3) not null,
    quote       varchar(3) not null,
    rate_micros bigint     not null check (rate_micros > 0),
    primary key (base, quote)
);

insert into fx_rates (base, quote, rate_micros) values
    ('USD', 'INR', 83250000),
    ('INR', 'USD', 12012),
    ('EUR', 'INR', 90100000),
    ('INR', 'EUR', 11098);

create table fx_transfers (
    id               uuid         primary key,
    idempotency_key  varchar(128) not null unique,
    from_owner       varchar(64)  not null,
    to_owner         varchar(64)  not null,
    from_currency    varchar(3)   not null,
    to_currency      varchar(3)   not null,
    source_amount    bigint       not null check (source_amount > 0),
    target_amount    bigint       not null check (target_amount > 0),
    rate_micros      bigint       not null,
    status           varchar(16)  not null,
    reason           varchar(64),
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now()
);

create index fx_transfers_open on fx_transfers (status, updated_at)
    where status in ('STARTED', 'DEBITED', 'COMPENSATING');
