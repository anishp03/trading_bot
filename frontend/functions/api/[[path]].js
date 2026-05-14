export async function onRequest({ request, env, params }) {
  if (!env.BACKEND_API_ORIGIN) {
    return backendUnavailable("Backend API origin is not configured.");
  }

  const incomingUrl = new URL(request.url);
  const pathParts = Array.isArray(params.path) ? params.path : [params.path].filter(Boolean);
  const backendUrl = new URL(`/api/${pathParts.join("/")}${incomingUrl.search}`, env.BACKEND_API_ORIGIN);
  const headers = new Headers(request.headers);

  headers.delete("host");
  headers.delete("content-length");

  try {
    const response = await fetch(backendUrl, {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
      redirect: "manual",
    });

    const contentType = response.headers.get("content-type") || "";
    if (!response.ok && contentType.toLowerCase().includes("text/html")) {
      return backendUnavailable();
    }

    return response;
  } catch (error) {
    console.error(error);
    return backendUnavailable();
  }
}

function backendUnavailable(message = "Backend unavailable. Start the live backend tunnel and try again.") {
  return new Response(message, {
    status: 503,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "text/plain; charset=utf-8",
    },
  });
}
