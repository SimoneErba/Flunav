import { axiosInstance } from './axiosInstance';

export type ArrivalDistribution = 'FIXED' | 'POISSON';
export type MultiSimulationStatus =
  | 'DRAFT' | 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_FAILURES'
  | 'CANCELLING' | 'CANCELLED' | 'FAILED';

export interface DestinationProbability {
  destination: string;
  probability: number;
}

export interface ConveyorFailureConfiguration {
  conveyorId: string;
  failuresPerHour: number;
  repairDurationSeconds: number | null;
}

export interface MultiSimulationConfiguration {
  name: string;
  simulationDurationSeconds: number;
  numberOfRuns: number;
  arrival: {
    ratePerHour: number;
    distribution: ArrivalDistribution;
    rateVariationPercent: number;
  };
  sourceLocationId: string;
  destinations: DestinationProbability[];
  conveyorFailures: ConveyorFailureConfiguration[];
  baseSeed: number | null;
  simulationStartTime: string | null;
  includeActiveItems: boolean;
}

export interface MultiSimulationEstimate {
  expectedItemsPerRun: number;
  expectedItemsTotal: number;
  locationCount: number;
  conveyorCount: number;
  configuredParallelRuns: number;
  parallelRuns: number;
  estimatedSeconds: number;
  lowerSeconds: number;
  upperSeconds: number;
}

export interface MultiSimulationResponse {
  id: string;
  configuration: MultiSimulationConfiguration;
  baseSeed: number;
  status: MultiSimulationStatus;
  completedRuns: number;
  failedRuns: number;
  totalRuns: number;
  topologyVersion: string;
  configurationVersion: string;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  cancelRequested: boolean;
  error: string | null;
}

export interface MetricDistribution {
  sampleCount: number;
  mean: number;
  median: number;
  standardDeviation: number;
  minimum: number;
  maximum: number;
  p5: number;
  p25: number;
  p75: number;
  p95: number;
}

export interface MultiSimulationReport {
  multiSimulationId: string;
  completedRuns: number;
  failedRuns: number;
  cancelledRuns: number;
  metrics: Record<string, MetricDistribution>;
  generatedAt: string;
}

export const multiSimulationApi = {
  list: async () => (await axiosInstance.get<MultiSimulationResponse[]>('/api/multi-simulations')).data,
  create: async (configuration: MultiSimulationConfiguration) =>
    (await axiosInstance.post<MultiSimulationResponse>('/api/multi-simulations', configuration)).data,
  estimate: async (configuration: MultiSimulationConfiguration) =>
    (await axiosInstance.post<MultiSimulationEstimate>('/api/multi-simulations/estimate', configuration)).data,
  get: async (id: string) =>
    (await axiosInstance.get<MultiSimulationResponse>(`/api/multi-simulations/${id}`)).data,
  run: async (id: string) =>
    (await axiosInstance.post<MultiSimulationResponse>(`/api/multi-simulations/${id}/run`)).data,
  cancel: async (id: string) =>
    (await axiosInstance.post<MultiSimulationResponse>(`/api/multi-simulations/${id}/cancel`)).data,
  report: async (id: string) =>
    (await axiosInstance.get<MultiSimulationReport>(`/api/multi-simulations/${id}/report`)).data,
};
