import { http } from './http';

export type ApiSourceType = 'TABLE' | 'SQL';

export interface ApiParamDef {
  name: string;
  type: 'string' | 'number';
  required: boolean;
  defaultValue?: string;
}

export interface ApiPubInfo {
  id: number;
  name: string;
  path: string;
  sourceType: ApiSourceType;
  datasourceId: number;
  datasourceName: string;
  schemaName: string;
  tableName: string;
  sqlText?: string;
  params?: ApiParamDef[];
  columns: string[];
  filterColumns: string[];
  maxRows: number;
  status: number;
  apiKey?: string;
  updatedAt: string;
}

export interface ApiCredential {
  apiKey: string;
  apiSecret: string | null;
  secretSet: boolean;
}

export interface ApiPubPayload {
  name: string;
  path: string;
  sourceType: ApiSourceType;
  datasourceId: number;
  schemaName?: string;
  tableName?: string;
  columns?: string[];
  filterColumns?: string[];
  sqlText?: string;
  params?: ApiParamDef[];
  maxRows?: number;
  status?: number;
}

export interface ColumnDef {
  name: string;
  type: string;
}

export function listApis() {
  return http.get<ApiPubInfo[]>('/apipubs').then((r) => r.data);
}

export function getApi(id: number) {
  return http.get<ApiPubInfo>(`/apipubs/${id}`).then((r) => r.data);
}

export function createApi(payload: ApiPubPayload) {
  return http.post<ApiPubInfo>('/apipubs', payload).then((r) => r.data);
}

export function updateApi(id: number, payload: Partial<ApiPubPayload>) {
  return http.put<ApiPubInfo>(`/apipubs/${id}`, payload).then((r) => r.data);
}

export function deleteApi(id: number) {
  return http.delete(`/apipubs/${id}`).then((r) => r.data);
}

export function getCredential(id: number) {
  return http.get<ApiCredential>(`/apipubs/${id}/credential`).then((r) => r.data);
}

export function resetCredential(id: number) {
  return http.post<ApiCredential>(`/apipubs/${id}/credential/reset`).then((r) => r.data);
}
