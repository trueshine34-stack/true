import type { Candle } from './candles';

/**
 * Where the trading actually happened, as faint lines across the chart.
 *
 * The levels already drawn are the prices the market turned at — the few that
 * decide whether a move continues. This is the other half of the same picture
 * and a much quieter one: how much changed hands at each price over the whole
 * span on screen. Price moves through a band nothing traded in and slows in
 * one where everything did, because the orders that were there are what a move
 * has to eat through.
 *
 * Built from the candles already on screen rather than from a book snapshot:
 * the book shows a moment and only a few dollars either side of the mid, and
 * the question here is about hours and the whole range. A candle's volume is
 * spread evenly across the range it covered, which is the honest thing to do
 * with a bar that says how much traded but not where inside itself.
 */

/** One band of price, and how much of the span's volume went through it. */
export type Node = {
  /** The middle of the band. */
  price: number;
  /** Its share of the busiest band, 0..1 — what it is drawn at. */
  weight: number;
};

/** Bands across the range. Forty is about two pixels apiece on a phone. */
const BANDS = 40;

/** Below this share of the busiest band a line says nothing. */
const FLOOR = 0.35;

/**
 * The busiest bands, strongest first.
 *
 * Only local peaks: a shelf of volume twenty bands deep would otherwise draw
 * as twenty lines, which is a grey box rather than a level. A band is kept
 * when it holds more than both of its neighbours, so what is left is the
 * middle of each shelf.
 */
export function volumeNodes(candles: Candle[], keep = 8): Node[] {
  const clean = candles.filter(
    ([, , h, l, , v]) =>
      Number.isFinite(h) && Number.isFinite(l) && h >= l && h > 0 && (v ?? 0) > 0,
  );
  if (clean.length < 4) return [];

  let low = Infinity;
  let high = -Infinity;
  for (const [, , h, l] of clean) {
    if (l < low) low = l;
    if (h > high) high = h;
  }
  const span = high - low;
  if (!(span > 0)) return [];

  const step = span / BANDS;
  const volume = new Array<number>(BANDS).fill(0);

  for (const [, , h, l, , v] of clean) {
    const size = v ?? 0;
    const from = Math.max(0, Math.floor((l - low) / step));
    const to = Math.min(BANDS - 1, Math.floor((h - low) / step));
    const bands = to - from + 1;
    // A candle that never left one band puts everything in it; one that ran
    // through six shares itself out over the six.
    const each = size / bands;
    for (let i = from; i <= to; i++) volume[i] += each;
  }

  const most = Math.max(...volume);
  if (!(most > 0)) return [];

  const peaks: Node[] = [];
  for (let i = 0; i < BANDS; i++) {
    const weight = volume[i] / most;
    if (weight < FLOOR) continue;
    // The ends of the range are the ends of two wicks, never the middle of a
    // shelf — and treating "nothing outside" as zero would make both of them
    // peaks on every chart.
    const before = i > 0 ? volume[i - 1] : Infinity;
    const after = i < BANDS - 1 ? volume[i + 1] : Infinity;
    // A peak, and strictly one: a profile that is flat has no middle, and
    // drawing every band of it is a grey box rather than a level.
    if (!(volume[i] > before) || volume[i] < after) continue;
    peaks.push({ price: low + step * (i + 0.5), weight });
  }

  return peaks.sort((a, b) => b.weight - a.weight).slice(0, keep);
}
