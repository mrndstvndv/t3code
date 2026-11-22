import * as Effect from "effect/Effect";
import * as SqlClient from "effect/sql/SqlClient";

export default Effect.gen(function* () {
  const sql = yield* SqlClient.SqlClient;
  const sessionColumns = yield* sql<{ readonly name: string }>`
    PRAGMA table_info(auth_sessions)
  `;
  // A session issued on behalf of another lives and dies with its issuer: the issuer's device
  // key when it has one (its sessions renew and rotate), else the issuing session.
  if (!sessionColumns.some((column) => column.name === "issuer_session_id")) {
    yield* sql`ALTER TABLE auth_sessions ADD COLUMN issuer_session_id TEXT`;
  }
  if (!sessionColumns.some((column) => column.name === "issuer_proof_key_thumbprint")) {
    yield* sql`ALTER TABLE auth_sessions ADD COLUMN issuer_proof_key_thumbprint TEXT`;
  }
});
