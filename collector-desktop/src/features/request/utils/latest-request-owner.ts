export interface LatestRequestTicket<TContext> {
  generation: number;
  context: TContext;
}

export interface LatestRequestOwner<TContext> {
  begin(context: TContext): LatestRequestTicket<TContext>;
  invalidate(): void;
  isLatest(ticket: LatestRequestTicket<TContext>): boolean;
  canCommit(ticket: LatestRequestTicket<TContext>, liveContext: TContext): boolean;
}

export type LatestRequestContextComparator<TContext> = (
  left: TContext | null | undefined,
  right: TContext | null | undefined
) => boolean;

export function createLatestRequestOwner<TContext>(
  isSameContext: LatestRequestContextComparator<TContext>
): LatestRequestOwner<TContext> {
  let currentGeneration = 0;
  let currentTicket: LatestRequestTicket<TContext> | null = null;

  function isLatest(ticket: LatestRequestTicket<TContext>): boolean {
    return currentTicket !== null && ticket.generation === currentTicket.generation;
  }

  return {
    begin(context) {
      currentTicket = {
        context,
        generation: ++currentGeneration
      };
      return currentTicket;
    },
    invalidate() {
      currentGeneration += 1;
      currentTicket = null;
    },
    isLatest,
    canCommit(ticket, liveContext) {
      return isLatest(ticket) && isSameContext(ticket.context, liveContext);
    }
  };
}

export function shouldDisableLatestRequestSubmit<TContext>(
  loading: boolean,
  pendingContext: TContext | null | undefined,
  liveContext: TContext,
  isSameContext: LatestRequestContextComparator<TContext>
): boolean {
  return loading && isSameContext(pendingContext, liveContext);
}
