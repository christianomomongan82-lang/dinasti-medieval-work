import crypto from "node:crypto";
import express from "express";
import { createMcpExpressApp } from "@modelcontextprotocol/express";
import { toNodeHandler } from "@modelcontextprotocol/node";
import { createMcpHandler, McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

const PORT = Number(process.env.PORT || 3000);
const PUBLIC_URL = requireEnv("PUBLIC_URL").replace(/\/$/, "");
const TIKTOK_CLIENT_KEY = requireEnv("TIKTOK_CLIENT_KEY");
const TIKTOK_CLIENT_SECRET = requireEnv("TIKTOK_CLIENT_SECRET");
const MCP_CLIENT_ID = process.env.MCP_CLIENT_ID || "claude-tiktok";
const MCP_CLIENT_SECRET = requireEnv("MCP_CLIENT_SECRET");
const TIKTOK_SCOPES = (process.env.TIKTOK_SCOPES || "user.info.basic,video.list")
  .split(",").map(s => s.trim()).filter(Boolean);

const TIKTOK_CALLBACK = `${PUBLIC_URL}/auth/tiktok/callback`;
let tiktokTokens = null;

const pending = new Map();
const authorizationCodes = new Map();
const accessTokens = new Map();

function requireEnv(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function randomToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString("base64url");
}

function escapeHtml(input) {
  return String(input).replace(/[&<>'"]/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  }[c]));
}

function page(body) {
  return `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><body style="font-family:system-ui;max-width:720px;margin:40px auto;padding:0 20px">${body}</body>`;
}

async function tiktokFetch(path, init = {}) {
  if (!tiktokTokens) throw new Error("TikTok is not connected.");
  if (tiktokTokens.expiresAt < Date.now() + 60000 && tiktokTokens.refreshToken) {
    await refreshTikTokToken();
  }

  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${tiktokTokens.accessToken}`);
  if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");

  const response = await fetch(`https://open.tiktokapis.com${path}`, {...init, headers});
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data?.error?.message || data?.error?.description || `TikTok HTTP ${response.status}`);
  }
  return data;
}

async function refreshTikTokToken() {
  const form = new URLSearchParams({
    client_key: TIKTOK_CLIENT_KEY,
    client_secret: TIKTOK_CLIENT_SECRET,
    grant_type: "refresh_token",
    refresh_token: tiktokTokens.refreshToken
  });

  const response = await fetch("https://open.tiktokapis.com/v2/oauth/token/", {
    method: "POST",
    headers: {"Content-Type":"application/x-www-form-urlencoded"},
    body: form
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || !data.access_token) throw new Error(`TikTok refresh failed: ${JSON.stringify(data)}`);

  tiktokTokens = {
    ...tiktokTokens,
    accessToken: data.access_token,
    refreshToken: data.refresh_token || tiktokTokens.refreshToken,
    expiresAt: Date.now() + Number(data.expires_in || 86400) * 1000
  };
}

function buildServer() {
  const server = new McpServer({name:"tiktok-personal", version:"0.2.0"});

  server.registerTool("tiktok_connection_status", {
    description: "Check whether the TikTok account is connected to this MCP server."
  }, async () => ({
    content:[{type:"text", text:JSON.stringify({
      connected: Boolean(tiktokTokens),
      scopes:TIKTOK_SCOPES
    })}]
  }));

  server.registerTool("tiktok_get_my_profile", {
    description:"Get the connected TikTok profile.",
  }, async () => {
    const fields = ["open_id","display_name","avatar_url"];
    if (TIKTOK_SCOPES.includes("user.info.profile")) fields.push("username","bio_description","is_verified");
    if (TIKTOK_SCOPES.includes("user.info.stats")) fields.push("follower_count","following_count","likes_count","video_count");

    const data = await tiktokFetch(`/v2/user/info/?fields=${encodeURIComponent(fields.join(","))}`);
    return {content:[{type:"text", text:JSON.stringify(data.data?.user ?? data.data, null, 2)}]};
  });

  server.registerTool("tiktok_list_my_videos", {
    description:"List public videos from the connected TikTok account.",
    inputSchema:z.object({
      max_count:z.number().int().min(1).max(20).default(10),
      cursor:z.number().int().optional()
    })
  }, async ({max_count,cursor}) => {
    const fields = "id,create_time,cover_image_url,share_url,video_description,duration,height,width,title,embed_link";
    const data = await tiktokFetch(`/v2/video/list/?fields=${fields}`, {
      method:"POST",
      body:JSON.stringify({max_count,...(cursor === undefined ? {} : {cursor})})
    });
    return {content:[{type:"text", text:JSON.stringify(data,null,2)}]};
  });

  server.registerTool("tiktok_get_video", {
    description:"Get metadata for TikTok videos by ID.",
    inputSchema:z.object({video_ids:z.array(z.string()).min(1).max(20)})
  }, async ({video_ids}) => {
    const data = await tiktokFetch(
      "/v2/video/query/?fields=id,create_time,cover_image_url,share_url,video_description,duration,height,width,title,embed_link",
      {method:"POST", body:JSON.stringify({filters:{video_ids}})}
    );
    return {content:[{type:"text", text:JSON.stringify(data,null,2)}]};
  });

  if (TIKTOK_SCOPES.includes("video.publish")) {
    server.registerTool("tiktok_publish_video_from_url", {
      description:"Direct-post a TikTok video from a public URL. Requires video.publish approval and a verified URL domain/prefix.",
      inputSchema:z.object({
        video_url:z.string().url(),
        title:z.string().max(2200).optional(),
        privacy_level:z.enum(["PUBLIC_TO_EVERYONE","MUTUAL_FOLLOW_FRIENDS","FOLLOWER_OF_CREATOR","SELF_ONLY"])
      })
    }, async ({video_url,title,privacy_level}) => {
      const creator = await tiktokFetch("/v2/post/publish/creator_info/query/", {method:"POST",body:"{}"});
      const allowed = creator.data?.privacy_level_options || [];
      if (!allowed.includes(privacy_level)) {
        throw new Error(`Privacy level ${privacy_level} is not available for this account.`);
      }

      const result = await tiktokFetch("/v2/post/publish/video/init/", {
        method:"POST",
        body:JSON.stringify({
          post_info:{privacy_level,...(title ? {title}: {})},
          source_info:{source:"PULL_FROM_URL",video_url}
        })
      });

      return {content:[{type:"text", text:JSON.stringify(result,null,2)}]};
    });
  }

  return server;
}

const mcpHandler = createMcpHandler(() => buildServer());
const app = createMcpExpressApp({host:"0.0.0.0"});

app.use(express.json());

function requireMcpToken(req,res,next) {
  const header = req.header("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token || !accessTokens.has(token)) return res.status(401).json({error:"unauthorized"});
  next();
}

app.get("/.well-known/oauth-protected-resource", (_req,res) => {
  res.json({resource:`${PUBLIC_URL}/mcp`, authorization_servers:[PUBLIC_URL]});
});
app.get("/.well-known/oauth-protected-resource/mcp", (_req,res) => {
  res.json({resource:`${PUBLIC_URL}/mcp`, authorization_servers:[PUBLIC_URL]});
});
app.get("/.well-known/oauth-authorization-server", (_req,res) => {
  res.json({
    issuer:PUBLIC_URL,
    authorization_endpoint:`${PUBLIC_URL}/oauth/authorize`,
    token_endpoint:`${PUBLIC_URL}/oauth/token`,
    response_types_supported:["code"],
    grant_types_supported:["authorization_code"],
    code_challenge_methods_supported:["S256"]
  });
});

app.get("/oauth/authorize", (req,res) => {
  const {response_type,client_id,redirect_uri,state,code_challenge} = req.query;
  if (response_type !== "code" || client_id !== MCP_CLIENT_ID || typeof redirect_uri !== "string" || typeof state !== "string") {
    return res.status(400).send("Invalid OAuth request");
  }

  const id = randomToken(24);
  pending.set(id,{
    clientId:client_id,
    redirectUri,
    state,
    codeChallenge:typeof code_challenge === "string" ? code_challenge : null,
    createdAt:Date.now()
  });

  const url = new URL("https://www.tiktok.com/v2/auth/authorize/");
  url.searchParams.set("client_key",TIKTOK_CLIENT_KEY);
  url.searchParams.set("response_type","code");
  url.searchParams.set("scope",TIKTOK_SCOPES.join(","));
  url.searchParams.set("redirect_uri",TIKTOK_CALLBACK);
  url.searchParams.set("state",id);
  res.redirect(url.toString());
});

app.get("/auth/tiktok/callback", async (req,res) => {
  const id = String(req.query.state || "");
  const record = pending.get(id);
  pending.delete(id);

  if (!record || Date.now()-record.createdAt > 10*60*1000) {
    return res.status(400).send(page("<h2>OAuth state expired</h2><p>Reconnect from Claude.</p>"));
  }
  if (req.query.error) {
    return res.status(400).send(page(`<h2>TikTok authorization failed</h2><p>${escapeHtml(req.query.error_description || req.query.error)}</p>`));
  }

  const code = String(req.query.code || "");
  if (!code) return res.status(400).send("TikTok did not return a code.");

  const form = new URLSearchParams({
    client_key:TIKTOK_CLIENT_KEY,
    client_secret:TIKTOK_CLIENT_SECRET,
    code,
    grant_type:"authorization_code",
    redirect_uri:TIKTOK_CALLBACK
  });

  const tokenResponse = await fetch("https://open.tiktokapis.com/v2/oauth/token/", {
    method:"POST",
    headers:{"Content-Type":"application/x-www-form-urlencoded"},
    body:form
  });
  const data = await tokenResponse.json().catch(() => ({}));

  if (!tokenResponse.ok || !data.access_token) {
    return res.status(502).send(page(`<h2>TikTok token exchange failed</h2><pre>${escapeHtml(JSON.stringify(data,null,2))}</pre>`));
  }

  tiktokTokens = {
    accessToken:data.access_token,
    refreshToken:data.refresh_token,
    expiresAt:Date.now()+Number(data.expires_in || 86400)*1000,
    openId:data.open_id
  };

  const mcpCode = randomToken(32);
  authorizationCodes.set(mcpCode,{
    clientId:record.clientId,
    redirectUri:record.redirectUri,
    codeChallenge:record.codeChallenge,
    createdAt:Date.now()
  });

  const callback = new URL(record.redirectUri);
  callback.searchParams.set("code",mcpCode);
  callback.searchParams.set("state",record.state);
  res.redirect(callback.toString());
});

app.post("/oauth/token", express.urlencoded({extended:false}), (req,res) => {
  const {grant_type,code,client_id,client_secret,redirect_uri,code_verifier} = req.body;

  if (grant_type !== "authorization_code" || client_id !== MCP_CLIENT_ID || client_secret !== MCP_CLIENT_SECRET) {
    return res.status(401).json({error:"invalid_client"});
  }

  const record = authorizationCodes.get(code);
  authorizationCodes.delete(code);

  if (!record || record.clientId !== client_id || record.redirectUri !== redirect_uri || Date.now()-record.createdAt > 5*60*1000) {
    return res.status(400).json({error:"invalid_grant"});
  }

  if (record.codeChallenge) {
    if (!code_verifier) return res.status(400).json({error:"invalid_grant"});
    const digest = crypto.createHash("sha256").update(code_verifier).digest("base64url");
    if (digest !== record.codeChallenge) return res.status(400).json({error:"invalid_grant"});
  }

  const token = randomToken(32);
  accessTokens.set(token,{clientId:client_id,createdAt:Date.now()});
  res.json({access_token:token,token_type:"Bearer",expires_in:86400});
});

app.get("/",(_req,res) => res.send(page("<h1>TikTok MCP</h1><p>MCP endpoint: <code>/mcp</code></p><p><a href='/health'>health</a></p>")));
app.get("/health",(_req,res) => res.json({ok:true,tiktok_connected:Boolean(tiktokTokens)}));

app.all("/mcp", requireMcpToken, toNodeHandler(mcpHandler));

setInterval(() => {
  const now = Date.now();
  for (const [key,value] of pending) if (now-value.createdAt > 15*60*1000) pending.delete(key);
  for (const [key,value] of authorizationCodes) if (now-value.createdAt > 15*60*1000) authorizationCodes.delete(key);
  for (const [key,value] of accessTokens) if (now-value.createdAt > 24*60*60*1000) accessTokens.delete(key);
},60000).unref();

app.listen(PORT,() => console.log(`TikTok MCP listening on ${PORT}`));
