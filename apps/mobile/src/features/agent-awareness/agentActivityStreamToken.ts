import { executeConnectedEnvironmentHttpRequest } from "@t3tools/client-runtime/state/environmentHttpAuth";
import { createEnvironmentCommand, runAtomCommand } from "@t3tools/client-runtime/state/runtime";
import type { EnvironmentId } from "@t3tools/contracts";
import * as DateTime from "effect/DateTime";
import * as Duration from "effect/Duration";
import * as Effect from "effect/Effect";
import * as Option from "effect/Option";
import * as Schema from "effect/Schema";

import { connectionAtomRuntime } from "../../connection/runtime";
import * as MobileSecureStorage from "../../persistence/mobile-secure-storage";
import { appAtomRegistry } from "../../state/atom-registry";

const STREAM_TOKEN_TIMEOUT_MS = 15_000;
const REFRESH_WITHIN_MS = Duration.toMillis(Duration.days(7));

const StoredStreamToken = Schema.Struct({
  token: Schema.String,
  expiresAt: Schema.String,
});
const StoredStreamTokenJson = Schema.fromJsonString(StoredStreamToken);
const decodeStoredStreamToken = Schema.decodeUnknownOption(StoredStreamTokenJson);
const encodeStoredStreamToken = Schema.encodeUnknownEffect(StoredStreamTokenJson);

const storageKey = (environmentId: EnvironmentId) =>
  `t3code.agent-activity-stream-token.${environmentId}`;

const loadStreamToken = Effect.fn("agentActivityStreamToken.load")(function* (
  environmentId: EnvironmentId,
) {
  const storage = yield* MobileSecureStorage.MobileSecureStorage;
  const raw = yield* storage
    .getItem(storageKey(environmentId))
    .pipe(Effect.orElseSucceed(() => null));
  return raw === null ? Option.none() : decodeStoredStreamToken(raw);
});

const saveStreamToken = Effect.fn("agentActivityStreamToken.save")(function* (
  environmentId: EnvironmentId,
  issued: typeof StoredStreamToken.Type,
) {
  const storage = yield* MobileSecureStorage.MobileSecureStorage;
  yield* encodeStoredStreamToken(issued).pipe(
    Effect.flatMap((json) => storage.setItem(storageKey(environmentId), json)),
    Effect.ignore,
  );
});

const removeStreamToken = Effect.fn("agentActivityStreamToken.remove")(function* (
  environmentId: EnvironmentId,
) {
  const storage = yield* MobileSecureStorage.MobileSecureStorage;
  yield* storage.removeItem(storageKey(environmentId)).pipe(Effect.ignore);
});

const issueAgentActivityStreamToken = Effect.fn("agentActivityStreamToken.issue")(function* () {
  return yield* executeConnectedEnvironmentHttpRequest({
    group: "agentActivity",
    method: "POST",
    url: (urls) => urls.issueStreamToken(),
    timeoutMs: STREAM_TOKEN_TIMEOUT_MS,
    request: ({ client, headers }) => client.issueStreamToken({ headers }),
  });
});

const ensureStreamToken = Effect.fn("agentActivityStreamToken.ensure")(function* (
  environmentId: EnvironmentId,
) {
  const now = yield* DateTime.now;
  const stored = yield* loadStreamToken(environmentId);
  if (
    Option.isSome(stored) &&
    Date.parse(stored.value.expiresAt) - DateTime.toEpochMillis(now) > REFRESH_WITHIN_MS
  ) {
    return stored.value.token;
  }
  const issued = yield* issueAgentActivityStreamToken();
  yield* saveStreamToken(environmentId, issued);
  return issued.token;
});

/** The stored stream token while it has over a week left, else a newly issued one. */
const issueAgentActivityStreamTokenCommand = createEnvironmentCommand(connectionAtomRuntime, {
  label: "agent-activity:stream-token:issue",
  execute: (_input: void, _registry, environmentId) => ensureStreamToken(environmentId),
});

/** The stream token for `environmentId`, or null when it cannot be had. */
export async function requestAgentActivityStreamToken(
  environmentId: EnvironmentId,
): Promise<string | null> {
  const result = await runAtomCommand(appAtomRegistry, issueAgentActivityStreamTokenCommand, {
    environmentId,
    input: undefined,
  });
  return result._tag === "Success" ? result.value : null;
}

/** Drops the stored stream token so the next request issues a new one. */
export function forgetAgentActivityStreamToken(environmentId: EnvironmentId): Promise<void> {
  return Effect.runPromise(
    removeStreamToken(environmentId).pipe(Effect.provide(MobileSecureStorage.layer)),
  );
}
