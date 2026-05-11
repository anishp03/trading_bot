const DEFAULT_API_BASE_URL = "http://localhost:7070";
export const AUTH_STORAGE_KEY = "tradingbot.auth";

function runtimeApiBaseUrl() {
  if (typeof window !== "undefined" && window.__TRADINGBOT_CONFIG__?.API_BASE_URL) {
    return window.__TRADINGBOT_CONFIG__.API_BASE_URL;
  }
  return import.meta.env.VITE_API_BASE_URL || DEFAULT_API_BASE_URL;
}

export const API_BASE_URL = runtimeApiBaseUrl().replace(/\/+$/, "");

export function readStoredAuth() {
  if (typeof window === "undefined") {
    return null;
  }

  try {
    const stored = window.localStorage.getItem(AUTH_STORAGE_KEY);
    return stored ? JSON.parse(stored) : null;
  } catch (error) {
    console.error(error);
    return null;
  }
}

export function writeStoredAuth(auth) {
  if (typeof window === "undefined") {
    return;
  }

  if (!auth?.token) {
    window.localStorage.removeItem(AUTH_STORAGE_KEY);
    return;
  }

  window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(auth));
}

export function clearStoredAuth() {
  writeStoredAuth(null);
}

export function apiUrl(path) {
  const normalizedPath = String(path || "").startsWith("/") ? path : `/${path || ""}`;
  return `${API_BASE_URL}${normalizedPath}`;
}

export function apiFetch(path, options = {}) {
  const headers = new Headers(options.headers || {});
  const auth = readStoredAuth();

  if (auth?.token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${auth.token}`);
  }

  return fetch(apiUrl(path), {
    ...options,
    headers,
  });
}

export function apiFormFetch(path, fields = {}, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("Content-Type", "application/x-www-form-urlencoded");

  const body = new URLSearchParams();
  Object.entries(fields).forEach(([key, value]) => {
    body.set(key, value == null ? "" : String(value));
  });

  return apiFetch(path, {
    ...options,
    method: options.method || "POST",
    headers,
    body,
  });
}
