import { expect, it } from "@effect/vitest";
import { EnvironmentId, ThreadId } from "@t3tools/contracts";
import type { RelayAgentActivityState } from "@t3tools/contracts/relay";
import * as Digest from "effect/crypto/Digest";
import * as Effect from "effect/Effect";
import * as Stream from "effect/Stream";

import * as LocalAgentActivity from "./LocalAgentActivity.ts";

const THREAD_ID = ThreadId.make("thread");
const SECOND_THREAD_ID = ThreadId.make("thread-2");

const activity = (
  threadId: ThreadId,
  phase: RelayAgentActivityState["phase"],
): RelayAgentActivityState => ({
  environmentId: EnvironmentId.make("env"),
  threadId,
  projectTitle: "Project",
  threadTitle: "Thread",
  modelTitle: "Model",
  headline: "Working",
  phase,
  updatedAt: "1970-01-01T00:00:00.000Z",
  deepLink: "/threads/env/thread",
});

it.effect("publish upserts and removes a thread's row", () =>
  Effect.gen(function* () {
    const hub = yield* LocalAgentActivity.LocalAgentActivity;
    yield* hub.publish(THREAD_ID, activity(THREAD_ID, "running"));
    yield* hub.publish(SECOND_THREAD_ID, activity(SECOND_THREAD_ID, "waiting_for_input"));
    const afterUpsert = yield* hub.rows.pipe(Stream.take(1), Stream.runCollect);
    expect(afterUpsert.map((rows) => rows.map((row) => row.threadId))).toEqual([
      [THREAD_ID, SECOND_THREAD_ID],
    ]);

    yield* hub.publish(THREAD_ID, null);
    const afterRemove = yield* hub.rows.pipe(Stream.take(1), Stream.runCollect);
    expect(afterRemove.map((rows) => rows.map((row) => row.threadId))).toEqual([
      [SECOND_THREAD_ID],
    ]);
  }).pipe(Effect.provide(LocalAgentActivity.layer)),
);

it.effect("alerts only when a row newly needs attention", () =>
  Effect.gen(function* () {
    const events = yield* LocalAgentActivity.makeAndroidActivityEvents(
      Stream.make(
        [activity(THREAD_ID, "running")],
        [activity(THREAD_ID, "waiting_for_input")],
        [activity(THREAD_ID, "waiting_for_input")],
      ),
      { live: true, notify: true },
    ).pipe(Stream.runCollect);
    expect(events.map((data) => data.alert_title)).toEqual([undefined, "Thread", undefined]);
    expect(events[1]?.alert_body).toBe("Input: Project");
    expect(events[1]?.alert_id).toMatch(/^[0-9a-f]{64}$/);
  }).pipe(Effect.provide(Digest.layerWebCrypto)),
);

it.effect("alerts a row that appears after the card was emptied", () =>
  Effect.gen(function* () {
    const events = yield* LocalAgentActivity.makeAndroidActivityEvents(
      Stream.make(
        [activity(THREAD_ID, "running")],
        [],
        [activity(SECOND_THREAD_ID, "waiting_for_approval")],
      ),
      { live: true, notify: true },
    ).pipe(Stream.runCollect);
    expect(events[1]?.alert_title).toBeUndefined();
    expect(events[2]?.alert_title).toBe("Thread");
    expect(events[2]?.alert_body).toBe("Approval: Project");
    expect(events[2]?.alert_id).toMatch(/^[0-9a-f]{64}$/);
  }).pipe(Effect.provide(Digest.layerWebCrypto)),
);

it.effect("sends no alerts when notifications are off", () =>
  Effect.gen(function* () {
    const events = yield* LocalAgentActivity.makeAndroidActivityEvents(
      Stream.make([activity(THREAD_ID, "running")], [activity(THREAD_ID, "waiting_for_input")]),
      { live: true, notify: false },
    ).pipe(Stream.runCollect);
    expect(events.map((data) => data.alert_title)).toEqual([undefined, undefined]);
  }).pipe(Effect.provide(Digest.layerWebCrypto)),
);
