import http from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { Innertube } from 'youtubei.js';
import { analyzeHistoryPages, delegatedPageIdFrom, extractHistoryItems } from './history-parser.mjs';

const port = boundedInt(process.env.PORT, 8787, 1, 65535);
const dataDir = process.env.DATA_DIR || '/app/data';
const historyLimit = boundedInt(process.env.HISTORY_LIMIT, 18, 1, 50);
const maxPages = boundedInt(process.env.MAX_HISTORY_PAGES, 2, 1, 5);
const maxAccountIndices = boundedInt(process.env.MAX_ACCOUNT_INDICES, 3, 1, 5);
const maxProfileAttempts = boundedInt(process.env.MAX_PROFILE_ATTEMPTS, 12, 1, 25);
const cacheMilliseconds = boundedInt(process.env.CACHE_SECONDS, 75, 10, 900) * 1000;
const cookiePath = join(dataDir, 'youtube.cookie');
const keyPath = join(dataDir, 'access.key');
const identityPath = join(dataDir, 'youtube.identity.json');
let sessions = new Map(), cookieFingerprint = '', cache = null, preferredIdentity = null;

function boundedInt(value, fallback, minimum, maximum) {
  const number = Number.parseInt(value ?? '', 10);
  return Number.isFinite(number) ? Math.min(maximum, Math.max(minimum, number)) : fallback;
}

async function secret(path) {
  const value = (await readFile(path, 'utf8')).trim();
  if (!value) throw new Error(`Missing ${path.split('/').pop()}`);
  return value;
}

async function authorized(req) {
  const expected = await secret(keyPath);
  const supplied = String(req.headers['x-quiet-home-key'] || '');
  const a = Buffer.from(expected), b = Buffer.from(supplied);
  return a.length === b.length && timingSafeEqual(a, b);
}

async function configuredIdentity() {
  try {
    const value = JSON.parse(await readFile(identityPath, 'utf8'));
    const accountIndex = Number(value.accountIndex);
    const pageId = typeof value.pageId === 'string' ? value.pageId : '';
    if (!Number.isInteger(accountIndex) || accountIndex < 0 || accountIndex > 9) return null;
    if (pageId && !/^\d{10,30}$/.test(pageId)) return null;
    return { accountIndex, pageId };
  } catch {
    return null;
  }
}

async function innertube(identity = { accountIndex: 0, pageId: '' }) {
  const cookie = (await secret(cookiePath)).replace(/^cookie\s*:\s*/i, '');
  const fingerprint = createHash('sha256').update(cookie).digest('hex');
  if (fingerprint !== cookieFingerprint) {
    sessions = new Map();
    cookieFingerprint = fingerprint;
    cache = null;
    preferredIdentity = null;
  }
  const key = `${identity.accountIndex}:${identity.pageId}`;
  if (!sessions.has(key)) {
    const options = {
      cookie,
      lang: 'de',
      location: 'DE',
      account_index: identity.accountIndex,
      retrieve_player: false
    };
    if (identity.pageId) options.on_behalf_of_user = identity.pageId;
    const created = await Innertube.create(options);
    if (!created.session.logged_in) throw new Error('YouTube rejected the supplied cookie');
    sessions.set(key, created);
  }
  return sessions.get(key);
}

async function historyPages(yt) {
  const pages = [];
  let history = await yt.getHistory();
  for (let page = 0; page < maxPages; page += 1) {
    pages.push(Array.from(history.videos || []));
    if (!history.has_continuation || page + 1 >= maxPages) break;
    history = await history.getContinuation();
  }
  return pages;
}

async function resultForIdentity(identity, existingSession = null) {
  const yt = existingSession || await innertube(identity);
  const pages = await historyPages(yt);
  const options = { minimum: 2, maximum: 95 };
  return {
    identity,
    items: extractHistoryItems(pages, { ...options, limit: historyLimit }),
    diagnostics: analyzeHistoryPages(pages, options)
  };
}

function resultScore(result) {
  const diagnosis = result.diagnostics;
  return result.items.length * 1_000_000 + diagnosis.eligible * 10_000 + diagnosis.withProgress * 100 + diagnosis.candidates;
}

async function discoverProfileResults() {
  const results = [], tried = new Set(), discovered = [];
  let accountIndicesTested = 0, delegatedProfilesFound = 0, lastError = null;
  const tryIdentity = async (identity, existingSession = null) => {
    const key = `${identity.accountIndex}:${identity.pageId}`;
    if (tried.has(key) || tried.size >= maxProfileAttempts) return null;
    tried.add(key);
    try {
      const result = await resultForIdentity(identity, existingSession);
      results.push(result);
      return result;
    } catch (error) {
      lastError = error;
      return null;
    }
  };

  for (let accountIndex = 0; accountIndex < maxAccountIndices && tried.size < maxProfileAttempts; accountIndex += 1) {
    const identity = { accountIndex, pageId: '' };
    let yt;
    try {
      yt = await innertube(identity);
      accountIndicesTested += 1;
    } catch (error) {
      lastError = error;
      continue;
    }
    await tryIdentity(identity, yt);
    try {
      const accounts = await yt.account.getInfo(true);
      for (const account of accounts || []) {
        const pageId = delegatedPageIdFrom(account);
        if (!pageId) continue;
        discovered.push({ accountIndex, pageId });
      }
    } catch (error) {
      lastError = error;
    }
  }

  const uniqueDelegated = new Set();
  for (const identity of discovered) {
    const key = `${identity.accountIndex}:${identity.pageId}`;
    if (uniqueDelegated.has(key) || tried.size >= maxProfileAttempts) continue;
    uniqueDelegated.add(key);
    delegatedProfilesFound += 1;
    await tryIdentity(identity);
  }
  if (!results.length) throw lastError || new Error('No usable YouTube account profile was found');
  results.sort((a, b) => resultScore(b) - resultScore(a));
  const best = results[0];
  if (best.items.length) preferredIdentity = best.identity;
  return {
    ...best,
    diagnostics: {
      ...best.diagnostics,
      profilesTested: tried.size,
      accountIndicesTested,
      delegatedProfilesFound,
      selectedProfile: best.identity.pageId ? 'youtube_channel' : 'default_account'
    }
  };
}

async function continueWatching() {
  if (cache && Date.now() - cache.created < cacheMilliseconds) return cache.value;
  let result = null, configuredResult = null;
  const exactIdentity = await configuredIdentity();
  if (exactIdentity) {
    try {
      configuredResult = await resultForIdentity(exactIdentity);
      if (configuredResult.items.length) {
        preferredIdentity = exactIdentity;
        result = {
          ...configuredResult,
          diagnostics: {
            ...configuredResult.diagnostics,
            profilesTested: 1,
            accountIndicesTested: 1,
            delegatedProfilesFound: exactIdentity.pageId ? 1 : 0,
            selectedProfile: exactIdentity.pageId ? 'captured_youtube_channel' : 'captured_google_account',
            capturedIdentityUsed: true
          }
        };
      }
    } catch { /* fall back to automatic profile discovery */ }
  }
  if (!result && preferredIdentity) {
    try {
      const preferred = await resultForIdentity(preferredIdentity);
      if (preferred.items.length) {
        result = {
          ...preferred,
          diagnostics: {
            ...preferred.diagnostics,
            profilesTested: 1,
            accountIndicesTested: 1,
            delegatedProfilesFound: preferredIdentity.pageId ? 1 : 0,
            selectedProfile: preferredIdentity.pageId ? 'youtube_channel' : 'default_account'
          }
        };
      }
    } catch { /* fall back to a complete profile scan */ }
  }
  if (!result) {
    const discovered = await discoverProfileResults();
    const best = configuredResult && resultScore(configuredResult) > resultScore(discovered) ? configuredResult : discovered;
    result = {
      ...best,
      diagnostics: {
        ...best.diagnostics,
        capturedIdentityUsed: best === configuredResult
      }
    };
  }
  const value = {
    version: 1,
    generatedAt: Date.now(),
    items: result.items,
    diagnostics: result.diagnostics
  };
  cache = { created: Date.now(), value };
  return value;
}

function send(res, code, value) {
  const body = JSON.stringify(value);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer'
  });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', 'http://localhost');
    if (req.method === 'GET' && url.pathname === '/health') {
      let configured = false;
      try { configured = (await stat(cookiePath)).isFile() && (await stat(keyPath)).isFile(); } catch { /* not configured yet */ }
      return send(res, 200, { ok: true, service: 'quiet-home-youtube', configured });
    }
    if (req.method !== 'GET' || url.pathname !== '/api/continue-watching') return send(res, 404, { error: 'not_found' });
    let accepted = false;
    try { accepted = await authorized(req); } catch { return send(res, 503, { error: 'not_configured' }); }
    if (!accepted) return send(res, 401, { error: 'unauthorized' });
    return send(res, 200, await continueWatching());
  } catch (error) {
    console.error('[quiet-home] history fetch failed:', error instanceof Error ? error.message : String(error));
    return send(res, 502, { error: 'youtube_history_unavailable' });
  }
});

server.headersTimeout = 10_000;
server.requestTimeout = 12_000;
server.listen(port, '0.0.0.0', () => console.log(`[quiet-home] YouTube bridge listening on port ${port}`));
