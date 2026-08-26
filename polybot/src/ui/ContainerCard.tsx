import {
  removeReserve,
  type Container,
  type ContainerSplit,
} from '../core/container';
import { usd } from '../core/money';

/**
 * What the container holds back, and what that leaves to trade with.
 *
 * It moved here from the balance sheet, which is a record of what happened —
 * the container is a decision about what may happen next, and it belongs with
 * the switch that turns it on rather than behind a chart.
 */
export function ContainerCard({
  container,
  split,
  onChange,
}: {
  container: Container;
  split: ContainerSplit;
  onChange: (next: Container) => void;
}) {
  return (
    <div className="goal">
      <div className="goal-head">
        <span>Контейнер</span>
        <span className="muted">
          {usd(split.locked)} заперто · <b className="up">{usd(split.free)}</b> в торговле
        </span>
      </div>

      <div className="pcts spanrow" style={{ marginTop: 8 }}>
        {[20, 30, 40, 50].map((pct) => (
          <button
            key={pct}
            className={Math.round(container.corePct * 100) === pct ? 'on' : undefined}
            onClick={() => onChange({ ...container, corePct: pct / 100 })}
          >
            {pct}%
          </button>
        ))}
      </div>

      <div className="row">
        <span className="label">Неприкосновенные {Math.round(container.corePct * 100)}%</span>
        <span className="value">{usd(split.core)}</span>
      </div>

      {container.reserves.map((r) => (
        <div className="row" key={r.id}>
          <span className="label">{r.name}</span>
          <span className="value">
            {usd(r.usd)}
            <button
              className="xbtn"
              style={{ marginLeft: 8 }}
              onClick={() => onChange(removeReserve(container, r.id))}
              aria-label="Убрать"
            >
              ✕
            </button>
          </span>
        </div>
      ))}

    </div>
  );
}
