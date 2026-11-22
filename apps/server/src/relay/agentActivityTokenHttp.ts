import {
  AuthOrchestrationReadScope,
  EnvironmentAuthenticatedPrincipal,
  EnvironmentHttpApi,
} from "@t3tools/contracts";
import * as DateTime from "effect/DateTime";
import * as Duration from "effect/Duration";
import * as Effect from "effect/Effect";
import * as HttpApiBuilder from "effect/http-api/HttpApiBuilder";

import * as EnvironmentAuth from "../auth/EnvironmentAuth.ts";
import {
  annotateEnvironmentRequest,
  appendCredentialResponseHeaders,
  failEnvironmentInternal,
  requireEnvironmentScope,
} from "../auth/http.ts";

// A plain bearer the native notification service can present; it cannot be used for anything but reading.
const STREAM_TOKEN_TTL = Duration.days(90);

export const agentActivityTokenHttpApiLayer = HttpApiBuilder.group(
  EnvironmentHttpApi,
  "agentActivity",
  Effect.fnUntraced(function* (handlers) {
    const auth = yield* EnvironmentAuth.EnvironmentAuth;
    return handlers.handle(
      "issueStreamToken",
      Effect.fn("environment.agentActivity.issueStreamToken")(
        function* (args) {
          yield* annotateEnvironmentRequest(args.endpoint.name);
          yield* requireEnvironmentScope(AuthOrchestrationReadScope);
          const principal = yield* EnvironmentAuthenticatedPrincipal;
          const issued = yield* auth.issueSession({
            subject: principal.subject,
            scopes: [AuthOrchestrationReadScope],
            ttl: STREAM_TOKEN_TTL,
            label: "Android notifications",
          });
          yield* appendCredentialResponseHeaders;
          return {
            token: issued.token,
            expiresAt: DateTime.formatIso(issued.expiresAt),
          };
        },
        Effect.catchIf(EnvironmentAuth.isServerAuthInternalError, (error) =>
          failEnvironmentInternal("internal_error", error),
        ),
      ),
    );
  }),
);
