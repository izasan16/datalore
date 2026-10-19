import axios from 'axios';

export const http = axios.create({ baseURL: '/api', timeout: 15000 });

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('Datalore.token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401 && !location.pathname.startsWith('/datalore/login')) {
      localStorage.removeItem('Datalore.token');
      localStorage.removeItem('Datalore.user');
      location.href = '/datalore/login';
    }
    return Promise.reject(err);
  },
);

export async function getHealth(): Promise<{ status: string; version: string }> {
  const res = await http.get('/health');
  return res.data;
}
