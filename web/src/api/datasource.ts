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
  nullable?: string;
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

export interface TableFilter {
  col: string;
  op: 'eq' | 'ne' | 'like' | 'gt' | 'gte' | 'lt' | 'lte' | 'isnull' | 'notnull';
  value?: string;
}

export interface TableRowsResult {
  columns: ColumnInfo[];
  rows: string[][];
  rowIds: string[];
  total: number;
  page: number;
  pageSize: number;
  deletable: boolean;
  deleteReason?: string;
}

export interface RowQuery {
  schema?: string;
  table: string;
  page?: number;
  pageSize?: number;
  filters?: TableFilter[];
  orderBy?: string;
  orderDir?: 'asc' | 'desc';
}

export function listRows(id: number, q: RowQuery) {
  const params: Record<string, unknown> = { table: q.table };
  if (q.schema) params.schema = q.schema;
  if (q.page) params.page = q.page;
  if (q.pageSize) params.pageSize = q.pageSize;
  if (q.filters && q.filters.length > 0) params.filters = JSON.stringify(q.filters);
  if (q.orderBy) { params.orderBy = q.orderBy; params.orderDir = q.orderDir ?? 'asc'; }
  return http.get<TableRowsResult>(`/datasources/${id}/tables/rows`, { params }).then((r) => r.data);
}

export function exportRows(id: number, schema: string | undefined, table: string, filters?: TableFilter[]) {
  const params: Record<string, unknown> = { table };
  if (schema) params.schema = schema;
  if (filters && filters.length > 0) params.filters = JSON.stringify(filters);
  return http.get<TableRowsResult>(`/datasources/${id}/tables/rows/export`, { params }).then((r) => r.data);
}

export function insertRow(id: number, schema: string | undefined, table: string, values: Record<string, string>) {
  return http.post(`/datasources/${id}/tables/rows`, { schema, table, values }).then((r) => r.data);
}

export function deleteRows(id: number, schema: string | undefined, table: string, rowIds: string[]) {
  return http.delete(`/datasources/${id}/tables/rows`, { data: { schema, table, rowIds } }).then((r) => r.data);
}

export function alterColumn(id: number, schema: string | undefined, table: string,
                            action: 'addColumn' | 'renameColumn' | 'alterType' | 'dropColumn',
                            payload: { column: string; newColumn?: string; type?: string }) {
  return http.post(`/datasources/${id}/tables/columns`, { schema, table, action, ...payload }).then((r) => r.data);
}

export const STD_COLUMN_TYPES = [
  { label: 'bigint（整数）', value: 'bigint' },
  { label: 'numeric（数值）', value: 'numeric' },
  { label: 'text（文本）', value: 'text' },
  { label: 'timestamp（时间戳）', value: 'timestamp' },
  { label: 'date（日期）', value: 'date' },
  { label: 'boolean（布尔）', value: 'boolean' },
] as const;

export const FILTER_OPS = [
  { label: '等于', value: 'eq' },
  { label: '不等于', value: 'ne' },
  { label: '包含', value: 'like' },
  { label: '大于', value: 'gt' },
  { label: '大于等于', value: 'gte' },
  { label: '小于', value: 'lt' },
  { label: '小于等于', value: 'lte' },
  { label: '为空', value: 'isnull' },
  { label: '不为空', value: 'notnull' },
] as const;

