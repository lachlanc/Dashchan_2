# TikTok mode prefetch

## Scope and verification

`FAST LOCAL TEST` / `PUBLIC RELEASE` were run on scratchboxjr for this change:
`assembleGithubNdebug`, the full `testGithubDebugUnitTest` suite (737 tests, including
`GalleryPrefetchPlanTest`) and `assembleGithubRelease`, whose signed APK was published to
the private F-Droid repository as `3.2.72-f1` (versionCode 11532) and verified by hash.
No device was attached, so the behaviour below is not device-validated.

The existing `MediaPreloader` remains the only owner of optional background network
traffic: one bounded, UI-thread-owned queue. It now keeps at most two transfers in
flight, so the next and the previous attachment can be cached at the same time. This
change does not add decoders or cache formats.

## What changed

- **Concurrent transfers.** The queue runs up to two downloads at once on its own
  idle-timed pool (`newIdleThreadPool(2, …)`), separate from `PARALLEL_EXECUTOR` so the
  visible player's own download keeps priority. In-flight transfers are tracked by URI;
  re-planning keeps every one that is still wanted and cancels only the rest. A swipe
  therefore finds both neighbours already cached instead of waiting for the reverse item
  to queue behind the forward budget.
- **Filter-aware plan.** `GalleryPrefetchPlan` chooses the neighbouring attachments.
  Ordinary horizontal browsing keeps the previous behaviour exactly: forward only, and a
  cached, oversized, duplicate or non-playable video still consumes its slot instead of
  pulling a farther item in. TikTok paging instead mirrors its own filter: with
  "videos only" no image slot is spent, with "photos only" no video slot is spent, and a
  non-playable video does not consume a slot because paging skips it too.
- **Reverse window.** TikTok paging prefetches the attachments behind the current one as
  well, so swiping back is not a cold load. The new "Previous attachments to prefetch"
  setting is a count from 0 to 2 (default 1); 0 restores forward-only prefetching. The
  per-kind budgets ("Number of next photos"/"next videos") still cap each direction.
- **Direction priority.** When a vertical TikTok gesture starts moving, the queued
  transfers are reordered toward the finger's direction. Transfers already in flight are
  not interrupted.
- **No restart on re-plan.** Every page change recomputes the plan, but a transfer that is
  still in the new plan keeps running. Previously a page change cancelled the preloader's
  active task, so repeatedly swiping restarted the same upcoming download.
- **Early start.** In TikTok mode, prefetching may begin while the visible video is still
  streaming instead of waiting for its file to finish. Controlled by the new "Prefetch
  while the current video loads" setting (default on). It shares bandwidth with the
  visible stream, which is the trade-off the setting documents.
- **Chunked responses.** `PreloadMediaTask` now also caches a response that has no
  `Content-Length`, accepting a natural end of stream strictly below the byte cap.
  Reaching the cap is treated as ambiguous with a truncated body and is never accepted.
  Known lengths behave exactly as before.

Not changed: `NextVideoPreparation` still prepares at most one speculative decoder and
only for the forward neighbour, `VideoPageFrames` retention, the `.part`-file ownership of
the foreground player, size/network limits, and the low-RAM policy.

## Tests

`GalleryPrefetchPlanTest` passes and covers: ordinary forward-only order and slot
accounting, non-playable videos consuming a slot in ordinary mode but not in TikTok mode,
the video and photo filters, both budgets, reverse interleaving with either preferred
direction, the disabled reverse window, zero budgets, and both gallery ends.

Those fixtures do not prove device rendering, network behaviour or bandwidth sharing.

## Device checks

1. Video-only TikTok mode, cache empty, moderate connection: open a long streaming video
   and swipe immediately. The next video should already be downloading; with the early
   setting off it should not start until the visible video finishes.
2. Swipe forward three or four times, then back. Reverse swipes over prefetched items
   should not show a progress loop, and the log should show one transition at a time.
3. Watch the first seconds of a long video with both neighbours uncached: two background
   downloads run alongside the visible stream. If the visible video stutters, the
   concurrency (not the size limits) is what to tune down.
4. Set "Previous attachments to prefetch" to 0 and repeat; only forward items are cached.
5. Photos-only and everything filters: no slot may be spent on media the filter cannot
   reach. A thread with a non-playable video before a playable one must still prefetch the
   playable one in TikTok mode.
6. Enable an extended diagnostics log and confirm that a page change does not restart an
   in-flight `MediaPreloader` transfer (no repeated `preload` start for the same URL).
7. Background the app, enter PiP, open the grid, change the network to a disallowed one:
   prefetch must stop as before, and must not resurrect itself while paused.
8. Low-RAM device or a video above the size limit: unchanged behaviour, no prefetch, no
   decode preparation.
