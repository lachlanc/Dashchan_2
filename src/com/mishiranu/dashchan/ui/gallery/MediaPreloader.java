package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import androidx.annotation.NonNull;
import chan.content.Chan;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.PreloadMediaTask;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;

/** One bounded, UI-thread-owned queue for the visible gallery, never for the background/PiP player. */
final class MediaPreloader {
	private static final Executor EXECUTOR = ConcurrentUtils.newSingleThreadPool(20000, "MediaPreloader", null);
	private final GalleryInstance gallery;
	private final ConnectivityManager connectivity;
	private final ArrayDeque<Entry> queue = new ArrayDeque<>();
	private PreloadMediaTask task;
	private Entry currentEntry;
	private ConnectivityManager.NetworkCallback networkCallback;
	private boolean resumed;
	private boolean tikTokMode;
	private int tikTokFilter = Preferences.getTikTokMediaFilter();
	private int preferredDirection = GalleryPrefetchPlan.FORWARD;
	private int generation;
	private Runnable onVideoCached;

	private static final class Entry {
		final Uri uri;
		final boolean image;
		final long maxBytes;
		final String network;
		final int direction;

		Entry(Uri uri, boolean image, long maxBytes, String network, int direction) {
			this.uri = uri;
			this.image = image;
			this.maxBytes = maxBytes;
			this.network = network;
			this.direction = direction;
		}
	}

	private static final class GallerySource implements GalleryPrefetchPlan.Source {
		private final List<GalleryItem> items;
		private final Chan chan;

		GallerySource(List<GalleryItem> items, Chan chan) {
			this.items = items;
			this.chan = chan;
		}

		@Override
		public int size() {
			return items.size();
		}

		@Override
		public boolean isImage(int index) {
			return items.get(index).isImage(chan);
		}

		@Override
		public boolean isVideo(int index) {
			return items.get(index).isVideo(chan);
		}

		@Override
		public boolean isOpenableVideo(int index) {
			return items.get(index).isOpenableVideo(chan);
		}
	}

	MediaPreloader(GalleryInstance gallery) {
		this.gallery = gallery;
		connectivity = (ConnectivityManager) gallery.context.getApplicationContext()
				.getSystemService(Context.CONNECTIVITY_SERVICE);
	}

	void setResumed(boolean resumed) {
		this.resumed = resumed;
		if (!resumed) stop();
	}

	void setOnVideoCached(Runnable callback) { onVideoCached = callback; }

	/**
	 * Paging mode is remembered here so every existing start() caller (image and video units)
	 * builds the same plan without knowing about TikTok paging.
	 */
	void setPagingMode(boolean tikTokMode, int tikTokFilter) {
		if (this.tikTokMode == tikTokMode && this.tikTokFilter == tikTokFilter) return;
		this.tikTokMode = tikTokMode;
		this.tikTokFilter = tikTokFilter;
		// The pending order was chosen for the previous filter. The caller restarts from the
		// current item immediately afterwards; keep only the active transfer until then.
		queue.clear();
	}

	/** Reorders pending transfers toward the swipe direction without interrupting the active one. */
	void setDirection(int direction) {
		if (direction != GalleryPrefetchPlan.FORWARD && direction != GalleryPrefetchPlan.BACKWARD) return;
		if (preferredDirection == direction) return;
		preferredDirection = direction;
		if (queue.size() < 2) return;
		ArrayDeque<Entry> ordered = new ArrayDeque<>(queue.size());
		for (Entry entry : queue) {
			if (entry.direction == preferredDirection) ordered.add(entry);
		}
		for (Entry entry : queue) {
			if (entry.direction != preferredDirection) ordered.add(entry);
		}
		queue.clear();
		queue.addAll(ordered);
	}

	void stop() {
		cancelActive();
		queue.clear();
		if (networkCallback != null) {
			try {
				connectivity.unregisterNetworkCallback(networkCallback);
			} catch (IllegalArgumentException ignored) {
				// It may already have been unregistered by the system.
			}
			networkCallback = null;
		}
	}

	private void cancelActive() {
		generation++;
		currentEntry = null;
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	void start(GalleryItem current) {
		if (!resumed || current == null || gallery.callback.isGalleryMode() || connectivity == null) {
			stop();
			return;
		}
		List<Entry> wanted = buildPlan(current);
		HashSet<Uri> wantedUris = new HashSet<>();
		for (Entry entry : wanted) wantedUris.add(entry.uri);
		// Keep a transfer that is still wanted: re-planning after every swipe must not restart
		// the very download the next swipe depends on.
		if (currentEntry != null && !wantedUris.contains(currentEntry.uri)) {
			cancelActive();
		}
		queue.clear();
		for (Entry entry : wanted) {
			if (currentEntry == null || !entry.uri.equals(currentEntry.uri)) queue.add(entry);
		}
		if (queue.isEmpty() && currentEntry == null) {
			stop();
			return;
		}
		if (networkCallback == null) {
			networkCallback = new ConnectivityManager.NetworkCallback() {
				private void check() {
					if (networkCallback == this && currentEntry != null && !allowed(currentEntry)) {
						cancelActive();
						next();
					}
				}
				@Override
				public void onCapabilitiesChanged(@NonNull Network network,
						@NonNull NetworkCapabilities capabilities) {
					check();
				}
				@Override
				public void onLost(@NonNull Network network) {
					check();
				}
			};
			try {
				connectivity.registerDefaultNetworkCallback(networkCallback, ConcurrentUtils.HANDLER);
			} catch (SecurityException | IllegalArgumentException e) {
				networkCallback = null;
				queue.clear();
				return;
			}
		}
		if (task == null) next();
	}

	private List<Entry> buildPlan(GalleryItem current) {
		List<GalleryItem> items = gallery.galleryItems;
		int index = items.indexOf(current);
		if (index < 0) return new ArrayList<>(0);
		Chan chan = Chan.get(gallery.chanName);
		int images = Preferences.isImagePreload() ? Preferences.getImagePreloadCount() : 0;
		int videos = Preferences.isVideoPreload() && Preferences.isUseVideoPlayer()
				? Preferences.getVideoPreloadCount() : 0;
		int backward = tikTokMode ? Preferences.getTikTokPrefetchBackwardCount() : 0;
		List<GalleryPrefetchPlan.Slot> slots = GalleryPrefetchPlan.build(new GallerySource(items, chan),
				index, tikTokMode, tikTokFilter, images, videos, backward, preferredDirection);
		HashSet<Uri> seen = new HashSet<>();
		seen.add(current.getFileUri(chan));
		ArrayList<Entry> entries = new ArrayList<>(slots.size());
		for (GalleryPrefetchPlan.Slot slot : slots) {
			GalleryItem item = items.get(slot.index);
			long maxBytes = slot.image ? Preferences.getImagePreloadMaxBytes() : Preferences.getVideoPreloadMaxBytes();
			// Unknown sizes pass this check; PreloadMediaTask applies the byte cap to the response.
			if (item.size > maxBytes) continue;
			String network = slot.image ? Preferences.getImagePreloadNetwork()
					: Preferences.isVideoPreloadWifiOnly() ? "wifi" : "all";
			Uri uri = item.getFileUri(chan);
			if (uri == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
					|| !seen.add(uri)) continue;
			File cached = CacheManager.getInstance().getMediaFile(uri, false);
			if (cached != null && !cached.exists()) {
				entries.add(new Entry(uri, slot.image, maxBytes, network, slot.direction));
			}
		}
		return entries;
	}

	private boolean allowed(Entry entry) {
		return resumed && !gallery.callback.isGalleryMode()
				&& (entry.image ? Preferences.isImagePreload() : Preferences.isVideoPreload() && Preferences.isUseVideoPlayer())
				&& PreloadMediaTask.isNetworkAllowed(connectivity, entry.network);
	}

	private void next() {
		Entry entry;
		while ((entry = queue.poll()) != null) {
			if (!allowed(entry)) continue;
			currentEntry = entry;
			int expectedGeneration = generation;
			Entry expectedEntry = entry;
			task = new PreloadMediaTask(Chan.getPreferred(gallery.chanName, entry.uri), entry.uri, entry.maxBytes,
					connectivity, entry.network, () -> {
						if (generation != expectedGeneration || currentEntry != expectedEntry) return;
						task = null;
						currentEntry = null;
						if (!expectedEntry.image && onVideoCached != null) onVideoCached.run();
						next();
					});
			task.execute(EXECUTOR);
			return;
		}
		stop();
	}
}
