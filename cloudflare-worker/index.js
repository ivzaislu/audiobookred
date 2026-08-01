const ORIGIN = "https://rutracker.org";

const ALLOWED_PATHS = new Set([
  "/forum/viewforum.php",
  "/forum/viewtopic.php",
  "/forum/tracker.php",
]);

export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);

    if (incoming.pathname === "/health") {
      return Response.json({
        status: "ok",
        service: "audiobookred-rutracker-worker",
      });
    }

    if (request.method !== "GET") {
      return new Response("Method not allowed", {
        status: 405,
        headers: { Allow: "GET" },
      });
    }

    if (!ALLOWED_PATHS.has(incoming.pathname)) {
      return new Response("Path not allowed", { status: 403 });
    }

    const suppliedToken = request.headers.get("X-Proxy-Token");
    if (!env.PROXY_TOKEN || suppliedToken !== env.PROXY_TOKEN) {
      return new Response("Unauthorized", { status: 401 });
    }

    const target = new URL(ORIGIN);
    target.pathname = incoming.pathname;
    target.search = incoming.search;

    const headers = new Headers({
      "User-Agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
      Accept:
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.7",
    });

    try {
      const upstream = await fetch(target.toString(), {
        method: "GET",
        headers,
        redirect: "follow",
      });

      const responseHeaders = new Headers(upstream.headers);
      responseHeaders.delete("set-cookie");
      responseHeaders.set(
        "X-AudioBookRed-Upstream-Status",
        String(upstream.status),
      );
      responseHeaders.set("Cache-Control", "no-store");

      return new Response(upstream.body, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers: responseHeaders,
      });
    } catch (error) {
      return Response.json(
        {
          status: "error",
          message: error instanceof Error ? error.message : String(error),
        },
        { status: 502 },
      );
    }
  },
};
