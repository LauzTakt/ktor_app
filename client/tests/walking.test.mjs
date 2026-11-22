import assert from "node:assert/strict";
import test from "node:test";
import { leaveBy, refreshWalkingPlan, walkingPhase, waitRemaining } from "../src/walking.ts";

const departure = Date.parse("2026-11-23T00:04:00+01:00");
const option = { id: "B", journeyId: "backup", label: "IR 15 to Bern", departure };
const plan = { option, minutes: 5 };

test("a walk to a departure after midnight can start the previous day", () => {
  assert.equal(leaveBy(plan), Date.parse("2026-11-22T23:58:00+01:00"));
});

test("the signal to walk changes again once even the walk would use up the time left", () => {
  const cutoff = leaveBy(plan);
  assert.equal(walkingPhase(plan, cutoff - 1), "waiting");
  assert.equal(walkingPhase(plan, cutoff), "go");
  assert.equal(walkingPhase(plan, cutoff + 60_000), "go");
  assert.equal(walkingPhase(plan, cutoff + 60_001), "tight");
  assert.equal(walkingPhase(plan, departure), "departed");
});

test("a rider already at the boarding point still gets the boarding minute", () => {
  const atStop = { option, minutes: 0 };
  assert.equal(leaveBy(atStop), departure - 60_000);
  assert.equal(walkingPhase(atStop, departure - 1), "go");
});

test("remaining time shows units and never rounds up to another minute of waiting", () => {
  assert.equal(waitRemaining(1), "1 s");
  assert.equal(waitRemaining(59_999), "59 s");
  assert.equal(waitRemaining(60_000), "1 min");
  assert.equal(waitRemaining(179_999), "2 min");
  assert.equal(waitRemaining(3_600_000), "1 h");
  assert.equal(waitRemaining(3_720_000), "1 h 2 min");
});

test("revised departures move the deadline in either direction for the same journey", () => {
  for (const change of [-120_000, 120_000]) {
    const revised = { ...option, id: "A", departure: departure + change };
    const updated = refreshWalkingPlan(plan, [{ ...option, journeyId: "different" }, revised]);
    assert.equal(updated.option, revised);
    assert.equal(leaveBy(updated), leaveBy(plan) + change);
  }
});

test("withdrawn departures and reused option letters cannot inherit a walking time", () => {
  assert.equal(refreshWalkingPlan(plan, []), null);
  assert.equal(refreshWalkingPlan(plan, [{ ...option, journeyId: "replacement" }]), null);
  assert.equal(refreshWalkingPlan(plan, [{ ...option, departure: null }]), null);
  const anonymous = { ...option, journeyId: null };
  const oneAnswer = { option: anonymous, minutes: 2 };
  assert.equal(refreshWalkingPlan(oneAnswer, [anonymous]), oneAnswer);
  assert.equal(refreshWalkingPlan(oneAnswer, [{ ...anonymous }]), null);
  assert.equal(refreshWalkingPlan(null, [option]), null);
});
