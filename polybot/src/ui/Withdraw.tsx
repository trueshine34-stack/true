import { useCallback, useEffect, useState } from 'react';
import {
  DEFAULT_WITHDRAW_ADDRESS,
  loadWithdrawAddress,
  looksLikeAddress,
  saveWithdrawAddress,
  shortAddress,
  withdrawable,
} from '../core/withdraw';
import { PolyBot, type WalletInfo } from '../native/polybot';

/**
 * Taking money off the exchange.
 *
 * One thing this panel will not do is pretend. The balance is bridged USDC on
 * Polygon; a transfer moves it on Polygon and nowhere else. The saved address
 * is the same one on every EVM chain, so the money lands in the same wallet —
 * but as USDC on Polygon, and turning that into USDT on BSC is a swap that
 * wallet has to make. Saying so on the screen costs one line and saves the
 * assumption that this bridges, which it does not.
 */
export function Withdraw({ onClose }: { onClose: () => void }) {
  const [info, setInfo] = useState<WalletInfo | null>(null);
  const [address, setAddress] = useState(DEFAULT_WITHDRAW_ADDRESS);
  const [amount, setAmount] = useState('');
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void loadWithdrawAddress().then(setAddress);
  }, []);

  const read = useCallback(() => {
    setError(null);
    PolyBot.walletInfo()
      .then((r) => {
        setInfo(r);
        // The whole balance is what a withdrawal usually means; it stays
        // editable for the times it does not.
        setAmount((current) =>
          current === '' ? withdrawable(r.usdc).toFixed(2) : current,
        );
      })
      .catch((e: unknown) =>
        setError(e instanceof Error ? e.message : String(e)),
      );
  }, []);

  useEffect(read, [read]);

  const value = Number(amount.replace(',', '.'));
  const valid =
    Number.isFinite(value) &&
    value > 0 &&
    info != null &&
    value <= withdrawable(info.usdc) + 1e-9 &&
    looksLikeAddress(address);

  const send = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      await saveWithdrawAddress(address);
      const r = await PolyBot.walletWithdraw({ to: address, amount: value });
      setSent(r.hash);
      setConfirming(false);
      read();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  }, [address, value, read]);

  const copy = useCallback(() => {
    void navigator.clipboard
      ?.writeText(address)
      .then(() => setNote('Адрес скопирован'))
      .catch(() => setNote(address));
  }, [address]);

  return (
    <div className="sheet-scrim" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head">
          <h2>Вывод</h2>
          <button className="xbtn" onClick={onClose} aria-label="Закрыть">
            ✕
          </button>
        </div>

        {error && <div className="banner error">{error}</div>}
        {sent && (
          <div className="banner info">
            Отправлено. Транзакция&nbsp;
            <span className="mono">{shortAddress(sent)}</span> — деньги придут,
            когда Polygon её подтвердит.
          </div>
        )}

        <div className="row">
          <span className="label">На кошельке</span>
          <span className="value">
            {info ? `${info.usdc.toFixed(2)} $` : '…'}
          </span>
        </div>
        <div className="row">
          <span className="label">Газ</span>
          <span className={info && info.gas < info.fee ? 'value warn' : 'value'}>
            {info ? `${info.gas.toFixed(3)} POL` : '…'}
            {info && info.fee > 0 && (
              <span className="muted"> · перевод ≈ {info.fee.toFixed(3)}</span>
            )}
          </span>
        </div>

        <div className="row wrap">
          <span className="label">Адрес</span>
          {editing ? (
            <input
              className="addr"
              value={address}
              spellCheck={false}
              onChange={(e) => setAddress(e.target.value.trim())}
              onBlur={() => setEditing(false)}
            />
          ) : (
            <button className="linkish mono" onClick={() => setEditing(true)}>
              {shortAddress(address)}
            </button>
          )}
        </div>

        <div className="draftrow">
          <label className="mini">
            <span>сумма, $</span>
            <input
              type="number"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
            />
          </label>
          <div className="pcts">
            {[25, 50, 100].map((pct) => (
              <button
                key={pct}
                disabled={!info || info.usdc <= 0}
                onClick={() =>
                  info &&
                  setAmount(withdrawable((info.usdc * pct) / 100).toFixed(2))
                }
              >
                {pct}%
              </button>
            ))}
          </div>
        </div>

        {/*
          The one thing that is easy to get wrong here, said before the button
          rather than after the transfer.
        */}
        <p className="muted fineprint">
          Уходит <b>USDC в сети Polygon</b> — это то, чем торгует Polymarket. На
          тот же адрес, но не USDT и не BSC: обменять на USDT нужно уже в
          кошельке. Мост внутри приложения не делается — угадывать чужой
          контракт с реальными деньгами нельзя.
        </p>

        {info?.note && <div className="banner warn">{info.note}</div>}

        {confirming ? (
          <div className="confirm">
            <div>
              Отправить <b>{value.toFixed(2)} USDC</b> на{' '}
              <span className="mono">{shortAddress(address)}</span>?
            </div>
            <div className="draftrow">
              <button className="primary compact" disabled={busy} onClick={send}>
                {busy ? 'Отправляю…' : 'Да, вывести'}
              </button>
              <button
                className="ghost compact"
                disabled={busy}
                onClick={() => setConfirming(false)}
              >
                Отмена
              </button>
            </div>
          </div>
        ) : (
          <div className="draftrow">
            <button
              className="primary"
              disabled={!valid || !info?.canSend || busy}
              onClick={() => setConfirming(true)}
            >
              Вывести
            </button>
            <button className="ghost compact narrow" onClick={copy} title="Скопировать адрес">
              ⧉
            </button>
          </div>
        )}

        {note && <div className="muted fineprint">{note}</div>}
      </div>
    </div>
  );
}
