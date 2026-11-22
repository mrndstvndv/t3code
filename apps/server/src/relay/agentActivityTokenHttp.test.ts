// @effect-diagnostics nodeBuiltinImport:off globalFetch:off globalFetchInEffect:off - real HTTP boundary test.
import * as NodeHttpServer from "@effect/platform-node/NodeHttpServer";
import { it as effectIt } from "@effect/vitest";
import {
  AuthOrchestrationReadScope,
  AuthSessionId,
  type AuthEnvironmentScope,
  EnvironmentAgentActivityHttpApi,
  EnvironmentAuthenticatedAuth,
  EnvironmentAuthenticatedPrincipal,
} from "@t3tools/contracts";
import * as DateTime from "effect/DateTime";
import * as Duration from "effect/Duration";
import * as Effect from "effect/Effect";
import * as Layer from "effect/Layer";
import * as Schema from "effect/Schema";
import * as HttpRouter from "effect/http/HttpRouter";
import * as HttpServer from "effect/http/HttpServer";
import * as HttpApi from "effect/http-api/HttpApi";
import * as HttpApiBuilder from "effect/http-api/HttpApiBuilder";
import type * as NetAddress from "effect/net/NetAddress";
import { expect, vi } from "vite-plus/test";

import * as EnvironmentAuth from "../auth/EnvironmentAuth.ts";
import { agentActivityTokenHttpApiLayer } from "./agentActivityTokenHttp.ts";

const decodeSessionId = Schema.decodeUnknownSync(AuthSessionId);

const principal = (subject: string, ...scopes: AuthEnvironmentScope[]) =>
  Layer.succeed(EnvironmentAuthenticatedAuth, (effect) =>
    effect.pipe(
      Effect.provideService(EnvironmentAuthenticatedPrincipal, {
        sessionId: decodeSessionId("agent-activity-test"),
        subject,
        method: "bearer-access-token",
        scopes: new Set(scopes),
      }),
    ),
  );

const issued: EnvironmentAuth.IssuedBearerSession = {
  sessionId: decodeSessionId("agent-activity-stream"),
  token: "stream-token",
  method: "bearer-access-token",
  scopes: [AuthOrchestrationReadScope],
  subject: "caller",
  client: { deviceType: "bot", label: "Android notifications" },
  expiresAt: DateTime.makeUnsafe(Date.UTC(2027, 1, 20)),
};

const withRoutes = <A, E>(
  scopes: AuthEnvironmentScope[],
  issueSession: EnvironmentAuth.EnvironmentAuth["Service"]["issueSession"],
  body: (url: URL) => Effect.Effect<A, E>,
) =>
  Effect.gen(function* () {
    const routes = HttpApiBuilder.layer(
      HttpApi.make("environment").add(EnvironmentAgentActivityHttpApi),
    ).pipe(
      Layer.provide(agentActivityTokenHttpApiLayer),
      Layer.provide(principal("caller", ...scopes)),
      Layer.provide(Layer.mock(EnvironmentAuth.EnvironmentAuth)({ issueSession })),
    );
    yield* Layer.build(HttpRouter.serve(routes, { disableLogger: true, disableListenLog: true }));
    const server = yield* HttpServer.HttpServer;
    const address = server.address as NetAddress.InetAddress;
    const host = address._tag === "InetAddressV6" ? "[::1]" : "127.0.0.1";
    return yield* body(new URL(`http://${host}:${address.port}/api/agent-activity/token`));
  }).pipe(Effect.scoped, Effect.provide(NodeHttpServer.layerTest));

effectIt.live("issues a read-only stream token tied to the caller", () =>
  Effect.gen(function* () {
    const issueSession = vi.fn<EnvironmentAuth.EnvironmentAuth["Service"]["issueSession"]>(() =>
      Effect.succeed(issued),
    );
    yield* withRoutes([AuthOrchestrationReadScope], issueSession, (url) =>
      Effect.gen(function* () {
        const response = yield* Effect.promise(() => fetch(url, { method: "POST" }));
        expect(response.status).toBe(200);
        const body: unknown = yield* Effect.promise(() => response.json());
        expect(body).toEqual({ token: "stream-token", expiresAt: "2027-02-20T00:00:00.000Z" });
      }),
    );
    expect(issueSession).toHaveBeenCalledWith({
      subject: "caller",
      scopes: ["orchestration:read"],
      ttl: Duration.days(90),
      label: "Android notifications",
      issuer: expect.objectContaining({ sessionId: "agent-activity-test" }),
    });
  }),
);

effectIt.live("rejects a caller without orchestration:read", () =>
  Effect.gen(function* () {
    const issueSession = vi.fn<EnvironmentAuth.EnvironmentAuth["Service"]["issueSession"]>(() =>
      Effect.succeed(issued),
    );
    yield* withRoutes([], issueSession, (url) =>
      Effect.gen(function* () {
        const response = yield* Effect.promise(() => fetch(url, { method: "POST" }));
        expect(response.status).toBe(403);
      }),
    );
    expect(issueSession).not.toHaveBeenCalled();
  }),
);
