import { useCallback, useEffect, useState } from 'react';
import { api, formatAmount, type Account, type ReconRun, type Settlement } from './api';
import { userManager } from './auth';

const REFRESH_MS = 3000;

function usePolling<T>(load: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const run = useCallback(() => {
    load().then((d) => { setData(d); setError(null); }).catch((e) => setError(String(e)));
  }, deps); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    run();
    const id = setInterval(run, REFRESH_MS);
    return () => clearInterval(id);
  }, [run]);
  return { data, error, reload: run };
}

export function App({ username }: { username: string }) {
  const [owner, setOwner] = useState('');
  const stats = usePolling(api.stats, []);
  const accounts = usePolling(() => api.accounts(owner), [owner]);
  const recent = usePolling(() => api.settlements(), []);
  const failed = usePolling(() => api.settlements('FAILED'), []);
  const recon = usePolling(() => Promise.all([api.ledgerRecon(), api.settlementRecon()]), []);

  return (
    <div className="page">
      <header>
        <h1>Payments Ops</h1>
        <span className="muted">{username}</span>
        <button className="link" onClick={() => userManager.signoutRedirect()}>Sign out</button>
      </header>

      <section className="tiles">
        <Tile label="Settled" value={stats.data?.SETTLED} tone="good" />
        <Tile label="Failed" value={stats.data?.FAILED} tone={stats.data?.FAILED ? 'bad' : 'good'} />
        <Tile label="Pending" value={stats.data?.PENDING} tone={stats.data?.PENDING ? 'warn' : 'good'} />
        <ReconTile label="Ledger recon" run={recon.data?.[0] ?? null} />
        <ReconTile label="Settlement recon" run={recon.data?.[1] ?? null} />
      </section>

      <div className="columns">
        <section className="card">
          <div className="card-head">
            <h2>Accounts</h2>
            <input placeholder="Filter by owner, e.g. trader-3" value={owner} onChange={(e) => setOwner(e.target.value.trim())} />
          </div>
          {accounts.error && <p className="error">{accounts.error}</p>}
          <table>
            <thead><tr><th>ID</th><th>Owner</th><th>Asset</th><th className="num">Balance</th></tr></thead>
            <tbody>
              {(accounts.data ?? []).slice(0, 60).map((a: Account) => (
                <tr key={a.id} className={a.kind === 'SYSTEM' ? 'system' : ''}>
                  <td>{a.id}</td>
                  <td>{a.ownerId}</td>
                  <td>{a.asset}</td>
                  <td className="num">{formatAmount(a.balance, a.asset)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <div className="stack">
          <section className="card">
            <h2>Failed settlements</h2>
            {failed.data?.length === 0 && <p className="muted">None. Good.</p>}
            <table>
              <thead><tr><th>Trade</th><th>Details</th><th>Reason</th><th>Tries</th><th /></tr></thead>
              <tbody>
                {(failed.data ?? []).slice(0, 10).map((s) => (
                  <tr key={s.tradeId}>
                    <td className="mono" title={s.tradeId}>{s.tradeId.slice(0, 8)}</td>
                    <td>{s.buyerId} ← {s.quantity} {s.symbol} ← {s.sellerId}</td>
                    <td><span className="badge bad">{s.reason}</span></td>
                    <td>{s.attempts}</td>
                    <td><button onClick={() => api.retry(s.tradeId).finally(() => { failed.reload(); stats.reload(); })}>Retry</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section className="card">
            <h2>Recent settlements</h2>
            <table>
              <thead><tr><th>Trade</th><th>Symbol</th><th className="num">Qty</th><th className="num">Price</th><th>Status</th></tr></thead>
              <tbody>
                {(recent.data ?? []).slice(0, 12).map((s: Settlement) => (
                  <tr key={s.tradeId}>
                    <td className="mono" title={s.tradeId}>{s.tradeId.slice(0, 8)}</td>
                    <td>{s.symbol}</td>
                    <td className="num">{s.quantity}</td>
                    <td className="num">{formatAmount(s.price, 'INR')}</td>
                    <td><span className={`badge ${s.status === 'SETTLED' ? 'good' : s.status === 'FAILED' ? 'bad' : 'warn'}`}>{s.status}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </div>
      </div>
    </div>
  );
}

function Tile({ label, value, tone }: { label: string; value?: number; tone: 'good' | 'bad' | 'warn' }) {
  return (
    <div className={`tile ${tone}`}>
      <div className="tile-label">{label}</div>
      <div className="tile-value">{value === undefined ? '–' : value.toLocaleString('en-IN')}</div>
    </div>
  );
}

function ReconTile({ label, run }: { label: string; run: ReconRun | null }) {
  if (!run) return <div className="tile"><div className="tile-label">{label}</div><div className="tile-value muted">not run</div></div>;
  const clean = run.status === 'COMPLETED' && run.mismatchCount === 0;
  return (
    <div className={`tile ${clean ? 'good' : 'bad'}`}>
      <div className="tile-label">{label} #{run.runId}</div>
      <div className="tile-value">{clean ? 'clean' : `${run.mismatchCount} breaks`}</div>
    </div>
  );
}
