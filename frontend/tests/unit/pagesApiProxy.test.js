import assert from "node:assert/strict";
import test from "node:test";

import { onRequest } from "../../functions/api/[[path]].js";

test("hosted API proxy fails closed when the backend origin is missing", async () => {
  const response = await onRequest({
    request: new Request("https://app.example/api/system/health"),
    env: {},
    params: { path: ["system", "health"] },
  });

  assert.equal(response.status, 503);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.match(await response.text(), /not configured/i);
});

test("hosted API proxy forwards the path, query, and Access service headers", async () => {
  const originalFetch = globalThis.fetch;
  let forwardedUrl;
  let forwardedInit;
  globalThis.fetch = async (url, init) => {
    forwardedUrl = String(url);
    forwardedInit = init;
    return new Response('{"status":"ok"}', {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  };

  try {
    const response = await onRequest({
      request: new Request("https://app.example/api/system/health?verbose=1", {
        headers: { "X-Request-Id": "demo-request" },
      }),
      env: {
        BACKEND_API_ORIGIN: "https://api.example",
        CF_ACCESS_CLIENT_ID: "test-client-id",
        CF_ACCESS_CLIENT_SECRET: "test-client-secret",
      },
      params: { path: ["system", "health"] },
    });

    assert.equal(response.status, 200);
    assert.equal(forwardedUrl, "https://api.example/api/system/health?verbose=1");
    assert.equal(forwardedInit.method, "GET");
    assert.equal(forwardedInit.body, undefined);
    assert.equal(forwardedInit.headers.get("x-request-id"), "demo-request");
    assert.equal(forwardedInit.headers.get("cf-access-client-id"), "test-client-id");
    assert.equal(forwardedInit.headers.get("cf-access-client-secret"), "test-client-secret");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("hosted API proxy converts an Access HTML response into a safe 503", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response("<html>Access login</html>", {
    status: 403,
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });

  try {
    const response = await onRequest({
      request: new Request("https://app.example/api/futures/instruments"),
      env: { BACKEND_API_ORIGIN: "https://api.example" },
      params: { path: ["futures", "instruments"] },
    });

    assert.equal(response.status, 503);
    assert.match(await response.text(), /backend unavailable/i);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
