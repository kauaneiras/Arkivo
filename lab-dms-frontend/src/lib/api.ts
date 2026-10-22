import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1',
});

/** Seconds left on a JWT, or null when it cannot be read. */
function secondsLeft(token: string): number | null {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return typeof payload.exp === 'number' ? payload.exp - Math.floor(Date.now() / 1000) : null;
  } catch {
    return null;
  }
}

// One refresh at a time: ten requests fired together must not trade the same
// refresh token ten times (Keycloak rotates it; the second use fails).
let refreshing: Promise<string | null> | null = null;
async function refreshIfSso(): Promise<string | null> {
  if (!refreshing) {
    refreshing = import('./sso')
      .then(({ refreshSsoToken }) => refreshSsoToken())
      .catch(() => null)
      .finally(() => { refreshing = null; });
  }
  return refreshing;
}

api.interceptors.request.use(async (config) => {
  let token = localStorage.getItem('jwt_token');
  // An SSO access token is short-lived (minutes). Renew it a little before it
  // expires so the person never sees the login page in the middle of an edit.
  if (token && localStorage.getItem('labdms.sso.provider') === 'KEYCLOAK') {
    const left = secondsLeft(token);
    if (left !== null && left < 60) {
      const renewed = await refreshIfSso();
      if (renewed) token = renewed;
    }
  }
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Expired/invalid session: for SSO, try one silent renewal and replay the
// request; otherwise clear credentials and send the user to login.
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const status = error.response?.status;
    const original = error.config;
    if (status === 401 && original && !original.__retried
        && localStorage.getItem('labdms.sso.provider') === 'KEYCLOAK') {
      const renewed = await refreshIfSso();
      if (renewed) {
        original.__retried = true;
        original.headers = { ...(original.headers ?? {}), Authorization: `Bearer ${renewed}` };
        return api.request(original);
      }
    }
    if (status === 401 && !window.location.pathname.startsWith('/login')) {
      localStorage.removeItem('jwt_token');
      localStorage.removeItem('user_info');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default api;
