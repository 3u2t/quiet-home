const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

function walk(value, visit, seen = new WeakSet(), depth = 0) {
  if (!value || typeof value !== 'object' || depth > 24 || seen.has(value)) return;
  seen.add(value);
  visit(value, depth);
  if (Array.isArray(value)) {
    for (const child of value) walk(child, visit, seen, depth + 1);
  } else {
    for (const child of Object.values(value)) walk(child, visit, seen, depth + 1);
  }
}
function textFrom(value) {
  if (typeof value === 'string') return value.trim();
  if (typeof value === 'number') return String(value);
  if (!value || typeof value !== 'object') return '';
  if (typeof value.simpleText === 'string') return value.simpleText.trim();
  if (typeof value.content === 'string') return value.content.trim();
  if (typeof value.text === 'string') return value.text.trim();
  if (typeof value.attributedString === 'string') return value.attributedString.trim();
  if (Array.isArray(value.runs)) return value.runs.map((run) => textFrom(run)).filter(Boolean).join('').trim();
  return '';
}

function percentNumber(value) {
  if (typeof value === 'string') {
    const match = value.trim().match(/^(-?\d+(?:\.\d+)?)\s*%?$/);
    if (!match) return -1;
    value = match[1];
  }
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 && number <= 100 ? Math.round(number) : -1;
}

function firstNamedText(root, names) {
  let answer = '';
  walk(root, (node) => {
    if (answer || Array.isArray(node)) return;
    for (const name of names) {
      if (!Object.prototype.hasOwnProperty.call(node, name)) continue;
      const text = textFrom(node[name]);
      if (text) { answer = text; return; }
    }
  });
  return answer;
}

function progressFrom(root) {
  let answer = -1;
  walk(root, (node) => {
    if (answer >= 0 || Array.isArray(node)) return;
    for (const [key, value] of Object.entries(node)) {
      if (!/^(?:percentDurationWatched|percent_duration_watched|startPercent|start_percent|progressPercent|progress_percent|progressPercentage|progress_percentage)$/i.test(key)) continue;
      const number = percentNumber(value);
      if (number >= 0) { answer = number; return; }
    }
  });
  return answer;
}

function normalizeImageUrl(value) {
  if (typeof value !== 'string') return '';
  if (value.startsWith('//')) value = `https:${value}`;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' ? url.toString() : '';
  } catch { return ''; }
}

function thumbnailFrom(root) {
  const candidates = [];
  walk(root, (node, depth) => {
    if (Array.isArray(node)) return;
    const url = normalizeImageUrl(node.url);
    if (!url) return;
    const width = Number(node.width) || 0, height = Number(node.height) || 0;
    const ratio = height > 0 ? width / height : 0;
    const hostBonus = /(^|\.)ytimg\.com$/.test(new URL(url).hostname) ? 1e9 : 0;
    const landscapeBonus = ratio >= 1.25 && ratio <= 2.2 ? 5e8 : 0;
    candidates.push({ url, score: hostBonus + landscapeBonus + width * height - depth });
  });
  candidates.sort((a, b) => b.score - a.score);
  return candidates[0]?.url || '';
}

function parseClock(text) {
  if (typeof text !== 'string' || !/^\d{1,3}(?::\d{2}){1,2}$/.test(text.trim())) return 0;
  return text.trim().split(':').reduce((seconds, part) => seconds * 60 + Number(part), 0);
}

function durationFrom(root) {
  let answer = 0;
  walk(root, (node) => {
    if (answer || Array.isArray(node)) return;
    for (const value of [node.durationSeconds, node.duration_seconds, node.duration?.seconds]) {
      const seconds = Number(value);
      if (Number.isFinite(seconds) && seconds > 0 && seconds < 60 * 60 * 24 * 30) { answer = Math.round(seconds); return; }
    }
    for (const key of ['lengthText', 'durationText']) {
      const seconds = parseClock(textFrom(node[key]));
      if (seconds) { answer = seconds; return; }
    }
    for (const value of Object.values(node)) {
      const seconds = parseClock(textFrom(value));
      if (seconds) { answer = seconds; return; }
    }
  });
  return answer;
}

function rendererCandidates(raw) {
  const candidates = [];
  const added = new WeakSet();
  const add = (candidate) => {
    if (!candidate || typeof candidate !== 'object' || added.has(candidate)) return;
    added.add(candidate);
    candidates.push(candidate);
  };
  walk(raw, (node) => {
    if (Array.isArray(node)) return;
    if (videoIdFrom(node) && titleFrom(node)) add(node);
    for (const [key, value] of Object.entries(node)) {
      if (!value || typeof value !== 'object') continue;
      if (/(?:videoRenderer|compactVideoRenderer|gridVideoRenderer|videoWithContextRenderer|videoCardViewModel|lockupViewModel|shortsLockupViewModel)$/i.test(key)) add(value);
    }
  });
  return candidates;
}

function videoIdFrom(candidate) {
  for (const value of [candidate.videoId, candidate.video_id, candidate.contentId, candidate.content_id]) {
    if (typeof value === 'string' && VIDEO_ID.test(value)) return value;
  }
  return '';
}

function titleFrom(candidate) {
  return textFrom(candidate.title) || firstNamedText(candidate.metadata, ['title', 'headline']) || firstNamedText(candidate, ['title', 'headline']);
}

function channelFrom(candidate) {
  const author = firstNamedText(candidate.author, ['name', 'title']);
  return (author || firstNamedText(candidate, ['ownerText', 'longBylineText', 'shortBylineText', 'bylineText', 'ownerName', 'channelName'])).slice(0, 100);
}

export function findContinuationToken(raw) {
  let token = '';
  walk(raw, (node) => {
    const next = node?.continuationEndpoint?.continuationCommand?.token || node?.continuationCommand?.token;
    if (typeof next === 'string' && next.length > 20) token = next;
  });
  return token;
}

export function delegatedPageIdFrom(value) {
  let pageId = '';
  walk(value, (node) => {
    if (pageId || Array.isArray(node)) return;
    for (const [key, candidate] of Object.entries(node)) {
      if (!/^page_?id$/i.test(key) || typeof candidate !== 'string') continue;
      if (/^\d{10,30}$/.test(candidate)) { pageId = candidate; return; }
    }
  });
  return pageId;
}

export function extractHistoryItems(pages, options = {}) {
  const minimum = Number.isFinite(options.minimum) ? options.minimum : 2;
  const maximum = Number.isFinite(options.maximum) ? options.maximum : 95;
  const limit = Math.min(50, Math.max(1, Number(options.limit) || 18));
  const now = Number(options.now) || Date.now();
  const found = new Map();
  for (const page of pages) {
    for (const candidate of rendererCandidates(page)) {
      const videoId = videoIdFrom(candidate), title = titleFrom(candidate);
      const progressPercent = progressFrom(candidate);
      if (!videoId || !title || progressPercent < minimum || progressPercent >= maximum || found.has(videoId)) continue;
      const durationSeconds = durationFrom(candidate);
      const positionSeconds = durationSeconds ? Math.floor(durationSeconds * progressPercent / 100) : 0;
      const time = positionSeconds >= 5 ? `&t=${positionSeconds}s` : '';
      found.set(videoId, {
        videoId,
        title: title.slice(0, 200),
        channel: channelFrom(candidate),
        thumbnail: thumbnailFrom(candidate),
        progressPercent,
        durationSeconds,
        positionSeconds,
        url: `https://www.youtube.com/watch?v=${videoId}${time}`,
        seenAt: now - found.size
      });
      if (found.size >= limit) return [...found.values()];
    }
  }
  return [...found.values()];
}

export function analyzeHistoryPages(pages, options = {}) {
  const minimum = Number.isFinite(options.minimum) ? options.minimum : 2;
  const maximum = Number.isFinite(options.maximum) ? options.maximum : 95;
  let candidates = 0, withVideoId = 0, withTitle = 0, withProgress = 0, eligible = 0;
  const progressValues = new Set(), rendererTypes = new Map();
  for (const page of pages) {
    for (const candidate of rendererCandidates(page)) {
      candidates += 1;
      const type = String(candidate.type || candidate.contentType || candidate.content_type || 'unknown').slice(0, 50);
      rendererTypes.set(type, (rendererTypes.get(type) || 0) + 1);
      if (videoIdFrom(candidate)) withVideoId += 1;
      if (titleFrom(candidate)) withTitle += 1;
      const progress = progressFrom(candidate);
      if (progress >= 0) {
        withProgress += 1;
        if (progressValues.size < 12) progressValues.add(progress);
        if (progress >= minimum && progress < maximum) eligible += 1;
      }
    }
  }
  const status = candidates === 0 ? 'no_history_videos'
    : withProgress === 0 ? 'no_resume_progress'
      : eligible === 0 ? 'no_eligible_progress'
        : 'ok';
  return {
    version: 1,
    status,
    pages: pages.length,
    candidates,
    withVideoId,
    withTitle,
    withProgress,
    eligible,
    progressValues: [...progressValues].sort((a, b) => a - b),
    rendererTypes: Object.fromEntries([...rendererTypes.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8))
  };
}
