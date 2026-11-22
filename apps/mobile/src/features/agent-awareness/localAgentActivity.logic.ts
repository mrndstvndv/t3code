/**
 * Rejected sources whose stream token can be reissued. A rejected pairing bearer token means
 * the pairing itself is gone, which re-pairing handles instead.
 */
export function reissuableRejectedSources<Id extends string>(
  rejectedIds: ReadonlyArray<string>,
  connectionsById: Readonly<Record<Id, { readonly bearerToken: string | null }>>,
): Id[] {
  return rejectedIds.filter(
    (id): id is Id =>
      Object.hasOwn(connectionsById, id) && connectionsById[id as Id].bearerToken === null,
  );
}

export function removedEnvironmentIds<Id extends string>(
  previous: ReadonlyArray<Id>,
  current: ReadonlyArray<Id>,
): Id[] {
  const kept = new Set(current);
  return previous.filter((id) => !kept.has(id));
}
