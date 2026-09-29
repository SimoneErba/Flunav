import { Configuration } from '../api-client/configuration';
import { ScenarioControllerApi, SimulationTemplateControllerApi, ConveyorPresetControllerApi, SimulationComparisonControllerApi, MultiSimulationsApi } from '../api-client';
import { axiosInstance } from './axiosInstance';
import { baseURL } from './config';

const config = new Configuration({ basePath: baseURL });
export const experimentExportApi = new MultiSimulationsApi(config, undefined, axiosInstance);
export const scenariosApi = new ScenarioControllerApi(config, undefined, axiosInstance);
export const templatesApi = new SimulationTemplateControllerApi(config, undefined, axiosInstance);
export const presetsApi = new ConveyorPresetControllerApi(config, undefined, axiosInstance);
export const comparisonsApi = new SimulationComparisonControllerApi(config, undefined, axiosInstance);

export const downloadDocument = (name: string, value: unknown) => {
  const content = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  const url = URL.createObjectURL(new Blob([content], { type: name.endsWith('.csv') ? 'text/csv;charset=utf-8' : 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
};
