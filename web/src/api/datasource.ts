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
  extraConfig?: string | null;
}

export interface ColumnInfo {
  name: string;
  type: string;
  nullable?: string;
  default?: string | null;
  comment?: string | null;
  charLength?: string | null;
  numericPrecision?: string | null;
  numericScale?: string | null;
}

export function listDatasources() {
  return http.get<DataSourceInfo[]>('/datasources').then((r) => r.data);
}

export function createDatasource(payload: {
  name: string; type: string; host: string; port?: number;
  databaseName?: string; schemaName?: string; username?: string; password?: string;
  extraConfig?: Record<string, string>;
}) {
  return http.post('/datasources', payload).then((r) => r.data);
}

export function updateDatasource(id: number, payload: {
  name?: string; host?: string; port?: number;
  databaseName?: string; schemaName?: string; username?: string; password?: string;
  extraConfig?: Record<string, string>;
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

export interface ColumnChange {
  op: 'add' | 'update';
  column?: string;
  name: string;
  base?: string | null;
  length?: number;
  precision?: number;
  scale?: number;
  nullable: boolean;
  defaultValue?: string;
  comment?: string;
}

export interface ColumnTypeDef {
  base: string;
  label: string;
  param: 'NONE' | 'LENGTH' | 'PRECISION';
  family: 'INT' | 'DECIMAL' | 'BOOL' | 'TEXT' | 'DATETIME' | 'OTHER';
  aliases: string[];
}

export function columnTypes(dsType: string) {
  return http.get<ColumnTypeDef[]>('/datasources/column-types/' + dsType).then((r) => r.data);
}

export function batchColumns(id: number, schema: string | undefined, table: string,
                             changes: ColumnChange[], preview: boolean) {
  return http.post('/datasources/' + id + '/tables/columns/batch', { schema, table, changes, preview })
    .then((r) => r.data.statements as string[]);
}

export interface TableRef {
  type: 'etl_source' | 'etl_target' | 'api_pub' | 'imported';
  id: number;
  name: string;
  running?: boolean;
  enabled?: boolean;
}

export function tableRefs(id: number, schema: string | undefined, table: string) {
  const params: Record<string, unknown> = { table };
  if (schema) params.schema = schema;
  return http.get<TableRef[]>(`/datasources/${id}/tables/refs`, { params }).then((r) => r.data);
}

export function deleteTable(id: number, schema: string | undefined, table: string, force: boolean) {
  const params: Record<string, unknown> = { table, force };
  if (schema) params.schema = schema;
  return http.delete(`/datasources/${id}/tables`, { params }).then((r) => r.data);
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

