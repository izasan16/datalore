import axios from 'axios';

export const http = axios.create({ baseURL: '/api', timeout: 15000 });

export async function getHealth(): Promise<{ status: string; version: string }> {
  const res = await http.get('/health');
  return res.data;
}
