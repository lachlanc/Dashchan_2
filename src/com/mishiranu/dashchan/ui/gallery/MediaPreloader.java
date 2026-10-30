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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Bounded, UI-thread-owned prefetch queue for the visible gallery, never for the background/PiP player.
 * At most {@link #MAX_CONCURRENT} neighbours are transferred at once so the next and the previous swipe
 * both have their file ready; the visible player's own download keeps priority by staying outside it.
 */
final class MediaPreloader {
	private static final int MAX_CONCURRENT = 2;
	private static final Executor EXECUTOR = ConcurrentUtils.newIdleThreadPool(MAX_CONCURRENT, 20000,
			"MediaPreloader", null);
	private final GalleryInstance gallery;
	private final ConnectivityManager connectivity;
	private final ArrayDeque<Entry> queue = new ArrayDeque<>();
	private final HashMap<Uri, Running> running = new HashMap<>();
	private ConnectivityManager.NetworkCallback networkCallback;
	private boolean resumed;
	private boolean tikTokMode;
	private int tikTokFilter = Preferences.getTikTokMediaFilter();
	private int preferredDirection = GalleryPrefetchPlan.FORWARD;
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

	/** One transfer in flight; identity is what tells a late callback from a cancelled one. */
	private static final class Running {
		final Entry entry;
		PreloadMediaTask task;

		Running(Entry entry) {
			this.entry = entry;
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
		// current item immediately afterwards; keep only the active transfers until then.
		queue.clear();
	}

	/** Reorders pending transfers toward the swipe direction without interrupting the active ones. */
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
		cancelAll();
		queue.clear();
		unregisterNetworkCallback();
	}

	private void cancelAll() {
		for (Running active : running.values()) {
			active.task.cancel();
		}
		running.clear();
	}

	private void unregisterNetworkCallback() {
		if (networkCallback != null) {
			try {
				connectivity.unregisterNetworkCallback(networkCallback);
			} catch (IllegalArgumentException ignored) {
				// It may already have been unregistered by the system.
			}
			networkCallback = null;
		}
	}

	void start(GalleryItem current) {
		if (!resumed || current == null || gallery.callback.isGalleryMode() || connectivity == null) {
			stop();
			return;
		}
		List<Entry> wanted = buildPlan(current);
		HashSet<Uri> wantedUris = new HashSet<>(wanted.size());
		for (Entry entry : wanted) wantedUris.add(entry.uri);
		// Re-planning after every swipe keeps every transfer that is still wanted, so rapid
		// swiping never restarts the downloads it depends on.
		Iterator<Map.Entry<Uri, Running>> iterator = running.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Uri, Running> active = iterator.next();
			if (!wantedUris.contains(active.getKey())) {
				active.getValue().task.cancel();
				iterator.remove();
			}
		}
		queue.clear();
		for (Entry entry : wanted) {
			if (!running.containsKey(entry.uri)) queue.add(entry);
		}
		if (queue.isEmpty() && running.isEmpty()) {
			unregisterNetworkCallback();
			return;
		}
		registerNetworkCallback();
		if (networkCallback != null) pump();
	}

	private void registerNetworkCallback() {
		if (networkCallback != null) return;
		networkCallback = new ConnectivityManager.NetworkCallback() {
			private void check() {
				if (networkCallback != this) return;
				ArrayList<Running> disallowed = new ArrayList<>(running.size());
				for (Running active : running.values()) {
					if (!allowed(active.entry)) disallowed.add(active);
				}
				for (Running active : disallowed) {
					if (running.remove(active.entry.uri) != null) active.task.cancel();
				}
				if (!disallowed.isEmpty()) {
					pump();
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
		}
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

	/** Fills the free transfer slots in queue order; the queue is already direction-prioritised. */
	private void pump() {
		Entry entry;
		while (running.size() < MAX_CONCURRENT && (entry = queue.poll()) != null) {
			if (!allowed(entry) || running.containsKey(entry.uri)) continue;
			startTransfer(entry);
		}
		if (queue.isEmpty() && running.isEmpty()) {
			// Nothing left to watch for: the next page change or resume re-registers.
			unregisterNetworkCallback();
		}
	}

	private void startTransfer(Entry entry) {
		Running active = new Running(entry);
		active.task = new PreloadMediaTask(Chan.getPreferred(gallery.chanName, entry.uri), entry.uri,
				entry.maxBytes, connectivity, entry.network, () -> onTransferFinished(active));
		running.put(entry.uri, active);
		active.task.execute(EXECUTOR);
	}

	private void onTransferFinished(Running active) {
		// A cancelled or replaced transfer has already left the map and must not resume the queue.
		if (running.get(active.entry.uri) != active) return;
		running.remove(active.entry.uri);
		if (!active.entry.image && onVideoCached != null) onVideoCached.run();
		pump();
	}
}
