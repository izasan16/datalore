import { http } from './http';

export type ScheduleType = 'MANUAL' | 'ONCE' | 'CRON';
export type TaskStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED';

export interface EtlTask {
  id: number;
  name: string;
  description: string;
  sourceDatasourceId: number;
  targetDatasourceId: number;
  status: TaskStatus;
  scheduleType: ScheduleType;
  onceAt: string;
  cronExpression: string;
  configVersion: number;
  hasMapping: boolean;
  inputType?: 'TABLE' | 'SELECT';
  sourceSchema?: string;
  sourceTable?: string;
  selectSql?: string;
  parametersJson?: string;
  targetSchema?: string;
  targetTable?: string;
  targetTableMode?: 'CREATE' | 'EXISTING';
  writeMode?: 'OVERWRITE' | 'APPEND' | 'UPSERT';
  fieldMappingsJson?: string;
  upsertKeysJson?: string;
  createTableDdl?: string;
  updatedAt: string;
}

export interface EtlRun {
  id: number;
  taskId: number;
  status: 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELED';
  currentAttempt: number;
  readRows: number;
  writtenRows: number;
  errorSummary?: string;
  workerRunId?: string;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface EtlTaskPayload {
  name: string;
  description?: string;
  sourceDatasourceId: number;
  targetDatasourceId: number;
  scheduleType: ScheduleType;
  onceAt?: string;
  cronExpression?: string;
  scheduleEndAt?: string;
  maxRetries?: number;
  retryIntervalSeconds?: number;
  inputType?: 'TABLE' | 'SELECT';
  sourceSchema?: string;
  sourceTable?: string;
  selectSql?: string;
  parametersJson?: string;
  targetSchema?: string;
  targetTable?: string;
  targetTableMode?: 'CREATE' | 'EXISTING';
  writeMode?: 'OVERWRITE' | 'APPEND' | 'UPSERT';
  fieldMappingsJson?: string;
  upsertKeysJson?: string;
  createTableDdl?: string;
}

export interface ParsedDdlColumn { name: string; rawType: string; platformType: string; }
export interface ParsedDdl { schema: string | null; table: string; columns: ParsedDdlColumn[]; }

export function listEtlTasks() { return http.get<EtlTask[]>('/etl').then((r) => r.data); }
export function getEtlTask(id: number) { return http.get<EtlTask>(`/etl/${id}`).then((r) => r.data); }
export function createEtlTask(payload: EtlTaskPayload) { return http.post<{ id: number }>('/etl', payload).then((r) => r.data); }
export function updateEtlTask(id: number, payload: EtlTaskPayload) { return http.put(`/etl/${id}`, payload).then((r) => r.data); }
export function deleteEtlTask(id: number) { return http.delete(`/etl/${id}`).then((r) => r.data); }
export function setEtlStatus(id: number, status: 'ACTIVE' | 'PAUSED') { return http.post(`/etl/${id}/status`, { status }).then((r) => r.data); }
export function copyEtlTask(id: number, name: string) { return http.post(`/etl/${id}/copy`, { name }).then((r) => r.data); }
export function parseEtlDdl(ddl: string) { return http.post<ParsedDdl>('/etl/ddl/parse', { ddl }).then((r) => r.data); }
export function runEtlTask(id: number, parameters: Record<string, unknown> = {}) { return http.post<EtlRun>(`/etl/${id}/runs`, parameters).then((r) => r.data); }
export function listEtlRuns(id: number) { return http.get<EtlRun[]>(`/etl/${id}/runs`).then((r) => r.data); }
export function getEtlRun(id: number) { return http.get<EtlRun>(`/etl/runs/${id}`).then((r) => r.data); }
export function cancelEtlRun(id: number) { return http.post(`/etl/runs/${id}/cancel`).then((r) => r.data); }
export function retryEtlRun(id: number) { return http.post<EtlRun>(`/etl/runs/${id}/retry`).then((r) => r.data); }
