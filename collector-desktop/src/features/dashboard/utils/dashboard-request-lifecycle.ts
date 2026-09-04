export interface DashboardRefreshTicket {
  generation: number;
}

export interface DashboardRefreshCycle {
  begin(): DashboardRefreshTicket;
  invalidate(): void;
  isLatest(ticket: DashboardRefreshTicket): boolean;
}

export function createDashboardRefreshCycle(): DashboardRefreshCycle {
  let currentGeneration = 0;

  return {
    begin() {
      currentGeneration += 1;
      return { generation: currentGeneration };
    },
    invalidate() {
      currentGeneration += 1;
    },
    isLatest(ticket) {
      return ticket.generation === currentGeneration;
    }
  };
}
