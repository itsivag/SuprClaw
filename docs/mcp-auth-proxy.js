/**
 * Universal MCP Auth Proxy
 *
 * Single HTTP server on 127.0.0.1:18790.
 * Routes requests by path prefix to upstream MCP servers, applying
 * per-server authentication read from /etc/suprclaw/mcp-routes.json
 * and /etc/suprclaw/mcp.env (both root-protected).
 *
 * URL format expected by mcporter:
 *   http://127.0.0.1:18790/{server-name}/{path...}
 *
 * Auth types supported:
 *   bearer      — injects Authorization: Bearer <value> header
 *   path-prefix — prepends template (/{key}/v2) to upstream path
 *
 * Deploy to base image:
 *   sudo cp mcp-auth-proxy.js /usr/local/bin/mcp-auth-proxy.js
 *   sudo chmod 700 /usr/local/bin/mcp-auth-proxy.js
 *   sudo chown root:root /usr/local/bin/mcp-auth-proxy.js
 *   sudo systemctl restart mcp-auth-proxy
 */

const http = require("http");
const https = require("https");
const fs = require("fs");

function readEnv() {
  const text = fs.readFileSync("/etc/suprclaw/mcp.env", "utf8");
  const env = {};
  for (const line of text.split("\n")) {
    const eq = line.indexOf("=");
    if (eq > 0) env[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return env;
}

function readRoutes() {
  return JSON.parse(fs.readFileSync("/etc/suprclaw/mcp-routes.json", "utf8"));
}

http.createServer((req, res) => {
  // Extract server name and remaining path from /serverName/rest?query
  const url = req.url || "/";
  const secondSlash = url.indexOf("/", 1);
  const serverName = secondSlash > 0 ? url.slice(1, secondSlash) : url.slice(1);
  const remaining = secondSlash > 0 ? url.slice(secondSlash) : "/";

  if (!serverName) {
    res.writeHead(400);
    res.end("Missing server name in path");
    return;
  }

  let routes, env;
  try {
    routes = readRoutes();
    env = readEnv();
  } catch (e) {
    res.writeHead(500);
    res.end("Failed to read proxy config: " + e.message);
    return;
  }

  const route = routes[serverName];
  if (!route) {
    res.writeHead(404);
    res.end("Unknown MCP server: " + serverName);
    return;
  }

  const upstreamUrl = new URL(route.upstream);
  const auth = route.auth || {};
  const envValue = env[auth.envVar] || "";

  let upstreamPath = remaining;
  const headers = Object.assign({}, req.headers, { host: upstreamUrl.hostname });

  if (auth.type === "bearer") {
    headers["authorization"] = "Bearer " + envValue;
  } else if (auth.type === "path-prefix") {
    const prefix = (auth.template || "").replace("{key}", envValue);
    upstreamPath = prefix + remaining;
  }

  const opts = {
    hostname: upstreamUrl.hostname,
    port: 443,
    path: upstreamPath,
    method: req.method,
    headers,
  };

  const proxy = https.request(opts, (r) => {
    res.writeHead(r.statusCode, r.headers);
    r.pipe(res);
  });
  proxy.on("error", (e) => {
    res.writeHead(502);
    res.end(e.message);
  });
  req.pipe(proxy);
}).listen(18790, "127.0.0.1", () => {
  console.log("Universal MCP proxy listening on 127.0.0.1:18790");
});
