import type { ThreadId } from "@t3tools/contracts";
import type {
  RelayAgentActivityAggregateState,
  RelayAgentActivityState,
  RelayAgentAwarenessPreferences,
} from "@t3tools/contracts/relay";
import {
  androidActivityData,
  androidAlertForAggregate,
  androidRowExpiresAt,
} from "@t3tools/shared/agentActivity/android";
import { makeAggregateState } from "@t3tools/shared/agentActivity/aggregate";
import { isExpiredAgentActivityState } from "@t3tools/shared/agentActivity/payloads";
import * as Context from "effect/Context";
import * as DateTime from "effect/DateTime";
import * as Digest from "effect/crypto/Digest";
import * as Effect from "effect/Effect";
import * as Layer from "effect/Layer";
import * as Option from "effect/Option";
import * as Stream from "effect/Stream";
import * as SubscriptionRef from "effect/SubscriptionRef";
import { HttpRouter, HttpServerRequest } from "effect/http";

import { authenticateMediaRequest } from "../auth/http.ts";
import { eventStreamResponse } from "../eventStreamResponse.ts";
import { requestUrl } from "../requestUrl.ts";

const AGENT_ACTIVITY_STREAM_ROUTE = "/api/agent-activity/stream";

export class LocalAgentActivity extends Context.Service<
  LocalAgentActivity,
  {
    /** Upserts (state non-null) or removes (null) the thread's row and notifies subscribers. */
    readonly publish: (
      threadId: ThreadId,
      state: RelayAgentActivityState | null,
    ) => Effect.Effect<void>;
    /** Current rows first, then the full row list after every change. */
    readonly rows: Stream.Stream<ReadonlyArray<RelayAgentActivityState>>;
  }
>()("t3/relay/LocalAgentActivity") {}

export const layer = Layer.effect(
  LocalAgentActivity,
  Effect.gen(function* () {
    const rows = yield* SubscriptionRef.make<ReadonlyMap<ThreadId, RelayAgentActivityState>>(
      new Map(),
    );
    const publish = Effect.fn("LocalAgentActivity.publish")(function* (
      threadId: ThreadId,
      state: RelayAgentActivityState | null,
    ) {
      const nowMs = (yield* DateTime.now).epochMilliseconds;
      yield* SubscriptionRef.update(rows, (current) => {
        const next = new Map(current);
        if (state === null) {
          next.delete(threadId);
        } else {
          next.set(threadId, state);
        }
        for (const [id, row] of next) {
          if (isExpiredAgentActivityState(row, nowMs)) next.delete(id);
        }
        return next;
      });
    });
    return LocalAgentActivity.of({
      publish,
      rows: SubscriptionRef.changes(rows).pipe(Stream.map((current) => [...current.values()])),
    });
  }),
);

/** The phone merges every server's rows into one card, so it needs their phases. */
function encodeActivityRows(aggregate: RelayAgentActivityAggregateState | null): string {
  return JSON.stringify(
    (aggregate?.activities ?? []).map((row) => ({
      phase: row.phase,
      status: row.status,
      threadTitle: row.threadTitle,
      projectTitle: row.projectTitle,
      deepLink: row.deepLink,
      updatedAt: row.updatedAt,
      expiresAt: androidRowExpiresAt(row),
    })),
  );
}

/**
 * Turns each row list into the Android data map a device would receive, alerting only when a
 * row newly needs attention or finishes. The first list is a baseline and never alerts; an
 * empty list after it counts as an empty card, so the next attention or finish still alerts.
 */
export function makeAndroidActivityEvents(
  activeRows: Stream.Stream<ReadonlyArray<RelayAgentActivityState>>,
  options: { readonly live: boolean; readonly notify: boolean },
): Stream.Stream<Record<string, string>, never, Digest.Digest> {
  const preferences: RelayAgentAwarenessPreferences = {
    liveActivitiesEnabled: options.live,
    notificationsEnabled: options.notify,
    notifyOnApproval: true,
    notifyOnInput: true,
    notifyOnCompletion: true,
    notifyOnFailure: true,
  };
  return activeRows.pipe(
    Stream.mapAccumEffect(
      (): RelayAgentActivityAggregateState | null | undefined => undefined,
      // Untraced: one step per emission would flood spans.
      Effect.fnUntraced(function* (
        previous: RelayAgentActivityAggregateState | null | undefined,
        states: ReadonlyArray<RelayAgentActivityState>,
      ) {
        const digest = yield* Digest.Digest;
        const nowMs = (yield* DateTime.now).epochMilliseconds;
        const aggregate = makeAggregateState({ activeStates: states, terminalState: null, nowMs });
        const alert =
          options.notify && previous !== undefined && aggregate !== null
            ? androidAlertForAggregate({
                previousAggregate: previous ?? { ...aggregate, activeCount: 0, activities: [] },
                nextAggregate: aggregate,
                preferences,
                nowMs,
              })
            : null;
        const shown = options.live ? aggregate : null;
        const data: Record<string, string> = {
          t3_kind: "agent_activity",
          updated_at: String(nowMs),
          ...androidActivityData(shown),
          activity_rows: encodeActivityRows(shown),
          ...alert,
        };
        if (alert) {
          data.alert_id = yield* digest.digestHex(
            Digest.sha256,
            new TextEncoder().encode(alert.alert_id),
          );
        }
        return [aggregate, [data]] as const;
      }),
    ),
  );
}

export const routeLayer = HttpRouter.add(
  "GET",
  AGENT_ACTIVITY_STREAM_ROUTE,
  Effect.gen(function* () {
    const request = yield* HttpServerRequest.HttpServerRequest;
    yield* authenticateMediaRequest("orchestration:read");
    const hub = yield* LocalAgentActivity;
    const digest = yield* Digest.Digest;
    const params = Option.getOrUndefined(requestUrl(request))?.searchParams;
    return eventStreamResponse(
      makeAndroidActivityEvents(hub.rows, {
        live: params?.get("live") !== "0",
        notify: params?.get("notify") !== "0",
      }).pipe(Stream.provideService(Digest.Digest, digest)),
      (data) => Effect.succeed(JSON.stringify(data)),
    );
  }),
);
