export type ContextComparator<TContext> = (
  left: TContext | null | undefined,
  right: TContext | null | undefined
) => boolean;

export interface ContextualReadStatusInput<TContext> {
  loading: boolean;
  error: string;
  lastSuccessfulContext: TContext | null;
  currentContext: TContext;
  isSameContext: ContextComparator<TContext>;
  loadingText: string;
  refreshingText: string;
  staleText: string;
  initialErrorPrefix: string;
  lastSuccessAt?: number | null;
}

export function hasLastGoodForContext<TContext>(
  lastSuccessfulContext: TContext | null,
  currentContext: TContext,
  isSameContext: ContextComparator<TContext>
): boolean {
  return lastSuccessfulContext !== null && isSameContext(lastSuccessfulContext, currentContext);
}

export function shouldClearLastGoodForRequest<TContext>(
  lastSuccessfulContext: TContext | null,
  requestContext: TContext,
  isSameContext: ContextComparator<TContext>
): boolean {
  return !hasLastGoodForContext(lastSuccessfulContext, requestContext, isSameContext);
}

export function buildContextualReadStatus<TContext>(input: ContextualReadStatusInput<TContext>): string {
  const hasLastGood = hasLastGoodForContext(input.lastSuccessfulContext, input.currentContext, input.isSameContext);
  const lastSuccessText = input.lastSuccessAt ? ` · 最后成功 ${formatLastGoodTime(input.lastSuccessAt)}` : "";
  if (input.loading && hasLastGood) {
    return `${input.refreshingText}${lastSuccessText}`;
  }
  if (input.loading) {
    return input.loadingText;
  }
  if (input.error && hasLastGood) {
    return `${input.staleText}：${input.error}${lastSuccessText}`;
  }
  if (input.error) {
    return `${input.initialErrorPrefix}：${input.error}`;
  }
  return "";
}

export function formatLastGoodTime(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString();
}
