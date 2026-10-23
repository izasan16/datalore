import { http } from './http';

export interface QueryResult {
  columns: string[];
  rows: string[][];
  truncated: boolean;
  rowCount: number;
  elapsedMs: number;
  isQuery: boolean;
  affectedRows: number;
}

export interface SqlScriptInfo {
  id: number;
  name: string;
  updatedAt: string;
}

export interface SqlScriptDetail extends SqlScriptInfo {
  content: string;
}

export function runSql(datasourceId: number | undefined, sql: string, runToken?: string) {
  return http.post<QueryResult>('/sql/run', {
    datasourceId: datasourceId == null ? undefined : String(datasourceId),
    sql,
    runToken,
  }, { timeout: 0 }).then((r) => r.data);
}

export function cancelSql(runToken?: string) {
  return http.post('/sql/cancel', runToken ? { runToken } : undefined).then((r) => r.data);
}

export function listScripts() {
  return http.get<SqlScriptInfo[]>('/scripts').then((r) => r.data);
}

export function getScript(id: number) {
  return http.get<SqlScriptDetail>(`/scripts/${id}`).then((r) => r.data);
}

export function createScript(name: string, content: string) {
  return http.post<{ id: number }>('/scripts', { name, content }).then((r) => r.data);
}

export function updateScript(id: number, name: string | undefined, content: string | undefined) {
  return http.put(`/scripts/${id}`, { name, content }).then((r) => r.data);
}

export function deleteScript(id: number) {
  return http.delete(`/scripts/${id}`).then((r) => r.data);
}
