create table accounts (
    id          bigserial primary key,
    owner_id    varchar(64)  not null,
    asset       varchar(16)  not null,
    kind        varchar(16)  not null default 'CUSTOMER',
    status      varchar(16)  not null default 'ACTIVE',
    created_at  timestamptz  not null default now(),
    unique (owner_id, asset)
);

-- One row per account, updated in the same transaction as the entries it summarises.
-- version is the optimistic lock. System accounts (money coming in from outside the
-- ledger) are the only ones allowed to go negative.
create table balances (
    account_id     bigint  primary key references accounts (id),
    balance        bigint  not null default 0,
    allow_negative boolean not null default false,
    version        bigint  not null default 0,
    constraint balance_not_negative check (allow_negative or balance >= 0)
);

create table transactions (
    id               bigserial primary key,
    idempotency_key  varchar(128) not null unique,
    request_hash     char(64)     not null,
    type             varchar(32)  not null,
    status           varchar(16)  not null,
    reason           varchar(64),
    reference        varchar(128),
    created_at       timestamptz  not null default now()
);

create table ledger_entries (
    id              bigserial primary key,
    transaction_id  bigint      not null references transactions (id),
    account_id      bigint      not null references accounts (id),
    asset           varchar(16) not null,
    direction       varchar(6)  not null check (direction in ('DEBIT', 'CREDIT')),
    amount          bigint      not null check (amount > 0),
    created_at      timestamptz not null default now()
);

create index ledger_entries_account on ledger_entries (account_id, id);
create index ledger_entries_transaction on ledger_entries (transaction_id);

-- Entries are history. Corrections are new reversing transactions, never edits.
create function ledger_entries_append_only() returns trigger as $$
begin
    raise exception 'ledger_entries is append-only';
end;
$$ language plpgsql;

create trigger ledger_entries_no_update_delete
    before update or delete on ledger_entries
    for each row execute function ledger_entries_append_only();

-- Last line of defence for double-entry: checked at commit, so a transaction whose
-- debits and credits differ for any asset cannot be committed, whatever the app does.
create function ledger_transaction_balanced() returns trigger as $$
begin
    if exists (
        select 1 from ledger_entries
        where transaction_id = new.transaction_id
        group by asset
        having sum(case when direction = 'DEBIT' then amount else -amount end) <> 0
    ) then
        raise exception 'transaction % is not balanced', new.transaction_id;
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger ledger_entries_balanced
    after insert on ledger_entries
    deferrable initially deferred
    for each row execute function ledger_transaction_balanced();

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
