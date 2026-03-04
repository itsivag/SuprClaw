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
 * execute_sql intercept:
 *   When SUPABASE_API_KEY is set in mcp.env (self-hosted mode), calls to the
 *   supabase server's execute_sql tool are rerouted to PostgREST's
 *   /rest/v1/rpc/execute_scoped_sql with the scoped JWT — bypassing pg_meta's
 *   superuser connection and enforcing schema-level isolation.
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

  // Buffer the request body so we can inspect it for execute_sql interception.
  let body = '';
  req.setEncoding('utf8');
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    handleRequest(req, res, serverName, remaining, env, route, body);
  });
}).listen(18790, "127.0.0.1", () => {
  console.log("Universal MCP proxy listening on 127.0.0.1:18790");
});

function handleRequest(req, res, serverName, remaining, env, route, body) {
  const upstreamUrl = new URL(route.upstream);
  const auth = route.auth || {};
  const envValue = env[auth.envVar] || "";

  // ── execute_sql intercept for supabase server ─────────────────────────────
  // When SUPABASE_API_KEY is set (self-hosted mode), reroute execute_sql calls
  // to PostgREST RPC instead of pg_meta, enforcing schema-scoped isolation.
  const apiKey = env['SUPABASE_API_KEY'] || '';
  const schema = env['SUPABASE_PROJECT_REF'] || '';

  if (serverName === 'supabase' && apiKey && body) {
    let parsed;
    try { parsed = JSON.parse(body); } catch (_) {}
    if (parsed &&
        parsed.method === 'tools/call' &&
        parsed.params && parsed.params.name === 'execute_sql') {
      const query = (parsed.params.arguments || {}).query || '';
      const rpcBody = JSON.stringify({ query });
      const rpcPath = '/rest/v1/rpc/execute_scoped_sql';

      const rpcOpts = {
        hostname: upstreamUrl.hostname,
        port: 443,
        path: rpcPath,
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'content-length': Buffer.byteLength(rpcBody),
          'accept-profile': schema,
          'content-profile': schema,
          'apikey': apiKey,
          'authorization': 'Bearer ' + envValue,
          'host': upstreamUrl.hostname
        }
      };

      const rpcReq = https.request(rpcOpts, (r) => {
        let data = '';
        r.on('data', c => { data += c; });
        r.on('end', () => {
          const mcpId = parsed.id !== undefined ? parsed.id : null;
          let mcpRes;
          if (r.statusCode >= 200 && r.statusCode < 300) {
            mcpRes = {
              jsonrpc: '2.0', id: mcpId,
              result: { content: [{ type: 'text', text: data }] }
            };
          } else {
            mcpRes = {
              jsonrpc: '2.0', id: mcpId,
              error: { code: -32000, message: data }
            };
          }
          const out = JSON.stringify(mcpRes);
          res.writeHead(200, { 'content-type': 'application/json' });
          res.end(out);
        });
      });
      rpcReq.on('error', (e) => {
        res.writeHead(502); res.end(e.message);
      });
      rpcReq.end(rpcBody);
      return;
    }
  }

  // ── Normal proxy — forward buffered body to upstream ──────────────────────
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
  proxy.write(body);
  proxy.end();
}
