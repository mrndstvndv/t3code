import { describe, expect, it } from "vite-plus/test";

import { reissuableRejectedSources, removedEnvironmentIds } from "./localAgentActivity.logic";

describe("stream tokens to forget", () => {
  it("reissues only rejected stream tokens of saved servers", () => {
    const connections = {
      dpop: { bearerToken: null },
      bearer: { bearerToken: "pairing-token" },
      idle: { bearerToken: null },
    };
    expect(reissuableRejectedSources(["dpop", "bearer", "removed"], connections)).toEqual(["dpop"]);
  });

  it("forgets servers that left the saved connections", () => {
    expect(removedEnvironmentIds(["a", "b", "c"], ["c", "a"])).toEqual(["b"]);
  });
});
