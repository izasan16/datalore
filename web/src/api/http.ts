import axios from 'axios';

export const http = axios.create({ baseURL: '/api', timeout: 15000 });

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('daaspro.token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401 && !location.pathname.startsWith('/login')) {
      localStorage.removeItem('daaspro.token');
      localStorage.removeItem('daaspro.user');
      location.href = '/login';
    }
    return Promise.reject(err);
  },
);

export async function getHealth(): Promise<{ status: string; version: string }> {
  const res = await http.get('/health');
  return res.data;
}
