import { useAtomValue } from "@effect/atom-react";
import {
  connectionRouteKind,
  routeHttpBaseUrl,
  usableRoutes,
  type ConnectionRouteKind,
} from "@t3tools/client-runtime/connection";
import type { EnvironmentId } from "@t3tools/contracts";
import { AsyncResult } from "effect/reactivity";
import { useEffect, useRef, useState } from "react";
import { Platform } from "react-native";

import { requestAgentActivityStreamToken } from "./agentActivityStreamToken";
import { setAndroidLocalAgentActivitySources } from "./androidNotifications";
import { environmentCatalog } from "../../connection/catalog";
import { mobilePreferencesAtom } from "../../state/preferences";
import { useSavedRemoteConnections } from "../../state/use-remote-environment-registry";

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

export function useLocalAgentActivitySync(): void {
  const { savedConnectionsById } = useSavedRemoteConnections();
  const catalog = useAtomValue(environmentCatalog.catalogValueAtom);
  const preferencesResult = useAtomValue(mobilePreferencesAtom);
  const [streamTokens, setStreamTokens] = useState<ReadonlyMap<string, string>>(() => new Map());
  const streamTokenRequests = useRef(new Set<string>());
  // A request often fails before the app reconnects after launch, so failures retry with backoff.
  const [streamTokenRetry, setStreamTokenRetry] = useState(0);
  const streamTokenRetryDelayMs = useRef(STREAM_TOKEN_RETRY_MIN_MS);
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
  const streamTokenKey = JSON.stringify({ pending: pendingStreamTokens, retry: streamTokenRetry });

  // oxlint-disable-next-line t3code/no-unreasoned-state-sync -- asks each DPoP server for the stream token the native service presents, which lives outside React
  useEffect(() => {
    if (Platform.OS !== "android") return;
    const { pending } = JSON.parse(streamTokenKey) as { pending: EnvironmentId[] };
    for (const environmentId of pending) {
      if (streamTokenRequests.current.has(environmentId)) continue;
      streamTokenRequests.current.add(environmentId);
      void requestAgentActivityStreamToken(environmentId).then((token) => {
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
