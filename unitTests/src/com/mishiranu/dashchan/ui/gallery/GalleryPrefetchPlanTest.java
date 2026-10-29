package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class GalleryPrefetchPlanTest {
	private static final int ALL = 0, VIDEO = 1, IMAGE = 2;

	/** 'i' image, 'v' openable video, 'x' non-openable video, 'o' any other file. */
	private static GalleryPrefetchPlan.Source gallery(String pattern) {
		return new GalleryPrefetchPlan.Source() {
			@Override
			public int size() {
				return pattern.length();
			}

			@Override
			public boolean isImage(int index) {
				return pattern.charAt(index) == 'i';
			}

			@Override
			public boolean isVideo(int index) {
				char value = pattern.charAt(index);
				return value == 'v' || value == 'x';
			}

			@Override
			public boolean isOpenableVideo(int index) {
				return pattern.charAt(index) == 'v';
			}
		};
	}

	private static List<Integer> indices(List<GalleryPrefetchPlan.Slot> plan) {
		List<Integer> indices = new ArrayList<>(plan.size());
		for (GalleryPrefetchPlan.Slot slot : plan) indices.add(slot.index);
		return indices;
	}

	private static List<Integer> directions(List<GalleryPrefetchPlan.Slot> plan) {
		List<Integer> directions = new ArrayList<>(plan.size());
		for (GalleryPrefetchPlan.Slot slot : plan) directions.add(slot.direction);
		return directions;
	}

	@Test public void ordinaryBrowsingStaysForwardOnlyWithHistoricalSlotAccounting() {
		// A non-openable video consumes its slot instead of pulling a farther video in.
		assertEquals(Arrays.asList(2), indices(GalleryPrefetchPlan.build(gallery("ixv"), 0, false,
				0, 0, 2, 0, GalleryPrefetchPlan.FORWARD)));
		assertTrue(GalleryPrefetchPlan.build(gallery("ixv"), 0, false,
				0, 0, 1, 0, GalleryPrefetchPlan.FORWARD).isEmpty());
		assertEquals(Arrays.asList(1, 2), indices(GalleryPrefetchPlan.build(gallery("ioi"), 0, false,
				0, 2, 0, 0, GalleryPrefetchPlan.FORWARD)));
	}

	@Test public void ordinaryBrowsingIgnoresTheTikTokFilterAndTheReverseBudget() {
		List<GalleryPrefetchPlan.Slot> plan = GalleryPrefetchPlan.build(gallery("iviv"), 0, false,
				0, 1, 1, 2, GalleryPrefetchPlan.BACKWARD);
		assertEquals(Arrays.asList(1, 2), indices(plan));
		assertEquals(Arrays.asList(GalleryPrefetchPlan.FORWARD, GalleryPrefetchPlan.FORWARD), directions(plan));
	}

	@Test public void videoFilterSkipsImagesWithoutSpendingTheVideoBudget() {
		List<GalleryPrefetchPlan.Slot> plan = GalleryPrefetchPlan.build(gallery("iviiviiv"), 0, true,
				VIDEO, 3, 2, 0, GalleryPrefetchPlan.FORWARD);
		assertEquals(Arrays.asList(1, 4), indices(plan));
		for (GalleryPrefetchPlan.Slot slot : plan) assertFalse(slot.image);
	}

	@Test public void imageFilterSkipsVideosAndNonOpenableVideosWithoutSpendingTheImageBudget() {
		List<GalleryPrefetchPlan.Slot> plan = GalleryPrefetchPlan.build(gallery("ivxioi"), 0, true,
				IMAGE, 2, 3, 0, GalleryPrefetchPlan.FORWARD);
		assertEquals(Arrays.asList(3, 5), indices(plan));
		for (GalleryPrefetchPlan.Slot slot : plan) assertTrue(slot.image);
	}

	@Test public void videoFilterDoesNotSpendAVideoSlotOnANonOpenableVideo() {
		// Paging skips it too, so a prefetch slot must move on to the next playable video.
		assertEquals(Arrays.asList(2), indices(GalleryPrefetchPlan.build(gallery("vxv"), 0, true,
				VIDEO, 0, 1, 0, GalleryPrefetchPlan.FORWARD)));
	}

	@Test public void allFilterContainsBothKindsWithinTheirOwnBudgets() {
		List<GalleryPrefetchPlan.Slot> plan = GalleryPrefetchPlan.build(gallery("viiov"), 0, true,
				ALL, 1, 1, 0, GalleryPrefetchPlan.FORWARD);
		assertEquals(Arrays.asList(1, 4), indices(plan));
		assertTrue(plan.get(0).image);
		assertFalse(plan.get(1).image);
	}

	@Test public void reverseWindowAddsThePreviousItemsAndKeepsTheirDirection() {
		List<GalleryPrefetchPlan.Slot> plan = GalleryPrefetchPlan.build(gallery("iiviviv"), 2, true,
				ALL, 1, 2, 1, GalleryPrefetchPlan.FORWARD);
		assertEquals(Arrays.asList(3, 1, 4, 5), indices(plan));
		assertEquals(Arrays.asList(GalleryPrefetchPlan.FORWARD, GalleryPrefetchPlan.BACKWARD,
				GalleryPrefetchPlan.FORWARD, GalleryPrefetchPlan.FORWARD), directions(plan));
	}

	@Test public void reverseFirstOrderFollowsTheLastSwipe() {
		List<GalleryPrefetchPlan.Slot> plan = GalleryPrefetchPlan.build(gallery("vvvvv"), 2, true,
				ALL, 0, 2, 1, GalleryPrefetchPlan.BACKWARD);
		assertEquals(Arrays.asList(1, 3, 4), indices(plan));
	}

	@Test public void disabledReverseWindowAndDisabledBudgetsReturnNothing() {
		assertEquals(Arrays.asList(3, 4), indices(GalleryPrefetchPlan.build(gallery("vvvvv"), 2, true,
				ALL, 0, 2, 0, GalleryPrefetchPlan.FORWARD)));
		assertTrue(GalleryPrefetchPlan.build(gallery("vvvvv"), 2, true, ALL,
				0, 0, 2, GalleryPrefetchPlan.FORWARD).isEmpty());
	}

	@Test public void planNeverLeavesTheGalleryAtEitherEnd() {
		assertEquals(Arrays.asList(1, 2), indices(GalleryPrefetchPlan.build(gallery("vvv"), 0, true,
				ALL, 0, 2, 2, GalleryPrefetchPlan.BACKWARD)));
		assertEquals(Arrays.asList(1, 0), indices(GalleryPrefetchPlan.build(gallery("vvv"), 2, true,
				ALL, 0, 2, 2, GalleryPrefetchPlan.BACKWARD)));
		assertTrue(GalleryPrefetchPlan.build(gallery("vvv"), 3, true, ALL,
				3, 3, 2, GalleryPrefetchPlan.FORWARD).isEmpty());
	}

	@Test public void otherFilesConsumeNoBudget() {
		assertEquals(Arrays.asList(2, 3, 4), indices(GalleryPrefetchPlan.build(gallery("ioivi"), 0, false,
				0, 2, 2, 0, GalleryPrefetchPlan.FORWARD)));
	}
}
