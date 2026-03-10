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
 * Supabase tool intercepts (self-hosted mode, when SUPABASE_API_KEY is set):
 *   - Any tool with a 'query' argument (execute_sql, apply_migration, etc.)
 *     is routed to PostgREST /rest/v1/rpc/execute_scoped_sql with the scoped
 *     JWT, bypassing pg_meta's superuser connection.
 *   - Catalog tools (list_tables, list_schemas, list_views, list_functions)
 *     are implemented via information_schema queries through execute_scoped_sql,
 *     which naturally returns only objects visible to the scoped role.
 *   - All other tool calls pass through with apikey + scoped JWT injected.
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

/** Calls execute_scoped_sql via PostgREST and returns an MCP JSON-RPC response. */
function callExecuteScopedSql(hostname, apiKey, scopedJwt, schema, query, mcpId, res) {
  const rpcBody = JSON.stringify({ query });
  const rpcOpts = {
    hostname,
    port: 443,
    path: '/rest/v1/rpc/execute_scoped_sql',
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'content-length': Buffer.byteLength(rpcBody),
      'accept-profile': schema,
      'content-profile': schema,
      'apikey': apiKey,
      'authorization': 'Bearer ' + scopedJwt,
      'host': hostname
    }
  };
  const rpcReq = https.request(rpcOpts, (r) => {
    let data = '';
    r.on('data', c => { data += c; });
    r.on('end', () => {
      let mcpRes;
      if (r.statusCode >= 200 && r.statusCode < 300) {
        mcpRes = { jsonrpc: '2.0', id: mcpId, result: { content: [{ type: 'text', text: data }] } };
      } else {
        mcpRes = { jsonrpc: '2.0', id: mcpId, error: { code: -32000, message: data } };
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify(mcpRes));
    });
  });
  rpcReq.on('error', (e) => { res.writeHead(502); res.end(e.message); });
  rpcReq.end(rpcBody);
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

  // Buffer the request body so we can inspect it for tool interception.
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

  const apiKey = env['SUPABASE_API_KEY'] || '';
  const schema = env['SUPABASE_PROJECT_REF'] || '';

  // ── Supabase tool intercepts (self-hosted mode) ───────────────────────────
  // All tools/call for the supabase server are routed through the scoped role
  // (proj_xxx_rpc) via execute_scoped_sql to enforce schema isolation.
  if (serverName === 'supabase' && apiKey && body) {
    let parsed;
    try { parsed = JSON.parse(body); } catch (_) {}
    if (parsed && parsed.method === 'tools/call' && parsed.params) {
      const toolName = parsed.params.name;
      const args = parsed.params.arguments || {};
      const mcpId = parsed.id !== undefined ? parsed.id : null;
      const hostname = upstreamUrl.hostname;

      // Any tool that carries a SQL query argument — route it through the
      // scoped function. Catches execute_sql, apply_migration, and future tools.
      if (args.query) {
        callExecuteScopedSql(hostname, apiKey, envValue, schema, args.query, mcpId, res);
        return;
      }

      // Catalog tools — implement via information_schema so only the scoped
      // role's visible objects are returned.
      if (toolName === 'list_tables') {
        callExecuteScopedSql(hostname, apiKey, envValue, schema,
          "SELECT table_schema AS schema, table_name AS name " +
          "FROM information_schema.tables " +
          "WHERE table_type = 'BASE TABLE' " +
          "ORDER BY table_name",
          mcpId, res);
        return;
      }

      if (toolName === 'list_schemas') {
        callExecuteScopedSql(hostname, apiKey, envValue, schema,
          "SELECT schema_name AS name " +
          "FROM information_schema.schemata " +
          "WHERE schema_name = '" + schema + "'",
          mcpId, res);
        return;
      }

      if (toolName === 'list_views') {
        callExecuteScopedSql(hostname, apiKey, envValue, schema,
          "SELECT table_schema AS schema, table_name AS name " +
          "FROM information_schema.views " +
          "ORDER BY table_name",
          mcpId, res);
        return;
      }

      if (toolName === 'list_functions') {
        callExecuteScopedSql(hostname, apiKey, envValue, schema,
          "SELECT routine_schema AS schema, routine_name AS name, routine_type AS type " +
          "FROM information_schema.routines " +
          "WHERE routine_schema = '" + schema + "' " +
          "ORDER BY routine_name",
          mcpId, res);
        return;
      }

      // All other tool calls fall through to the normal proxy below,
      // which already injects apikey + scoped JWT via the bearer auth block.
    }
  }

  // ── Normal proxy — forward buffered body to upstream ──────────────────────
  const upstreamBasePath = upstreamUrl.pathname && upstreamUrl.pathname !== "/"
    ? upstreamUrl.pathname.replace(/\/$/, "")
    : "";
  let upstreamPath = upstreamBasePath + remaining;
  const headers = Object.assign({}, req.headers, { host: upstreamUrl.hostname });

  if (auth.type === "bearer") {
    headers["authorization"] = "Bearer " + envValue;
    if (serverName === 'supabase' && apiKey) {
      headers["apikey"] = apiKey;
    }
  } else if (auth.type === "path-prefix") {
    const prefix = (auth.template || "").replace("{key}", envValue);
    upstreamPath = upstreamBasePath + prefix + remaining;
  }

  if (upstreamUrl.search) {
    upstreamPath += (upstreamPath.includes("?") ? "&" : "?") + upstreamUrl.search.slice(1);
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
