/**
 * The shape of one five-minute window's price, in chart coordinates.
 *
 * Polymarket's own chart answers one question — is the price above the line it
 * has to beat, and by how much — so the geometry here is built around that
 * line rather than around the data. The target is always inside the drawn
 * range, even when the price has run a long way from it, because a chart that
 * scrolls its own reference off the top is a chart that stops answering.
 */

/** Every 5-minute window, in seconds. */
export const WINDOW_SEC = 300;

/** One reading: milliseconds since the epoch, and the price at that moment. */
export type PricePoint = [number, number];

export interface ChartShape {
  /** The price path, as an SVG `d`. */
  path: string;
  /** The same path closed to the floor, for the fill under it. */
  area: string;
  /** Where the price the window must beat sits, in chart units. */
  targetY: number;
  /** The newest reading, or null when the window has no readings yet. */
  last: { x: number; y: number; value: number } | null;
  /** The drawn range, low first. */
  low: number;
  high: number;
}

/**
 * Widens a range that would otherwise draw the line flat against an edge.
 *
 * A window that has barely moved has a span of pennies, and a span of pennies
 * magnifies noise into a mountain range; a window whose price sits exactly on
 * its target has no span at all.
 */
function rangeFor(values: number[], target: number): [number, number] {
  const all = target > 0 ? [...values, target] : values;
  let low = Math.min(...all);
  let high = Math.max(...all);
  const span = high - low;
  // Nothing to scale against: give the line somewhere to sit.
  if (!(span > 1e-9)) return [low - 1, high + 1];
  const pad = span * 0.12;
  low -= pad;
  high += pad;
  return [low, high];
}

/**
 * Lays a window's readings out over the full five minutes.
 *
 * The x axis is the window, not the data: a window three minutes in fills
 * three fifths of the width and grows into the rest, which is how the site
 * draws it and the only way the distance to the right edge means "time left".
 */
export function shapeWindow(
  points: PricePoint[],
  windowStart: number,
  target: number,
  width: number,
  height: number,
  /** Room kept clear at the right edge, so the live dot is not half a dot. */
  insetRight = 0,
): ChartShape | null {
  const clean = points.filter(
    ([t, v]) => Number.isFinite(t) && Number.isFinite(v) && v > 0,
  );
  if (clean.length === 0 && !(target > 0)) return null;

  const [low, high] = rangeFor(
    clean.map(([, v]) => v),
    target,
  );
  const span = high - low;

  const span_x = Math.max(1, width - insetRight);
  const x = (ms: number) => {
    const at = (ms / 1000 - windowStart) / WINDOW_SEC;
    return Math.max(0, Math.min(1, at)) * span_x;
  };
  const y = (value: number) => ((high - value) / span) * height;

  let path = '';
  for (const [t, v] of clean) {
    path += `${path ? 'L' : 'M'}${x(t).toFixed(1)} ${y(v).toFixed(1)}`;
  }

  const tail = clean[clean.length - 1];
  const last = tail
    ? { x: x(tail[0]), y: y(tail[1]), value: tail[1] }
    : null;

  const head = clean[0];
  const area =
    path && head && last
      ? `${path}L${last.x.toFixed(1)} ${height}L${x(head[0]).toFixed(1)} ${height}Z`
      : '';

  return { path, area, targetY: y(target), last, low, high };
}

/** A price difference the way a desk says it: signed, whole dollars. */
export function signedPrice(delta: number): string {
  const sign = delta > 0 ? '+' : delta < 0 ? '−' : '';
  return `${sign}${Math.round(Math.abs(delta)).toLocaleString('ru-RU')}`;
}

/** A BTC price with a thin space between thousands, as the site shows it. */
export function bigPrice(value: number): string {
  return Math.round(value).toLocaleString('ru-RU');
}
