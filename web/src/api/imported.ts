import { http } from './http';

export interface UploadResult {
  uploadId: string;
  fileName: string;
  columns: string[];
  types: string[];
  previewRows: string[][];
  rowCount: number;
}

export interface ImportedTableInfo {
  id: number;
  name: string;
  tableName: string;
  schemaName: string;
  datasourceId: number;
  rowCount: number;
  fileName: string;
  createdAt: string;
}

export function uploadImported(file: File) {
  const fd = new FormData();
  fd.append('file', file);
  return http.post<UploadResult>('/imported-tables/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((r) => r.data);
}

export function confirmImported(payload: {
  uploadId: string; name: string; fileName: string; columns: string[]; types: string[];
  datasourceId?: number; schemaName?: string; existingTable?: string;
}) {
  return http.post<{ id: number; tableName: string; rowCount: number }>('/imported-tables/confirm', payload)
    .then((r) => r.data);
}

export function listImported() {
  return http.get<ImportedTableInfo[]>('/imported-tables').then((r) => r.data);
}

export function deleteImported(id: number) {
  return http.delete(`/imported-tables/${id}`).then((r) => r.data);
}
