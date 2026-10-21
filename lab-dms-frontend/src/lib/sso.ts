/**
 * Single sign-on from the browser: OpenID Connect authorization code + PKCE.
 *
 * No library on purpose. The flow is four steps — build the authorize URL,
 * send the browser there, trade the code for tokens, store the access token —
 * and the only cryptography is a SHA-256 the browser already has. A public
 * client (no secret) with PKCE is what Keycloak recommends for SPAs.
 *
 * The access token is used exactly like the local one: `Authorization: Bearer`.
 * The server tells the two apart by the signing algorithm and validates the SSO
 * token against the provider's published keys.
 */
import api from './api';

export interface AuthConfig {
  ssoEnabled: boolean;
  localLoginEnabled: boolean;
  issuerUri: string | null;
  clientId: string | null;
  authorizationEndpoint: string | null;
  tokenEndpoint: string | null;
  logoutEndpoint: string | null;
  providerName: string | null;
}

const KEY_VERIFIER = 'labdms.sso.verifier';
const KEY_STATE = 'labdms.sso.state';
const KEY_CONFIG = 'labdms.sso.config';
const KEY_ID_TOKEN = 'labdms.sso.idToken';
const KEY_PROVIDER = 'labdms.sso.provider';
const KEY_REFRESH = 'labdms.sso.refresh';

export async function fetchAuthConfig(): Promise<AuthConfig> {
  const res = await api.get('/auth/config');
  const cfg: AuthConfig = res.data;
  try { sessionStorage.setItem(KEY_CONFIG, JSON.stringify(cfg)); } catch { /* ignore */ }
  return cfg;
}

function randomString(bytes = 32) {
  const arr = new Uint8Array(bytes);
  crypto.getRandomValues(arr);
  return base64url(arr);
}

function base64url(bytes: Uint8Array) {
  let s = '';
  bytes.forEach((b) => { s += String.fromCharCode(b); });
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function sha256(text: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return base64url(new Uint8Array(digest));
}

export function callbackUri() {
  return `${window.location.origin}/auth/callback`;
}

/** Sends the browser to the provider's login page. */
export async function startSsoLogin(cfg: AuthConfig) {
  if (!cfg.authorizationEndpoint || !cfg.clientId) throw new Error('SSO is not configured');
  const verifier = randomString(48);
  const state = randomString(16);
  sessionStorage.setItem(KEY_VERIFIER, verifier);
  sessionStorage.setItem(KEY_STATE, state);
  sessionStorage.setItem(KEY_CONFIG, JSON.stringify(cfg));

  const params = new URLSearchParams({
    client_id: cfg.clientId,
    response_type: 'code',
    scope: 'openid profile email',
    redirect_uri: callbackUri(),
    state,
    code_challenge: await sha256(verifier),
    code_challenge_method: 'S256',
  });
  window.location.assign(`${cfg.authorizationEndpoint}?${params}`);
}

/**
 * Trades the code in the callback URL for tokens.
 *
 * @return the access token, or throws with a reason the screen can show
 */
export async function finishSsoLogin(search: string): Promise<{ accessToken: string }> {
  const params = new URLSearchParams(search);
  const error = params.get('error');
  if (error) throw new Error(params.get('error_description') || error);

  const code = params.get('code');
  const state = params.get('state');
  const expectedState = sessionStorage.getItem(KEY_STATE);
  const verifier = sessionStorage.getItem(KEY_VERIFIER);
  const raw = sessionStorage.getItem(KEY_CONFIG);
  sessionStorage.removeItem(KEY_STATE);
  sessionStorage.removeItem(KEY_VERIFIER);
  if (!code || !state || !verifier || !raw) throw new Error('missing_state');
  if (state !== expectedState) throw new Error('state_mismatch');

  const cfg: AuthConfig = JSON.parse(raw);
  if (!cfg.tokenEndpoint || !cfg.clientId) throw new Error('SSO is not configured');

  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: cfg.clientId,
    code,
    redirect_uri: callbackUri(),
    code_verifier: verifier,
  });
  const res = await fetch(cfg.tokenEndpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!res.ok) {
    let detail = `${res.status}`;
    try { const j = await res.json(); detail = j.error_description || j.error || detail; } catch { /* ignore */ }
    throw new Error(detail);
  }
  const tokens = await res.json();
  if (tokens.id_token) sessionStorage.setItem(KEY_ID_TOKEN, tokens.id_token);
  if (tokens.refresh_token) sessionStorage.setItem(KEY_REFRESH, tokens.refresh_token);
  localStorage.setItem(KEY_PROVIDER, 'KEYCLOAK');
  return { accessToken: tokens.access_token };
}

/**
 * Trades the refresh token for a new access token, silently.
 *
 * The access token lives minutes; the refresh token lives as long as the
 * Keycloak session. Renewing in the background is what keeps someone who is
 * editing a long document from being thrown to the login page mid-sentence.
 * The new refresh token replaces the old one (Keycloak rotates them).
 *
 * @return the new access token, or null when the session is gone for good
 */
export async function refreshSsoToken(): Promise<string | null> {
  const refresh = sessionStorage.getItem(KEY_REFRESH);
  const raw = sessionStorage.getItem(KEY_CONFIG);
  if (!refresh || !raw) return null;
  const cfg: AuthConfig = JSON.parse(raw);
  if (!cfg.tokenEndpoint || !cfg.clientId) return null;
  try {
    const res = await fetch(cfg.tokenEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'refresh_token', client_id: cfg.clientId, refresh_token: refresh }),
    });
    if (!res.ok) {
      sessionStorage.removeItem(KEY_REFRESH);
      return null;
    }
    const tokens = await res.json();
    if (tokens.refresh_token) sessionStorage.setItem(KEY_REFRESH, tokens.refresh_token);
    if (tokens.id_token) sessionStorage.setItem(KEY_ID_TOKEN, tokens.id_token);
    if (tokens.access_token) {
      localStorage.setItem('jwt_token', tokens.access_token);
      return tokens.access_token;
    }
    return null;
  } catch {
    return null;
  }
}

export function isSsoSession() {
  try { return localStorage.getItem(KEY_PROVIDER) === 'KEYCLOAK'; } catch { return false; }
}

/**
 * Ends the provider session too, otherwise the next "Entrar com SSO" signs the
 * same person straight back in without asking — which is not what someone
 * leaving a shared bench computer expects from "Sair".
 */
export function ssoLogoutRedirect(): boolean {
  if (!isSsoSession()) return false;
  let cfg: AuthConfig | null = null;
  try { const raw = sessionStorage.getItem(KEY_CONFIG); cfg = raw ? JSON.parse(raw) : null; } catch { cfg = null; }
  const idToken = sessionStorage.getItem(KEY_ID_TOKEN);
  localStorage.removeItem(KEY_PROVIDER);
  sessionStorage.removeItem(KEY_ID_TOKEN);
  sessionStorage.removeItem(KEY_REFRESH);
  if (!cfg?.logoutEndpoint) return false;
  const params = new URLSearchParams({ post_logout_redirect_uri: `${window.location.origin}/login` });
  if (cfg.clientId) params.append('client_id', cfg.clientId);
  if (idToken) params.append('id_token_hint', idToken);
  window.location.assign(`${cfg.logoutEndpoint}?${params}`);
  return true;
}
