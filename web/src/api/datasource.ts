import { http } from './http';

export interface DataSourceInfo {
  id: number;
  name: string;
  type: string;
  host: string;
  port: number;
  databaseName: string;
  schemaName: string;
  status: number;
}

export interface ColumnInfo {
  name: string;
  type: string;
}

export function listDatasources() {
  return http.get<DataSourceInfo[]>('/datasources').then((r) => r.data);
}

export function createDatasource(payload: {
  name: string; type: string; host: string; port?: number;
  databaseName: string; schemaName?: string; username: string; password: string;
}) {
  return http.post('/datasources', payload).then((r) => r.data);
}

export function updateDatasource(id: number, payload: {
  name?: string; host?: string; port?: number;
  databaseName?: string; schemaName?: string; username?: string; password?: string;
}) {
  return http.put(`/datasources/${id}`, payload).then((r) => r.data);
}

export function testDatasource(id: number) {
  return http.post(`/datasources/${id}/test`).then((r) => r.data);
}

export function deleteDatasource(id: number) {
  return http.delete(`/datasources/${id}`).then((r) => r.data);
}

export function listSchemas(id: number) {
  return http.get<{ name: string }[]>(`/datasources/${id}/schemas`).then((r) => r.data);
}

export function listTables(id: number, schema?: string) {
  return http.get<{ name: string }[]>(`/datasources/${id}/tables`, { params: schema ? { schema } : undefined }).then((r) => r.data);
}

export function listColumns(id: number, table: string, schema?: string) {
  return http.get<ColumnInfo[]>(`/datasources/${id}/tables/${table}/columns`, { params: schema ? { schema } : undefined }).then((r) => r.data);
}
