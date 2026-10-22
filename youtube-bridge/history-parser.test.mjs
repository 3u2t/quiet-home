import test from 'node:test';
import assert from 'node:assert/strict';
import { analyzeHistoryPages, delegatedPageIdFrom, extractHistoryItems, findContinuationToken } from './history-parser.mjs';

const page = {
  contents: [{
    videoRenderer: {
      videoId: 'abcDEF123_-',
      title: { runs: [{ text: 'Halb gesehen' }] },
      ownerText: { runs: [{ text: 'Beispielkanal' }] },
      thumbnail: { thumbnails: [
        { url: 'https://yt3.ggpht.com/avatar', width: 88, height: 88 },
        { url: 'https://i.ytimg.com/vi/abcDEF123_-/hqdefault.jpg', width: 480, height: 360 }
      ] },
      lengthText: { simpleText: '10:00' },
      thumbnailOverlays: [{ thumbnailOverlayResumePlaybackRenderer: { percentDurationWatched: 42 } }]
    }
  }, {
    videoRenderer: {
      videoId: 'finished123', title: { simpleText: 'Fertig' },
      thumbnailOverlays: [{ thumbnailOverlayResumePlaybackRenderer: { percentDurationWatched: 99 } }]
    }
  }],
  continuationEndpoint: { continuationCommand: { token: 'continuation-token-long-enough-123456' } }
};

test('keeps only unfinished videos and builds a resume URL', () => {
  const items = extractHistoryItems([page], { now: 1000, limit: 10 });
  assert.equal(items.length, 1);
  assert.equal(items[0].videoId, 'abcDEF123_-');
  assert.equal(items[0].title, 'Halb gesehen');
  assert.equal(items[0].channel, 'Beispielkanal');
  assert.equal(items[0].progressPercent, 42);
  assert.equal(items[0].positionSeconds, 252);
  assert.equal(items[0].url, 'https://www.youtube.com/watch?v=abcDEF123_-&t=252s');
  assert.match(items[0].thumbnail, /i\.ytimg\.com/);
});
test('supports the newer lockup response shape and de-duplicates videos', () => {
  const lockup = { lockupViewModel: {
    contentId: 'ZXcvbnM1234', contentType: 'LOCKUP_CONTENT_TYPE_VIDEO',
    metadata: { lockupMetadataViewModel: { title: { content: 'Neues Layout' } } },
    contentImage: { thumbnailViewModel: { image: { sources: [{ url: 'https://i.ytimg.com/vi/ZXcvbnM1234/maxresdefault.jpg', width: 1280, height: 720 }] }, overlays: [{ progress: { percent_duration_watched: 17 } }] } }
  } };
  const items = extractHistoryItems([[lockup, lockup]], { limit: 10 });
  assert.equal(items.length, 1);
  assert.equal(items[0].title, 'Neues Layout');
  assert.equal(items[0].progressPercent, 17);
});

test('supports the current lockup progress-bar startPercent shape', () => {
  const lockup = { lockupViewModel: {
    contentId: 'newShape123', contentType: 'LOCKUP_CONTENT_TYPE_VIDEO',
    metadata: { lockupMetadataViewModel: { title: { content: 'Aktueller Fortschritt' } } },
    contentImage: { thumbnailViewModel: {
      image: { sources: [{ url: 'https://i.ytimg.com/vi/newShape123/hqdefault.jpg', width: 480, height: 360 }] },
      overlays: [{ thumbnailBottomOverlayViewModel: {
        progressBar: { thumbnailOverlayProgressBarViewModel: { startPercent: 37 } }
      } }]
    } }
  } };
  const items = extractHistoryItems([[lockup]], { limit: 10 });
  assert.equal(items.length, 1);
  assert.equal(items[0].videoId, 'newShape123');
  assert.equal(items[0].progressPercent, 37);
});

test('supports parsed history video nodes and percent strings', () => {
  const parsed = [{
    type: 'Video', video_id: 'parsed12345', title: { text: 'Direkt geparst' },
    author: { name: 'Kanal' }, duration: { seconds: 900 },
    thumbnails: [{ url: 'https://i.ytimg.com/vi/parsed12345/hqdefault.jpg', width: 480, height: 360 }],
    thumbnail_overlays: [{ progressPercentage: '25%' }]
  }];
  const items = extractHistoryItems([parsed], { limit: 10 });
  assert.equal(items.length, 1);
  assert.equal(items[0].videoId, 'parsed12345');
  assert.equal(items[0].channel, 'Kanal');
  assert.equal(items[0].durationSeconds, 900);
  assert.equal(items[0].positionSeconds, 225);
});

test('returns privacy-safe diagnostics when history has no resume bars', () => {
  const diagnostics = analyzeHistoryPages([[{
    videoCardViewModel: { videoId: 'diagNOprog1', title: { content: 'Private title is not returned' } }
  }]]);
  assert.equal(diagnostics.status, 'no_resume_progress');
  assert.equal(diagnostics.candidates, 1);
  assert.equal(diagnostics.withProgress, 0);
  assert.equal(JSON.stringify(diagnostics).includes('Private title'), false);
});

test('extracts a feed continuation token', () => {
  assert.equal(findContinuationToken(page), 'continuation-token-long-enough-123456');
});

test('extracts only a numeric delegated YouTube page id', () => {
  const account = { endpoint: { payload: { supportedTokens: [
    { pageIdToken: { pageId: '123456789012345678901' } }
  ] } } };
  assert.equal(delegatedPageIdFrom(account), '123456789012345678901');
  assert.equal(delegatedPageIdFrom({ pageId: 'UCnot-a-delegated-id' }), '');
});
