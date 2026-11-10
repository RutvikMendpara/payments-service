import { userManager } from './auth';

export type Account = {
  id: number;
  ownerId: string;
  asset: string;
  kind: 'CUSTOMER' | 'SYSTEM';
  balance: number;
  version: number;
};

export type Settlement = {
  tradeId: string;
  symbol: string;
  price: number;
  quantity: number;
  buyerId: string;
  sellerId: string;
  status: 'PENDING' | 'SETTLED' | 'FAILED';
  reason: string | null;
  attempts: number;
  ledgerTransactionId: number | null;
  updatedAt: string;
};

export type ReconRun = { runId: number; status: string; mismatchCount: number; finishedAt?: string };

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const user = await userManager.getUser();
  const res = await fetch(path, {
    ...init,
    headers: { Authorization: `Bearer ${user?.access_token}`, ...(init.headers || {}) },
  });
  if (res.status === 401) {
    await userManager.signinRedirect();
  }
  if (!res.ok) {
    throw new Error(`${res.status} ${path}`);
  }
  return res.json() as Promise<T>;
}

export const api = {
  accounts: (owner: string) =>
    call<Account[]>(`/api/ledger/accounts?size=500${owner ? `&ownerId=${encodeURIComponent(owner)}` : ''}`),
  stats: () => call<Record<string, number>>('/api/settlement/settlements/stats'),
  settlements: (status?: string) =>
    call<Settlement[]>(`/api/settlement/settlements?size=25${status ? `&status=${status}` : ''}`),
  retry: (tradeId: string) =>
    call<Settlement>(`/api/settlement/settlements/${encodeURIComponent(tradeId)}/retry`, { method: 'POST' }),
  ledgerRecon: () => call<ReconRun>('/api/ledger/reconciliation/runs/latest').catch(() => null),
  settlementRecon: () => call<ReconRun>('/api/settlement/reconciliation/runs/latest').catch(() => null),
};

const CURRENCIES = new Set(['INR', 'USD', 'EUR', 'GBP']);

/** Cash is stored in minor units (paise); securities in whole shares. */
export function formatAmount(amount: number, asset: string) {
  if (CURRENCIES.has(asset)) {
    return (amount / 100).toLocaleString('en-IN', { style: 'currency', currency: asset });
  }
  return `${amount.toLocaleString('en-IN')} sh`;
}
