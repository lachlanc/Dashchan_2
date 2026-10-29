# TikTok mode prefetch

## Scope

Source-only change. No Gradle, compilation, unit-test execution, lint, APK build or
device validation was performed for it (the repository's default `SOURCE PATCH ONLY`
mode forbids all of those until the user switches modes).

The existing `MediaPreloader` remains the only owner of optional background network
traffic: one bounded, UI-thread-owned queue, one transfer at a time. This change does
not add workers, decoders or cache formats.

## What changed

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
  transfers are reordered toward the finger's direction. The transfer already in flight
  is not interrupted.
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

## Prepared tests (not run)

`GalleryPrefetchPlanTest` covers: ordinary forward-only order and slot accounting,
non-playable videos consuming a slot in ordinary mode but not in TikTok mode, the video
and photo filters, both budgets, reverse interleaving with either preferred direction, the
disabled reverse window, zero budgets, and both gallery ends.

Runtime behaviour is not proven by those fixtures.

## Device checks

1. Video-only TikTok mode, cache empty, moderate connection: open a long streaming video
   and swipe immediately. The next video should already be downloading; with the early
   setting off it should not start until the visible video finishes.
2. Swipe forward three or four times, then back. Reverse swipes over prefetched items
   should not show a progress loop, and the log should show one transition at a time.
3. Set "Previous attachments to prefetch" to 0 and repeat; only forward items are cached.
4. Photos-only and everything filters: no slot may be spent on media the filter cannot
   reach. A thread with a non-playable video before a playable one must still prefetch the
   playable one in TikTok mode.
5. Enable an extended diagnostics log and confirm that a page change does not restart an
   in-flight `MediaPreloader` transfer (no repeated `preload` start for the same URL).
6. Background the app, enter PiP, open the grid, change the network to a disallowed one:
   prefetch must stop as before, and must not resurrect itself while paused.
7. Low-RAM device or a video above the size limit: unchanged behaviour, no prefetch, no
   decode preparation.
