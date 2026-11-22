import type { Option } from "./contract";

export const BOARDING_MINUTES = 1;
const MINUTE = 60_000;

export type WalkingPlan = { option: Option; minutes: number };

export function leaveBy({ option, minutes }: WalkingPlan) {
  return option.departure - (minutes + BOARDING_MINUTES) * MINUTE;
}

export function walkingPhase(plan: WalkingPlan, now: number) {
  if (now >= plan.option.departure) return "departed";
  if (now < leaveBy(plan)) return "waiting";
  if (now <= plan.option.departure - plan.minutes * MINUTE) return "go";
  return "tight";
}

/** Whole minutes a rider can still wait, then seconds in the last minute. */
export function waitRemaining(ms: number) {
  const minutes = Math.floor(ms / MINUTE);
  if (minutes < 1) return `${Math.max(1, Math.floor(ms / 1000))} s`;
  if (minutes < 60) return `${minutes} min`;
  const rest = minutes % 60;
  return `${Math.floor(minutes / 60)} h${rest ? ` ${rest} min` : ""}`;
}

/** Letter IDs such as "B" are reused in each answer. Only a journey ID identifies the same service after a refresh. */
export function findJourneyOption(option: Option, options: Option[]) {
  return options.find((o) => o === option || (o.journeyId && o.journeyId === option.journeyId));
}

export function refreshWalkingPlan(plan: WalkingPlan | null, options: Option[]): WalkingPlan | null {
  if (!plan) return null;
  const next = findJourneyOption(plan.option, options);
  if (!next || !Number.isFinite(next.departure) || next.departure <= 0) return null;
  return next === plan.option ? plan : { ...plan, option: next };
}
