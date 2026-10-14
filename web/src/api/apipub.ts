import { http } from './http';

export interface ApiPubInfo {
  id: number;
  name: string;
  path: string;
  datasourceId: number;
  datasourceName: string;
  schemaName: string;
  tableName: string;
  columns: string[];
  filterColumns: string[];
  maxRows: number;
  status: number;
  grantCount: number;
  updatedAt: string;
}

export interface ApiAppInfo {
  id: number;
  name: string;
  appKey: string;
  secretMask: string;
  createdAt: string;
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

export function createApi(payload: {
  name: string; path: string; datasourceId: number; schemaName?: string; tableName: string; columns: string[];
  filterColumns: string[]; maxRows?: number;
}) {
  return http.post<ApiPubInfo>('/apipubs', payload).then((r) => r.data);
}

export function updateApi(id: number, payload: {
  name?: string; columns?: string[]; filterColumns?: string[];
  maxRows?: number; status?: number;
}) {
  return http.put<ApiPubInfo>(`/apipubs/${id}`, payload).then((r) => r.data);
}

export function deleteApi(id: number) {
  return http.delete(`/apipubs/${id}`).then((r) => r.data);
}

export function getApiGrants(id: number) {
  return http.get<{ appIds: number[] }>(`/apipubs/${id}/grants`).then((r) => r.data.appIds);
}

export function setApiGrants(id: number, appIds: number[]) {
  return http.put(`/apipubs/${id}/grants`, { appIds }).then((r) => r.data);
}

export function listApps() {
  return http.get<ApiAppInfo[]>('/apiapps').then((r) => r.data);
}

export function createApp(name: string) {
  return http.post<{ id: number; appKey: string; appSecret: string }>('/apiapps', { name })
    .then((r) => r.data);
}

export function revealAppSecret(id: number) {
  return http.get<{ appSecret: string }>(`/apiapps/${id}/secret`).then((r) => r.data.appSecret);
}

export function updateApp(id: number, name: string) {
  return http.put<ApiAppInfo>(`/apiapps/${id}`, { name }).then((r) => r.data);
}

export function deleteApp(id: number) {
  return http.delete(`/apiapps/${id}`).then((r) => r.data);
}
