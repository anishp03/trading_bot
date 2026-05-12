export async function onRequest({ request, env, params }) {
  if (!env.BACKEND_API_ORIGIN) {
    return new Response("Missing BACKEND_API_ORIGIN.", { status: 500 });
  }

  const incomingUrl = new URL(request.url);
  const pathParts = Array.isArray(params.path) ? params.path : [params.path].filter(Boolean);
  const backendUrl = new URL(`/api/${pathParts.join("/")}${incomingUrl.search}`, env.BACKEND_API_ORIGIN);
  const headers = new Headers(request.headers);

  headers.delete("host");
  headers.delete("content-length");

  if (env.CF_ACCESS_CLIENT_ID && env.CF_ACCESS_CLIENT_SECRET) {
    headers.set("CF-Access-Client-Id", env.CF_ACCESS_CLIENT_ID);
    headers.set("CF-Access-Client-Secret", env.CF_ACCESS_CLIENT_SECRET);
  }

  return fetch(backendUrl, {
    method: request.method,
    headers,
    body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
    redirect: "manual",
  });
}
