import type { Candle } from './candles';

/**
 * The prices the chart actually turned at.
 *
 * The volume lines underneath say where trading happened and the levels say
 * which of those the market keeps respecting. Neither answers the plainest
 * question there is about a chart — what number is that top — and on a phone
 * that question is usually asked about a peak the eye has already picked out.
 *
 * So: every swing high and low in what is on screen, each with its own figure.
 * Not a model of anything, just the chart read back in numbers.
 */
export type Peak = {
  /** The candle's open time, so the mark follows its bar through a zoom. */
  time: number;
  price: number;
  kind: 'high' | 'low';
};

/**
 * How many candles either side have to be lower for a bar to be a top.
 *
 * Two is the smallest number that means anything: one would make a peak of
 * every second candle in a chop. It still finds plenty, which is the point —
 * the ones that matter are picked out by eye, and the figure is what is wanted.
 */
const REACH = 2;

/** The most to offer. Beyond this they stop being marks and become a grid. */
const KEEP = 12;

export function swingPeaks(
  candles: Candle[],
  reach = REACH,
  keep = KEEP,
): Peak[] {
  const clean = candles.filter(
    ([t, , h, l]) => Number.isFinite(t) && h > 0 && l > 0 && h >= l,
  );
  if (clean.length < reach * 2 + 1) return [];

  const found: Peak[] = [];
  for (let i = reach; i < clean.length - reach; i++) {
    const [time, , high, low] = clean[i]!;
    let topmost = true;
    let bottommost = true;
    for (let k = i - reach; k <= i + reach; k++) {
      if (k === i) continue;
      const other = clean[k]!;
      if (other[2] >= high) topmost = false;
      if (other[3] <= low) bottommost = false;
    }
    if (topmost) found.push({ time, price: high, kind: 'high' });
    if (bottommost) found.push({ time, price: low, kind: 'low' });
  }

  /*
    The furthest out first.

    Two marks a few pixels apart cannot both be read, and the one worth keeping
    is the one further from the middle: a top just under another top is the same
    top seen twice. Which of them actually collide depends on how tall the panel
    is drawn, so that part is decided where the drawing happens — here they are
    only put in the order they should be dropped in.
  */
  const low = Math.min(...clean.map((c) => c[3]!));
  const high = Math.max(...clean.map((c) => c[2]!));
  const middle = (low + high) / 2;

  return found
    .sort((a, b) => Math.abs(b.price - middle) - Math.abs(a.price - middle))
    .slice(0, keep);
}
