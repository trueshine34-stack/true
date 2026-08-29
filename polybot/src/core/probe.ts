import type { ProbeRound } from '../native/polybot';

/**
 * Reading the test bot's record.
 *
 * The bot itself is one line long — buy the way the chart's line points, leave
 * by the ladder — so everything worth knowing is in what happened afterwards,
 * and this is the arithmetic that turns a pile of windows into an answer.
 *
 * Two questions are being asked and they are not the same question. Did the
 * line call the direction right, and did following it make money: a run can
 * be right two windows in three and still lose, because the ladder sells the
 * winners early and the losers settle at nothing. Both are counted here, apart.
 */
export interface ProbeSummary {
  /** Windows that have been scored. */
  rounds: number;
  wins: number;
  losses: number;
  flat: number;
  spent: number;
  got: number;
  settled: number;
  pnl: number;
  /** Windows whose result is known, and how many the line called right. */
  scored: number;
  called: number;
  /** Share of those called right, 0..1, or null when nothing is scored. */
  hitRate: number | null;
  /** Money per window, or null with nothing to average. */
  average: number | null;
  best: number | null;
  worst: number | null;
  /** How many closed by the ladder rather than by the settlement. */
  byLadder: number;
  toSettlement: number;
}

const EMPTY: ProbeSummary = {
  rounds: 0,
  wins: 0,
  losses: 0,
  flat: 0,
  spent: 0,
  got: 0,
  settled: 0,
  pnl: 0,
  scored: 0,
  called: 0,
  hitRate: null,
  average: null,
  best: null,
  worst: null,
  byLadder: 0,
  toSettlement: 0,
};

/** A cent either way is rounding, not a result. */
const FLAT = 0.005;

/**
 * What a window came to, worked out from its parts rather than trusted.
 *
 * The record carries a `pnl` of its own, written when the window was scored.
 * This recomputes it from what was paid, what the ladder got and what the
 * market settled, so that every figure in the report — a row, a side, the
 * total — is the same arithmetic and they cannot drift apart.
 */
export function pnlOf(round: ProbeRound): number {
  return round.proceeds + round.settled - round.shares * round.price;
}

export function summarise(rounds: ProbeRound[]): ProbeSummary {
  const closed = rounds.filter((r) => !r.open);
  if (closed.length === 0) return { ...EMPTY };

  let spent = 0;
  let got = 0;
  let settled = 0;
  let wins = 0;
  let losses = 0;
  let flat = 0;
  let scored = 0;
  let called = 0;
  let byLadder = 0;
  let toSettlement = 0;
  let best: number | null = null;
  let worst: number | null = null;

  for (const r of closed) {
    spent += r.shares * r.price;
    got += r.proceeds;
    settled += r.settled;

    const money = pnlOf(r);
    if (money > FLAT) wins += 1;
    else if (money < -FLAT) losses += 1;
    else flat += 1;

    if (r.winner) {
      scored += 1;
      if (r.right) called += 1;
    }

    // Whichever paid for more of the position is how the window ended.
    if (r.settled > r.proceeds) toSettlement += 1;
    else byLadder += 1;

    if (best === null || money > best) best = money;
    if (worst === null || money < worst) worst = money;
  }

  const pnl = got + settled - spent;
  return {
    rounds: closed.length,
    wins,
    losses,
    flat,
    spent,
    got,
    settled,
    pnl,
    scored,
    called,
    hitRate: scored > 0 ? called / scored : null,
    average: pnl / closed.length,
    best,
    worst,
    byLadder,
    toSettlement,
  };
}

/**
 * The same arithmetic, split by the direction that was followed.
 *
 * Worth its own line because the two are not symmetrical: a market that grinds
 * up all session makes the line right on Up and wrong on Down, and a total
 * that averages the two hides exactly that.
 */
export function bySide(rounds: ProbeRound[]): {
  up: ProbeSummary;
  down: ProbeSummary;
} {
  return {
    up: summarise(rounds.filter((r) => r.side === 'Up')),
    down: summarise(rounds.filter((r) => r.side === 'Down')),
  };
}

/**
 * The last [count] scored windows, newest first.
 *
 * The record arrives newest first already; this only trims it, so a report
 * that has been running all day still opens on the part anyone reads.
 */
export function latest(rounds: ProbeRound[], count: number): ProbeRound[] {
  return rounds.slice(0, Math.max(0, count));
}

/** A running total over the record, oldest first — the shape of the run. */
export function curve(rounds: ProbeRound[]): number[] {
  const closed = rounds.filter((r) => !r.open);
  const out: number[] = [];
  let sum = 0;
  for (let i = closed.length - 1; i >= 0; i -= 1) {
    sum += pnlOf(closed[i]);
    out.push(sum);
  }
  return out;
}
