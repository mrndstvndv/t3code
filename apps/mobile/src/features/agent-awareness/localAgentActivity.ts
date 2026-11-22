import { useAtomValue } from "@effect/atom-react";
import {
  connectionRouteKind,
  routeHttpBaseUrl,
  usableRoutes,
  type ConnectionRouteKind,
} from "@t3tools/client-runtime/connection";
import type { EnvironmentId } from "@t3tools/contracts";
import { AsyncResult } from "effect/reactivity";
import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { AppState, Platform } from "react-native";

import {
  forgetAgentActivityStreamToken,
  requestAgentActivityStreamToken,
} from "./agentActivityStreamToken";
import {
  setAndroidLocalAgentActivitySources,
  takeAndroidRejectedLocalAgentActivitySources,
} from "./androidNotifications";
import { reissuableRejectedSources, removedEnvironmentIds } from "./localAgentActivity.logic";
import { environmentCatalog } from "../../connection/catalog";
import { appAtomRegistry } from "../../state/atom-registry";
import { mobilePreferencesAtom } from "../../state/preferences";
import {
  savedConnectionsByIdAtom,
  useSavedRemoteConnections,
} from "../../state/use-remote-environment-registry";

// Routes the background service can reach without the app's relay or ssh transport.
const STREAMABLE_ROUTE_KINDS: ReadonlySet<ConnectionRouteKind> = new Set([
  "loopback",
  "lan",
  "tailnet",
  "vpn",
]);

const STREAM_TOKEN_RETRY_MIN_MS = 30_000;
const STREAM_TOKEN_RETRY_MAX_MS = 10 * 60_000;

interface LocalAgentActivitySync {
  readonly sources: ReadonlyArray<{
    readonly id: string;
    readonly urls: ReadonlyArray<string>;
    readonly token: string;
  }>;
  readonly ongoingEnabled: boolean;
}

// Token requests chain on `forgetting`, so none can read back a stored token being forgotten.
function forgetStreamTokens(
  environmentIds: ReadonlyArray<EnvironmentId>,
  forgetting: { current: Promise<unknown> },
  setStreamTokens: Dispatch<SetStateAction<ReadonlyMap<string, string>>>,
): void {
  const forgotten = forgetting.current.then(() =>
    Promise.all(environmentIds.map(forgetAgentActivityStreamToken)),
  );
  forgetting.current = forgotten;
  void forgotten.then(() =>
    setStreamTokens((current) => {
      const next = new Map(current);
      for (const environmentId of environmentIds) next.delete(environmentId);
      return next;
    }),
  );
}

export function useLocalAgentActivitySync(): void {
  const { savedConnectionsById } = useSavedRemoteConnections();
  const catalog = useAtomValue(environmentCatalog.catalogValueAtom);
  const preferencesResult = useAtomValue(mobilePreferencesAtom);
  const [streamTokens, setStreamTokens] = useState<ReadonlyMap<string, string>>(() => new Map());
  const streamTokenRequests = useRef(new Set<string>());
  // A request often fails before the app reconnects after launch, so failures retry with backoff.
  const [streamTokenRetry, setStreamTokenRetry] = useState(0);
  const streamTokenRetryDelayMs = useRef(STREAM_TOKEN_RETRY_MIN_MS);
  const forgettingStreamTokens = useRef<Promise<unknown>>(Promise.resolve());
  const previousEnvironmentIds = useRef<EnvironmentId[] | null>(null);
  const ongoingEnabled = AsyncResult.isSuccess(preferencesResult)
    ? preferencesResult.value.liveActivitiesEnabled !== false
    : true;
  const reachable = Object.values(savedConnectionsById).flatMap((connection) => {
    if (connection.relayManaged) return [];
    const entry = catalog.entries.get(connection.environmentId);
    // The service keeps running after the app leaves the network it last used, so it gets
    // every direct route; the one in use goes first.
    const routes = entry
      ? usableRoutes(entry).flatMap((route) => {
          const kind = connectionRouteKind(route);
          const url = routeHttpBaseUrl(route);
          return kind === "relay" || kind === "ssh" || url === null ? [] : [{ kind, url }];
        })
      : [];
    const primaryKind = routes.find((route) => route.url === connection.httpBaseUrl)?.kind;
    const urls = [
      ...new Set(
        [{ url: connection.httpBaseUrl, kind: primaryKind }, ...routes]
          .filter(
            ({ url, kind }) =>
              url !== "" &&
              (url.startsWith("https:") ||
                (kind !== undefined && STREAMABLE_ROUTE_KINDS.has(kind))),
          )
          .map(({ url }) => url),
      ),
    ];
    return urls.length === 0 ? [] : [{ connection, urls }];
  });
  const sources = reachable
    .flatMap(({ connection, urls }) => {
      const id = connection.environmentId;
      if (connection.bearerToken !== null) return [{ id, urls, token: connection.bearerToken }];
      const token = streamTokens.get(id);
      return token === undefined ? [] : [{ id, urls, token }];
    })
    .sort((a, b) => a.id.localeCompare(b.id));
  const pendingStreamTokens = reachable
    .filter(
      ({ connection }) =>
        // Key-bound (DPoP) sessions expose no bearer token the service could present.
        connection.bearerToken === null && !streamTokens.has(connection.environmentId),
    )
    .map(({ connection }) => connection.environmentId)
    .sort();
  const syncKey = JSON.stringify({ sources, ongoingEnabled });
  const environmentIdsKey = catalog.isReady ? JSON.stringify([...catalog.entries.keys()]) : null;
  const streamTokenKey = JSON.stringify({ pending: pendingStreamTokens, retry: streamTokenRetry });

  // oxlint-disable-next-line t3code/no-unreasoned-state-sync -- collects the stream tokens the native service saw refused, at launch and on each return to the foreground
  useEffect(() => {
    if (Platform.OS !== "android" || !catalog.isReady) return;
    const reissueRejected = () => {
      const rejected = reissuableRejectedSources(
        takeAndroidRejectedLocalAgentActivitySources(),
        appAtomRegistry.get(savedConnectionsByIdAtom),
      );
      if (rejected.length > 0)
        forgetStreamTokens(rejected, forgettingStreamTokens, setStreamTokens);
    };
    reissueRejected();
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") reissueRejected();
    });
    return () => subscription.remove();
  }, [catalog.isReady]);

  // oxlint-disable-next-line t3code/no-unreasoned-state-sync -- drops the SecureStore stream token of each removed server
  useEffect(() => {
    if (Platform.OS !== "android" || environmentIdsKey === null) return;
    const current = JSON.parse(environmentIdsKey) as EnvironmentId[];
    const previous = previousEnvironmentIds.current;
    previousEnvironmentIds.current = current;
    const removed = previous === null ? [] : removedEnvironmentIds(previous, current);
    if (removed.length > 0) forgetStreamTokens(removed, forgettingStreamTokens, setStreamTokens);
  }, [environmentIdsKey]);

  // oxlint-disable-next-line t3code/no-unreasoned-state-sync -- asks each DPoP server for the stream token the native service presents, which lives outside React
  useEffect(() => {
    if (Platform.OS !== "android") return;
    const { pending } = JSON.parse(streamTokenKey) as { pending: EnvironmentId[] };
    for (const environmentId of pending) {
      if (streamTokenRequests.current.has(environmentId)) continue;
      streamTokenRequests.current.add(environmentId);
      void forgettingStreamTokens.current
        .then(() => requestAgentActivityStreamToken(environmentId))
        .then((token) => {
          streamTokenRequests.current.delete(environmentId);
          if (token !== null) {
            streamTokenRetryDelayMs.current = STREAM_TOKEN_RETRY_MIN_MS;
            setStreamTokens((current) => new Map(current).set(environmentId, token));
            return;
          }
          const delayMs = streamTokenRetryDelayMs.current;
          streamTokenRetryDelayMs.current = Math.min(delayMs * 2, STREAM_TOKEN_RETRY_MAX_MS);
          setTimeout(() => setStreamTokenRetry((attempt) => attempt + 1), delayMs);
        });
    }
  }, [streamTokenKey]);

  // oxlint-disable-next-line t3code/no-unreasoned-state-sync -- starts the native foreground service that mirrors saved servers
  useEffect(() => {
    if (Platform.OS !== "android") return;
    // Parsed from the key so the effect's dependency changes only when the key does.
    const sync = JSON.parse(syncKey) as LocalAgentActivitySync;
    setAndroidLocalAgentActivitySources(sync.sources, sync.ongoingEnabled);
  }, [syncKey]);
}
