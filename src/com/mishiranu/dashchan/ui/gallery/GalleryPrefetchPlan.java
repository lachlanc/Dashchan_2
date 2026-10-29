package com.mishiranu.dashchan.ui.gallery;

import com.mishiranu.dashchan.content.Preferences;
import java.util.ArrayList;
import java.util.List;

/**
 * Ordered, bounded selection of neighbouring attachments worth prefetching.
 *
 * <p>Ordinary horizontal browsing keeps the established forward-only order. TikTok paging also
 * looks backwards, because reversing is a single swipe away, and skips media that the active
 * filter cannot reach. This class only chooses indices; the caller applies the size limits,
 * duplicate checks and cache state.</p>
 */
final class GalleryPrefetchPlan {
	static final int FORWARD = 1;
	static final int BACKWARD = -1;

	/** Media facts for gallery positions, so a plan does not allocate a model object per item. */
	interface Source {
		int size();
		boolean isImage(int index);
		boolean isVideo(int index);
		boolean isOpenableVideo(int index);
	}

	static final class Slot {
		final int index;
		final boolean image;
		final int direction;

		Slot(int index, boolean image, int direction) {
			this.index = index;
			this.image = image;
			this.direction = direction;
		}
	}

	private GalleryPrefetchPlan() {}

	/**
	 * @param imageBudget how many upcoming images to include, 0 disables images
	 * @param videoBudget how many upcoming videos to include, 0 disables videos
	 * @param backwardBudget how many previous attachments TikTok paging may add per media kind
	 * @param preferredDirection the most recent swipe direction, used to interleave both sides
	 */
	static List<Slot> build(Source source, int currentIndex, boolean tikTok, int filter,
			int imageBudget, int videoBudget, int backwardBudget, int preferredDirection) {
		List<Slot> plan = new ArrayList<>();
		if (source == null || currentIndex < 0 || currentIndex >= source.size()) {
			return plan;
		}
		List<Slot> forward = collect(source, currentIndex, FORWARD, tikTok, filter, imageBudget, videoBudget);
		if (!tikTok) {
			plan.addAll(forward);
			return plan;
		}
		// The immediate reverse target matters most; keep the reverse window small and bounded by
		// the same per-kind budgets the user already chose.
		int backward = Math.max(0, Math.min(backwardBudget, Math.max(imageBudget, videoBudget)));
		List<Slot> reverse = backward == 0 ? new ArrayList<>()
				: collect(source, currentIndex, BACKWARD, true, filter,
						Math.min(backward, imageBudget), Math.min(backward, videoBudget));
		boolean reverseFirst = preferredDirection == BACKWARD;
		for (int i = 0; i < Math.max(forward.size(), reverse.size()); i++) {
			if (reverseFirst) {
				if (i < reverse.size()) plan.add(reverse.get(i));
				if (i < forward.size()) plan.add(forward.get(i));
			} else {
				if (i < forward.size()) plan.add(forward.get(i));
				if (i < reverse.size()) plan.add(reverse.get(i));
			}
		}
		return plan;
	}

	private static List<Slot> collect(Source source, int currentIndex, int step, boolean tikTok, int filter,
			int imageBudget, int videoBudget) {
		List<Slot> slots = new ArrayList<>();
		int images = Math.max(0, imageBudget);
		int videos = Math.max(0, videoBudget);
		for (int index = currentIndex + step; index >= 0 && index < source.size()
				&& (images > 0 || videos > 0); index += step) {
			boolean image = source.isImage(index);
			boolean video = source.isVideo(index);
			if (tikTok) {
				// Mirror the paging filter: media outside it is unreachable without leaving the mode.
				// A non-openable video is skipped by paging too, so it keeps its slot here.
				boolean matches = ((filter != Preferences.TIKTOK_FILTER_VIDEO) && image)
						|| ((filter != Preferences.TIKTOK_FILTER_IMAGE) && video
						&& source.isOpenableVideo(index));
				if (!matches) continue;
				if (image) {
					if (images <= 0) continue;
					images--;
				} else {
					if (videos <= 0) continue;
					videos--;
				}
				slots.add(new Slot(index, image, step));
				continue;
			}
			// Ordinary browsing keeps the historical accounting: an oversized, cached, duplicate or
			// non-openable video consumes its slot instead of being replaced by a farther item.
			if (image) {
				if (images <= 0) continue;
				images--;
			} else if (video) {
				if (videos <= 0) continue;
				videos--;
				if (!source.isOpenableVideo(index)) continue;
			} else continue;
			slots.add(new Slot(index, image, step));
		}
		return slots;
	}
}
