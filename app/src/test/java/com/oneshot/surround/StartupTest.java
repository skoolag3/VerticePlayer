package com.oneshot.surround;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StartupTest {
    @Test public void switchesBetweenAllRootTabsAndStudioShortcuts() {
        try (org.robolectric.android.controller.ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class)) {
            activity.setup().visible();
            MainActivity host = activity.get();
            com.google.android.material.bottomnavigation.BottomNavigationView navigation = findNavigation(host.getWindow().getDecorView());
            org.junit.Assert.assertNotNull(navigation);
            for (int id : new int[]{2, 3, 4, 1, 4, 2, 1}) {
                navigation.setSelectedItemId(id);
                org.junit.Assert.assertEquals(id - 1, host.activeRootTab);
                org.junit.Assert.assertEquals(id, navigation.getSelectedItemId());
            }
            for (Runnable shortcut : host.studioTabSelectors) {
                navigation.setSelectedItemId(1);
                shortcut.run();
                org.junit.Assert.assertEquals(2, host.activeRootTab);
                org.junit.Assert.assertEquals(3, navigation.getSelectedItemId());
            }
        }
    }

    @Test public void alphabeticalNavigationStaysInsideLibrary() {
        try (org.robolectric.android.controller.ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class)) {
            activity.setup().visible();
            MainActivity host = activity.get();
            for (int i = 0; i < 20; i++) {
                host.queue.add(new TrackInfo(android.net.Uri.parse("content://test/" + i), (char) ('A' + i) + ".mp3"));
            }
            findNavigation(host.getWindow().getDecorView()).setSelectedItemId(2);
            org.junit.Assert.assertTrue(host.alphabetIndexView.isShown());
            host.librarySearch.setText("missing-title");
            org.junit.Assert.assertFalse(host.libraryHighlights.isShown());
            host.librarySearch.setText("");
            findNavigation(host.getWindow().getDecorView()).setSelectedItemId(3);
            host.renderQueue();
            org.junit.Assert.assertFalse(host.alphabetIndexView.isShown());
        }
    }

    @Test public void albumUsesMostFrequentCoverAcrossSeparateTrackImages() {
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        java.util.List<TrackInfo> tracks = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TrackInfo track = new TrackInfo(android.net.Uri.parse("content://covers/" + i), "track");
            track.artwork = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888);
            track.artwork.eraseColor(i == 0 ? android.graphics.Color.RED : android.graphics.Color.BLUE);
            tracks.add(track);
        }
        android.graphics.Bitmap[] chosen = new android.graphics.Bitmap[1];
        new AlbumCoverIndex().load(host, tracks, cover -> chosen[0] = cover);
        org.junit.Assert.assertSame(tracks.get(1).artwork, chosen[0]);
    }

    private com.google.android.material.bottomnavigation.BottomNavigationView findNavigation(android.view.View view) {
        if (view instanceof com.google.android.material.bottomnavigation.BottomNavigationView)
            return (com.google.android.material.bottomnavigation.BottomNavigationView)view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                com.google.android.material.bottomnavigation.BottomNavigationView result = findNavigation(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }
    @Test public void rendersOrbitAndSavedVisualizerModesWithAndWithoutArtwork() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        ArtworkView view = new ArtworkView(context);
        view.setAnalyzer(new AudioReactiveAnalyzer());
        view.layout(0, 0, 360, 320);
        android.graphics.Bitmap surface = android.graphics.Bitmap.createBitmap(360, 320, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(surface);
        for (boolean hasCover : new boolean[]{false, true}) {
            view.setCover(hasCover ? android.graphics.Bitmap.createBitmap(120, 80, android.graphics.Bitmap.Config.ARGB_8888) : null);
            view.setPlaying(hasCover);
            for (int mode = 0; mode < AppearanceSettings.DISPLAY_MODES.length; mode++) {
                view.setAppearance(mode, true, true, true);
                view.draw(canvas);
            }
        }
    }

    @Test public void opensPlayerWithMaterialComponents() {
        try (org.robolectric.android.controller.ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class)) {
            activity.setup().visible();
            org.junit.Assert.assertNotNull(activity.get().artwork);
            org.junit.Assert.assertTrue(activity.get().playButton instanceof com.google.android.material.button.MaterialButton);
        }
    }
}
