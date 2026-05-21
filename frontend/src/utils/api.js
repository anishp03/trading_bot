const DEV_API_BASE_URL = "http://localhost:7071";
export const AUTH_STORAGE_KEY = "tradingbot.auth";

function runtimeApiBaseUrl() {
  if (typeof window !== "undefined") {
    const runtimeUrl = String(window.__TRADINGBOT_CONFIG__?.API_BASE_URL || "").trim();
    if (runtimeUrl) {
      return runtimeUrl;
    }
  }

  const buildUrl = String(import.meta.env.VITE_API_BASE_URL || "").trim();
  if (buildUrl) {
    return buildUrl;
  }

  if (!import.meta.env.DEV && typeof window !== "undefined" && window.location?.origin) {
    return window.location.origin;
  }

  return DEV_API_BASE_URL;
}

export const API_BASE_URL = runtimeApiBaseUrl().replace(/\/+$/, "");

function isDevWriteToLiveBackend(method) {
  const normalizedMethod = String(method || "GET").toUpperCase();
  if (["GET", "HEAD", "OPTIONS"].includes(normalizedMethod)) {
    return false;
  }
  try {
    const base = new URL(API_BASE_URL);
    return import.meta.env.DEV
      && ["localhost", "127.0.0.1"].includes(base.hostname)
      && base.port === "7070";
  } catch {
    return import.meta.env.DEV && /(^|:)7070$/.test(API_BASE_URL);
  }
}

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
  const method = options.method || "GET";

  if (isDevWriteToLiveBackend(method)) {
    return Promise.reject(new Error(
      `Blocked ${String(method).toUpperCase()} ${path}: this Vite dev frontend is pointed at the live backend (${API_BASE_URL}). Use http://localhost:7071 for dev writes.`
    ));
  }

  return fetch(apiUrl(path), {
    ...options,
    method,
    headers,
  });
}

export function isApiNetworkError(error) {
  const name = String(error?.name || "");
  const message = String(error?.message || error || "");
  return name === "AbortError"
    || name === "TypeError"
    || /failed to fetch|networkerror|load failed|abort/i.test(message);
}

export async function readApiErrorMessage(response, fallback = "Request failed.") {
  const contentType = response.headers.get("content-type") || "";
  const text = await response.text();
  const trimmed = text.trim();

  if (!trimmed) {
    return fallback;
  }

  if (
    response.status >= 500
    || contentType.toLowerCase().includes("text/html")
    || /^<!doctype\s+html/i.test(trimmed)
    || /^<html[\s>]/i.test(trimmed)
  ) {
    return "Backend unavailable. Start the live backend tunnel and try again.";
  }

  return trimmed.length > 240 ? `${trimmed.slice(0, 240)}...` : trimmed;
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
