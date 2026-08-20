/** Abramowitz & Stegun 7.1.26 — enough precision for pricing decisions. */
export function erf(x: number): number {
  const sign = x < 0 ? -1 : 1;
  const ax = Math.abs(x);
  const t = 1 / (1 + 0.3275911 * ax);
  const y =
    1 -
    ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) *
      t +
      0.254829592) *
      t *
      Math.exp(-ax * ax);
  return sign * y;
}

/** Standard normal CDF. */
export function normalCdf(z: number): number {
  return 0.5 * (1 + erf(z / Math.SQRT2));
}

/**
 * Per-second volatility of log returns, estimated from a tick series.
 *
 * Ticks arrive about once a second but the spacing is not exact, so each
 * return is normalised by its own elapsed time before being pooled.
 */
export function perSecondVolatility(
  ticks: { timestamp: number; value: number }[],
): number | null {
  if (ticks.length < 8) return null;

  const normalised: number[] = [];
  for (let i = 1; i < ticks.length; i++) {
    const dtSec = (ticks[i].timestamp - ticks[i - 1].timestamp) / 1000;
    if (dtSec <= 0) continue;
    const prev = ticks[i - 1].value;
    const curr = ticks[i].value;
    if (prev <= 0 || curr <= 0) continue;
    normalised.push(Math.log(curr / prev) / Math.sqrt(dtSec));
  }
  if (normalised.length < 5) return null;

  const mean = normalised.reduce((a, b) => a + b, 0) / normalised.length;
  const variance =
    normalised.reduce((a, b) => a + (b - mean) ** 2, 0) /
    (normalised.length - 1);
  const sigma = Math.sqrt(Math.max(variance, 0));
  return Number.isFinite(sigma) && sigma > 0 ? sigma : null;
}

export function clamp(x: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, x));
}
