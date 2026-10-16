import { http } from './http';

export interface ScriptInfo {
  id: number;
  name: string;
  datasourceId: number;
  updatedAt: string;
}

export interface ScriptDetail extends ScriptInfo {
  content: string;
  params: string[];
}

export interface RunResult {
  runId: number;
  status: string;
}

export interface RunStatus {
  runId: number;
  status: string;
  error: string;
  startedAt: string;
  finishedAt: string;
}

export interface RunHistoryItem {
  id: number;
  status: string;
  startedAt: string;
  finishedAt: string;
}

export interface HdfsEntry {
  name: string;
  path: string;
  isDir: boolean;
  size: number;
  modificationTime: string;
}

export function listScripts() {
  return http.get<ScriptInfo[]>('/pyscript').then(r => r.data);
}

export function getScript(id: number) {
  return http.get<ScriptDetail>(`/pyscript/${id}`).then(r => r.data);
}

export function createScript(payload: { name: string; datasourceId: number; content: string }) {
  return http.post<ScriptDetail>('/pyscript', payload).then(r => r.data);
}

export function updateScript(id: number, payload: { name?: string; datasourceId?: number; content?: string }) {
  return http.put<ScriptDetail>(`/pyscript/${id}`, payload).then(r => r.data);
}

export function deleteScript(id: number) {
  return http.delete(`/pyscript/${id}`).then(r => r.data);
}

export function runScript(id: number, params?: Record<string, string>) {
  return http.post<RunResult>(`/pyscript/${id}/run`, { params: params ?? {} }).then(r => r.data);
}

export function refreshRun(runId: number) {
  return http.get<RunStatus>(`/pyscript/runs/${runId}/refresh`).then(r => r.data);
}

export function getRunLogs(runId: number, offset: number) {
  return http.get<{ logs: string; offset: number }>(`/pyscript/runs/${runId}/logs`, { params: { offset } }).then(r => r.data);
}

export function runHistory(id: number) {
  return http.get<RunHistoryItem[]>(`/pyscript/${id}/history`).then(r => r.data);
}

export function listHdfs(dsId: number, path: string) {
  return http.get<HdfsEntry[]>(`/pyscript/hdfs/${dsId}`, { params: { path } }).then(r => r.data);
}
