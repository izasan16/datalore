import { http } from './http';

export interface ColumnDef {
  name: string;
  type: string;
}

export interface UploadResult {
  uploadId: string;
  fileName: string;
  columns: string[];
  types: string[];
  previewRows: string[][];
  rowCount: number;
}

export interface DatasetInfo {
  id: number;
  name: string;
  tableName: string;
  rowCount: number;
  fileName: string;
  createdAt: string;
}

export interface DatasetPreview {
  columns: ColumnDef[];
  rows: string[][];
}

export function uploadDataset(file: File) {
  const fd = new FormData();
  fd.append('file', file);
  return http.post<UploadResult>('/datasets/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((r) => r.data);
}

export function confirmDataset(payload: {
  uploadId: string; name: string; fileName: string; columns: string[]; types: string[];
  datasourceId?: number; existingTable?: string;
}) {
  return http.post<{ id: number; tableName: string; rowCount: number }>('/datasets/confirm', payload)
    .then((r) => r.data);
}

export function listDatasets() {
  return http.get<DatasetInfo[]>('/datasets').then((r) => r.data);
}

/** 列出某数据源下的表（用于追加导入时选择目标表） */
export function listDatasetTables(id: number) {
  return http.get<{ name: string }[]>(`/datasources/${id}/tables`).then((r) => r.data);
}

export function previewDataset(id: number) {
  return http.get<DatasetPreview>(`/datasets/${id}/preview`).then((r) => r.data);
}

export function deleteDataset(id: number) {
  return http.delete(`/datasets/${id}`).then((r) => r.data);
}
