import { http } from './http';

export interface LoginResult {
  token: string;
  user: { id: number; username: string; displayName: string; role: string };
}

export function login(tenantSlug: string, username: string, password: string) {
  return http.post<LoginResult>('/auth/login', { tenantSlug, username, password }).then((r) => r.data);
}

export function registerTenant(name: string, slug: string, adminUsername: string, adminPassword: string) {
  return http.post('/tenant/register', { name, slug, adminUsername, adminPassword }).then((r) => r.data);
}

export function me() {
  return http.get<{ userId: number; username: string; role: string; tenantId: number }>('/auth/me').then((r) => r.data);
}

export function logout() {
  return http.post('/auth/logout').then((r) => r.data);
}

export interface UserInfo {
  id: number;
  username: string;
  displayName: string;
  role: string;
  status: number;
}

export function listUsers() {
  return http.get<UserInfo[]>('/users').then((r) => r.data);
}

export function createUser(username: string, password: string, displayName: string, role: string) {
  return http.post('/users', { username, password, displayName, role }).then((r) => r.data);
}

export function disableUser(id: number) {
  return http.post(`/users/${id}/disable`).then((r) => r.data);
}
