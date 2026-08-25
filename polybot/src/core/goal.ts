import { Preferences } from '@capacitor/preferences';

/**
 * Taking money off the table.
 *
 * A doubled balance is the moment a run is most worth protecting and least
 * likely to be: the same confidence that doubled it is the confidence that
 * gives it back. So the app watches for it and says so, with the figure already
 * worked out — three quarters of the profit, which leaves the run bigger than
 * it started and the winnings out of reach of the next bad five minutes.
 *
 * The baseline is whatever the balance was when the run began. Once the money
 * is actually withdrawn the run starts again from what is left, so the next
 * reminder means the same thing as this one did.
 */
export type GoalState = {
  /** What the balance was when this run started. */
  baseline: number;
  startedAt: number;
  /** Balance the reminder was last put off at, and when. */
  snoozedBalance?: number;
  snoozedAt?: number;
  /** How many times the goal has been reached and the run restarted. */
  rounds: number;
};

const KEY = 'goal.v1';

/** The gain that triggers the reminder: double the money. */
export const GOAL_GAIN = 1;

/** How much of the profit the reminder suggests taking out. */
export const WITHDRAW_SHARE = 0.75;

/** After putting it off, this much more profit brings it back. */
const SNOOZE_GAIN = 0.1;

/** …or this much time, whichever comes first. */
const SNOOZE_MS = 6 * 60 * 60_000;

export async function loadGoal(): Promise<GoalState | null> {
  const { value } = await Preferences.get({ key: KEY });
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as GoalState;
    return Number.isFinite(parsed?.baseline) && parsed.baseline > 0 ? parsed : null;
  } catch {
    return null;
  }
}

export async function saveGoal(state: GoalState): Promise<void> {
  await Preferences.set({ key: KEY, value: JSON.stringify(state) });
}

/** The first balance the app ever sees is where the run starts. */
export function startRun(balance: number, at = Date.now()): GoalState {
  return { baseline: balance, startedAt: at, rounds: 0 };
}

export type GoalProgress = {
  baseline: number;
  balance: number;
  target: number;
  profit: number;
  /** Profit as a share of the baseline. */
  gain: number;
  /** What is still needed to reach the goal, in dollars. */
  remaining: number;
  /** Three quarters of the profit — what the reminder suggests withdrawing. */
  suggested: number;
  /** What would be left after taking it out. */
  leftAfter: number;
  reached: boolean;
};

export function goalProgress(state: GoalState, balance: number): GoalProgress {
  const baseline = state.baseline;
  const target = baseline * (1 + GOAL_GAIN);
  const profit = balance - baseline;
  const suggested = Math.max(0, profit) * WITHDRAW_SHARE;
  return {
    baseline,
    balance,
    target,
    profit,
    gain: baseline > 0 ? profit / baseline : 0,
    remaining: Math.max(0, target - balance),
    suggested,
    leftAfter: balance - suggested,
    reached: balance >= target - 1e-9,
  };
}

/**
 * Should the reminder be showing?
 *
 * Reaching the goal is not enough on its own: one that has been put off stays
 * off until the balance has climbed appreciably further, or until enough of the
 * day has passed that it is news again. A reminder that reappears on every poll
 * is a reminder that gets dismissed without being read.
 */
export function shouldRemind(
  state: GoalState | null,
  balance: number,
  now = Date.now(),
): boolean {
  if (!state || !Number.isFinite(balance)) return false;
  if (!goalProgress(state, balance).reached) return false;

  if (state.snoozedBalance == null || state.snoozedAt == null) return true;
  if (balance >= state.snoozedBalance + state.baseline * SNOOZE_GAIN) return true;
  return now - state.snoozedAt >= SNOOZE_MS;
}

export function snoozeGoal(
  state: GoalState,
  balance: number,
  now = Date.now(),
): GoalState {
  return { ...state, snoozedBalance: balance, snoozedAt: now };
}

/**
 * Start the run again from what is left.
 *
 * Called once the money is out, so the balance passed in is the reduced one —
 * that is the whole point of doing it on a tap rather than automatically at the
 * moment the goal is hit.
 */
export function restartRun(
  state: GoalState,
  balance: number,
  now = Date.now(),
): GoalState {
  return {
    baseline: balance,
    startedAt: now,
    rounds: state.rounds + 1,
  };
}
