package com.oneshot.surround;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import org.json.JSONArray;

public class MainActivity extends androidx.appcompat.app.AppCompatActivity {
    final MainScreenBuilder mainScreenBuilder = new MainScreenBuilder(this);
    final LibraryController libraryController = new LibraryController(this);
    final PlaybackController playbackController = new PlaybackController(this);
    final LibraryRenderer libraryRenderer = new LibraryRenderer(this);
    final EffectsController effectsController = new EffectsController(this);
    final PlayerUi playerUi = new PlayerUi(this);
    final JamScreen jamScreen = new JamScreen(this);

    static final int PICK_AUDIO = 10;
    static final int PICK_FOLDER = 11;
    static final String[] PRESET_NAMES = {
        "Normal (Flat)",
        "Bass Boost",
        "Sub-Bass Cinema",
        "Clássico",
        "Dance",
        "Eletrônico",
        "Hip-Hop / Trap",
        "Jazz",
        "Metal",
        "Pop",
        "Rock",
        "Vocal / Podcast",
        "Acústico",
        "Lounge",
        "Audiophile V-Shape",
        "Studio Reference",
        "AutoEQ · Harman IE 2019 (IEM)",
        "AutoEQ · Harman Over-Ear 2018",
        "AutoEQ · Oratory1990 Diffuse Field",
        "AutoEQ · Sennheiser HD600 / HD650",
        "AutoEQ · Sony WH-1000XM4 / XM5",
        "AutoEQ · AirPods Pro / Max Target",
        "AutoEQ · KZ / CCA Planar Target",
        "AutoEQ · Moondrop Blessing / Aria",
        "AutoEQ · Beyerdynamic DT990 De-Peak",
        "Personalizado"
    };
    static final int[][] PRESETS = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},          // Normal (Flat)
        {7, 6, 5, 3, 1, 0, 0, 0, 0, 0},          // Bass Boost
        {9, 7, 4, 1, -1, -1, 0, 1, 2, 2},        // Sub-Bass Cinema
        {1, 1, 1, 0, 0, 0, 1, 2, 2, 1},          // Clássico
        {5, 5, 3, 0, -2, -1, 2, 4, 5, 4},        // Dance
        {6, 5, 2, 0, -2, 0, 2, 4, 5, 5},         // Eletrônico
        {6, 5, 3, 1, -1, -1, 1, 2, 3, 4},        // Hip-Hop / Trap
        {2, 1, 0, 1, 2, 2, 2, 2, 1, 1},          // Jazz
        {5, 4, 2, -1, -2, 0, 3, 4, 5, 4},        // Metal
        {2, 2, 1, 0, 1, 2, 2, 3, 3, 2},          // Pop
        {4, 3, 2, 0, -1, 0, 2, 3, 4, 3},         // Rock
        {-2, -1, 0, 1, 3, 4, 3, 2, 1, 0},        // Vocal / Podcast
        {2, 2, 1, 0, 1, 2, 2, 1, 1, 1},          // Acústico
        {3, 2, 1, 0, 0, 1, 2, 2, 1, 1},          // Lounge
        {5, 4, 2, -1, -2, -1, 1, 3, 5, 6},       // Audiophile V-Shape
        {0, 1, 0, 0, 0, 1, 1, 1, 2, 1},          // Studio Reference
        {6, 5, 3, 0, -1, 0, 3, 5, 3, 1},         // AutoEQ · Harman IE 2019 (IEM)
        {4, 4, 2, 0, -1, 1, 3, 4, 2, 1},         // AutoEQ · Harman Over-Ear 2018
        {2, 2, 1, 0, 0, 1, 2, 3, 2, 0},          // AutoEQ · Oratory1990 Diffuse Field
        {5, 4, 2, 0, 0, 0, 1, 1, 2, 3},          // AutoEQ · Sennheiser HD600 / HD650 (sub-bass extension)
        {-3, -4, -3, -1, 1, 2, 3, 2, 2, 1},      // AutoEQ · Sony WH-1000XM4 / XM5 (de-mud & upper-mid clarity)
        {3, 2, 1, 0, 0, 1, 2, 2, 3, 4},          // AutoEQ · AirPods Pro / Max Target
        {2, 1, 0, 1, 2, 1, -2, -4, -2, 1},       // AutoEQ · KZ / CCA Planar Target (tames 4-8kHz V-shape glare)
        {3, 2, 1, 0, 0, -1, 1, 2, 3, 2},         // AutoEQ · Moondrop Blessing / Aria
        {4, 3, 1, 0, 1, 1, 0, -5, -4, 0}         // AutoEQ · Beyerdynamic DT990 De-Peak (tames 8kHz treble peak)
    };
    static final int BACKGROUND = 0xFF08090C;
    static final int SURFACE = 0xFF12141C;
    static final int SURFACE_TOP = 0xFF1A1D28;
    static final int BORDER = 0xFF262A38;
    static final int ACCENT_BORDER = 0xFF9D7BFF;
    static final int TEXT = 0xFFF2F5FA;
    static final int MUTED = 0xFF8B93A7;
    static final int ACCENT = 0xFF9D7BFF;
    static final int TECH_CYAN = 0xFF00E5FF;
    static final int SIGNAL_OK = 0xFF2EE59D;
    static final int SIGNAL_WARN = 0xFFFFB74D;
    final ArrayList<TrackInfo> queue = new ArrayList<>();
    final ArrayList<Uri> rootFolders = new ArrayList<>();
    final ArrayList<Uri> manualTracks = new ArrayList<>();
    final ArrayList<ThumbnailRequest> thumbnails = new ArrayList<>();
    LibraryMetadataLoader metadataLoader;
    int libraryView = 1;
    int libraryScanGeneration;
    String selectedGroup;
    Button[] libraryTabs;
    AudioSlider preampSlider;
    EqualizerView equalizerView;
    SpatialStageView spatialStage;
    MusicCollections collections;
    Button favoriteButton;
    Button endMarkerButton;
    Button shuffleButton;
    Button repeatButton;
    final PresetTileView[] presetTiles = new PresetTileView[6];
    String collectionFilter;
    LinearLayout albumShelf;
    CheckBox compareOutputToggle;
    TextView dspStatus;
    FfmpegPcmPlayer player;
    int currentIndex = -1;
    int repeatMode = PlaybackService.REPEAT_OFF;
    boolean shuffleEnabled;
    int mode = SpatialProcessor.OFF;
    int intensity = 80;
    boolean changingProgress;
    boolean playing;
    long durationMs;
    long currentPositionMs;
    ArtworkView artwork;
    TextView title;
    TextView artist;
    TextView status;
    TextView outputRoute;
    TextView elapsed;
    TextView duration;
    TextView intensityValue;
    TextView queueCount;
    LinearLayout queueItems;
    SeekBar progress;
    AudioSlider intensitySlider;
    Button playButton;
    Button wideButton;
    Button roomButton;
    Button orbitButton;
    Button offButton;
    Button frontButton;
    Button surroundButton;
    Button theaterButton;
    Button ambioButton;
    Button holoButton;
    TextView outputDetails;
    TextView outputRouteDetails;
    TextView sourceBadgeView;
    TextView rendererBadgeView;
    android.widget.CompoundButton eqToggle;
    android.widget.CompoundButton cludesToggle;
    CheckBox autoMixToggle;
    boolean cludesOrbit;
    boolean cludesOrbitPreset;
    int previousSpatialMode;
    final double[] cludesBands = new double[5];
    int cludesVolume = 100;
    int cludesWidth = 100;
    int cludesBass;
    int cludesTreble;
    int cludesClarity;
    boolean cludesLoudness;
    boolean cludesEnhancer;
    AudioSlider cludesVolumeBar;
    AudioSlider cludesWidthBar;
    AudioSlider cludesBassBar;
    AudioSlider cludesTrebleBar;
    AudioSlider cludesClarityBar;
    AudioSlider cludesReverbBar;
    AudioSlider cludesSpeedBar;
    AudioSlider[] cludesEqBars;
    android.widget.CompoundButton pitchToggle;
    android.widget.CompoundButton loudnessToggle;
    android.widget.CompoundButton enhancerToggle;
    final double[] eqGains = new double[10];
    SeekBar[] eqSliders;
    TextView[] eqValues;
    AudioDropdown presetPicker;
    EditText librarySearch;
    boolean loadingPreset;
    int dynamicAccent = ACCENT;
    TextView techHeader;
    View techDot;
    MusicBackground musicBackground;
    LinearLayout miniPlayerBar;
    ImageView miniArtwork;
    TextView miniTitle;
    TextView miniArtist;
    Button miniPlayButton;
    Button miniNextButton;
    View miniProgressFill;
    Runnable[] studioTabSelectors;
    int activeRootTab;
    ScrollView mainScroll;
    Button alphabetIndexView;
    LinearLayout libraryHighlights;
    androidx.recyclerview.widget.RecyclerView libraryRecycler;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(0xFF0E1017);
        mode = getPreferences(MODE_PRIVATE).getInt("spatialMode", SpatialProcessor.OFF);
        intensity = getPreferences(MODE_PRIVATE).getInt("spatialIntensity", 80);
        repeatMode = getPreferences(MODE_PRIVATE).getInt("repeatMode", PlaybackService.REPEAT_OFF);
        boolean autoMixSaved = getPreferences(MODE_PRIVATE).getBoolean("autoMixEnabled", false);
        shuffleEnabled = !autoMixSaved && getPreferences(MODE_PRIVATE).getBoolean("shuffleEnabled", false);
        PlaybackService.setRepeatMode(repeatMode);
        PlaybackService.setShuffleEnabled(shuffleEnabled);
        metadataLoader = new LibraryMetadataLoader(this);
        collections = new MusicCollections(this);
        loadRoots();
        player = PlaybackService.getPlayer(this);
        PlaybackService.setUiListener(new FfmpegPcmPlayer.Listener() {
            public void onMessage(String message) { runOnUiThread(() -> {
                status.setText(message);
                if (outputDetails != null) outputDetails.setText(player.getFormatInfo());
                refreshOutputBadges();
                if (message.startsWith("Falha")) { playing = false; playButton.setText("▶"); artwork.setPlaying(false); }
                if (message.startsWith("Fone desconectado")) { playing = false; playButton.setText("▶"); artwork.setPlaying(false); }
            }); }
            public void onProgress(long positionMs, long totalMs) { runOnUiThread(() -> showProgress(positionMs, totalMs)); }
            public void onFinished() { runOnUiThread(() -> {
                if (PlaybackService.isJamGuest()) { showJamTrack(); return; }
                int index = PlaybackService.getCurrentIndex();
                if (index >= 0 && index < queue.size() && player.isRunning()) showTrack(index);
                else { playing = false; playButton.setText("▶"); artwork.setPlaying(false); status.setText("Fila concluída"); }
            }); }
            public void onRoute(String route) { runOnUiThread(() -> {
                outputRoute.setText(route);
                if (outputRouteDetails != null) outputRouteDetails.setText("Rota ativa: " + route);
                refreshOutputBadges();
            }); }
            public void onArtwork(Bitmap cover) { runOnUiThread(() -> {
                artwork.setCover(cover);
                playerUi.syncArtworkTheme(cover);
                if (PlaybackService.isJamGuest()) return;
                int index = PlaybackService.getCurrentIndex();
                if (index >= 0 && index < queue.size()) queue.get(index).artwork = cover;
            }); }
            public void onMetadata(String trackTitle, String trackArtist) { runOnUiThread(() -> {
                if (PlaybackService.isJamGuest()) { showJamTrack(); return; }
                int index = PlaybackService.getCurrentIndex();
                if (index < 0 || index >= queue.size()) return;
                TrackInfo item = queue.get(index);
                if (trackTitle != null) item.title = trackTitle;
                if (trackArtist != null) item.artist = trackArtist;
                title.setText(item.title);
                artist.setText(item.artist);
                renderQueue();
            }); }
        });
        player.setSpatial(mode, intensity);
        buildScreen();
        chooseMode(mode);
        refreshOutputBadges();
        restoreQueue();
        refreshFavorite();
        refreshEndMarker();
        refreshPlaybackModes();
        loadMetadata();
        renderQueue();
        refreshRoute();
    }

    void buildScreen() { mainScreenBuilder.buildScreen(); }

    void showJamTrack() {
        PlaybackService.JamSnapshot snapshot = PlaybackService.jamSnapshot();
        title.setText(snapshot.title); artist.setText(snapshot.artist);
        Bitmap jamCover = PlaybackService.getCurrentArtwork();
        artwork.setCover(jamCover);
        playerUi.syncArtworkTheme(jamCover);
        playing = player.isRunning() && !player.isPaused();
        playButton.setText(playing ? "❚❚" : "▶"); artwork.setPlaying(playing);
        playerUi.syncMiniPlayerFromMain();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        jamScreen.permissionsResult(request, results);
    }

    void openPicker() { libraryController.openPicker(); }

    void openFolder() { libraryController.openFolder(); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SpotifyBridgeScreen.REQUEST_MEDIA_PROJECTION) {
            SpotifyBridgeScreen.handleProjectionResult(this, result, data);
            return;
        }
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_FOLDER && data.getData() != null) {
            Uri folder = data.getData();
            try { getContentResolver().takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            if (!rootFolders.contains(folder)) rootFolders.add(folder);
            saveRoots();
            refreshLibrary();
            return;
        }
        if (request != PICK_AUDIO) return;
        int firstAdded = queue.size();
        ClipData selected = data.getClipData();
        if (selected != null) {
            for (int index = 0; index < selected.getItemCount(); index++) addTrack(selected.getItemAt(index).getUri());
        } else if (data.getData() != null) addTrack(data.getData());
        if (queue.size() > firstAdded) loadMetadata();
        renderQueue();
        if (queue.size() > firstAdded) {
            status.setText((queue.size() - firstAdded) + " música(s) adicionada(s). Toque em uma faixa para reproduzir.");
        }
    }

    void loadRoots() { libraryController.loadRoots(); }

    boolean belongsToRoot(Uri file, Uri root) { return libraryController.belongsToRoot(file, root); }

    void saveRoots() { libraryController.saveRoots(); }

    void saveManualTracks() { libraryController.saveManualTracks(); }

    void refreshLibrary() { libraryController.refreshLibrary(); }

    void scanRoot(int index, ArrayList<TrackInfo> found, int token) { libraryController.scanRoot(index, found, token); }

    String rootName(Uri uri) { return libraryController.rootName(uri); }

    void showLibrarySettings() { libraryController.showLibrarySettings(); }

    void showRootFolders() { libraryController.showRootFolders(); }

    void loadMetadata() { libraryController.loadMetadata(); }

    void addTrack(Uri uri) { libraryController.addTrack(uri); }

    void playTrack(int index) { playbackController.playTrack(index); }

    void showTrack(int index) { playbackController.showTrack(index); }

    void toggle() { playbackController.toggle(); }

    void previousTrack() { playbackController.previousTrack(); }

    void nextTrack(boolean completed) { playbackController.nextTrack(completed); }

    void toggleShuffleMode() { playbackController.toggleShuffleMode(); }

    void cycleRepeatMode() { playbackController.cycleRepeatMode(); }

    void refreshPlaybackModes() { playbackController.refreshPlaybackModes(); }

    void toggleEndMarker() { playbackController.toggleEndMarker(); }

    void refreshEndMarker() { playbackController.refreshEndMarker(); }

    void chooseMode(int nextMode) { effectsController.chooseMode(nextMode); playerUi.updateTechHeader(); }

    Button selectedButton() { return effectsController.selectedButton(); }

    String modeName() { return effectsController.modeName(); }

    void showProgress(long positionMs, long totalMs) {
        playbackController.showProgress(positionMs, totalMs);
        playerUi.updateMiniProgress(positionMs, totalMs);
    }

    void renderQueue() { libraryRenderer.renderQueue(); }

    void appendTrackRow(int trackIndex, TrackInfo track, String subtitle) { libraryRenderer.appendTrackRow(trackIndex, track, subtitle); }

    String groupKey(TrackInfo track) { return libraryRenderer.groupKey(track); }

    void refreshFavorite() { playbackController.refreshFavorite(); }

    void createPlaylist(String uri) { libraryRenderer.createPlaylist(uri); }

    void addToPlaylist(String uri) { libraryRenderer.addToPlaylist(uri); }

    void showPlaybackQueue() { playbackController.showPlaybackQueue(); }

    String primaryArtist(String artist) { return libraryRenderer.primaryArtist(artist); }

    void loadVisibleCovers() { libraryRenderer.loadVisibleCovers(); }

    static final class ThumbnailRequest {
        final ImageView view;
        final TrackInfo track;
        boolean requested;
        ThumbnailRequest(ImageView view, TrackInfo track) { this.view = view; this.track = track; }
    }

    void refreshRoute() { effectsController.refreshRoute(); playerUi.updateTechHeader(); }

    void refreshOutputBadges() {
        effectsController.refreshOutputBadges();
        playerUi.updateTechHeader();
        SpotifyLiveBridge.syncDirectDspParameters(this);
    }

    void saveQueue() { libraryController.saveQueue(); }

    void restoreQueue() { libraryController.restoreQueue(); }

    @Override protected void onResume() {
        super.onResume();
        SpotifyLiveBridge.attachHost(this);
        SpotifyLiveBridge.inspectIncomingIntent(this, getIntent());
        if (outputRoute != null) refreshRoute();
        if (playButton != null && player != null) {
            if (PlaybackService.isJamGuest()) { showJamTrack(); return; }
            if (SpotifyLiveBridge.isSpotifyControlActive(this)) {
                playing = SpotifyLiveBridge.isSpotifyPlaying();
                playButton.setText(playing ? "❚❚" : "▶");
                if (artwork != null) artwork.setPlaying(playing);
                if (!SpotifyLiveBridge.getLastSpotifyTrack().isEmpty() && title != null && artist != null) {
                    title.setText(SpotifyLiveBridge.getLastSpotifyTrack());
                    artist.setText(SpotifyLiveBridge.getLastSpotifyArtist());
                }
                Bitmap spotCover = SpotifyLiveBridge.getLastSpotifyCover();
                if (spotCover != null && artwork != null) {
                    artwork.setCover(spotCover);
                    playerUi.syncArtworkTheme(spotCover);
                }
                long spotDur = SpotifyLiveBridge.getLastSpotifyDurationMs();
                if (spotDur > 0) {
                    durationMs = spotDur;
                    showProgress(SpotifyLiveBridge.getCurrentSpotifyPositionMs(), spotDur);
                }
                playerUi.syncMiniPlayerFromMain();
                playerUi.updateTechHeader();
                SpotifyLiveBridge.syncDirectDspParameters(this);
                return;
            }
            int activeIndex = PlaybackService.getCurrentIndex();
            if (activeIndex >= 0 && activeIndex < queue.size() && activeIndex != currentIndex) showTrack(activeIndex);
            refreshFavorite();
            refreshEndMarker();
            refreshPlaybackModes();
            playing = player.isRunning() && !player.isPaused();
            playButton.setText(playing ? "❚❚" : "▶");
            if (artwork != null) artwork.setPlaying(playing);
            playerUi.syncMiniPlayerFromMain();
            playerUi.updateTechHeader();
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        SpotifyLiveBridge.inspectIncomingIntent(this, intent);
    }

    @Override protected void onDestroy() {
        SpotifyLiveBridge.detachHost(this);
        jamScreen.close();
        metadataLoader.cancel();
        PlaybackService.setUiListener(null);
        super.onDestroy();
    }

    void updateCludes() { effectsController.updateCludes(); }

    void applyCludesPreset(int preset) { effectsController.applyCludesPreset(preset); }

    void saveCludesPreset() { effectsController.saveCludesPreset(); }

    void loadCludesPreset() { effectsController.loadCludesPreset(); }

    interface ValueChanged { void onChange(int value); }
    AudioSlider addSlider(LinearLayout parent, String name, int minimum, int maximum, int initial, ValueChanged callback) { return playerUi.addSlider(parent, name, minimum, maximum, initial, callback); }
    String formatSlider(String name, int value) { return playerUi.formatSlider(name, value); }
    void saveCustomEq() { effectsController.saveCustomEq(); }

    String formatTime(long milliseconds) { return playerUi.formatTime(milliseconds); }
    int dp(int size) { return playerUi.dp(size); }
    LinearLayout column() { return playerUi.column(); }
    LinearLayout row() { return playerUi.row(); }
    LinearLayout.LayoutParams weighted() { return playerUi.weighted(); }
    void space(LinearLayout parent, int height) { playerUi.space(parent, height); }
    TextView label(LinearLayout parent, String value, int size, int color, boolean bold) { return playerUi.label(parent, value, size, color, bold); }
    TextView label(LinearLayout parent, String value, float size, int color, boolean bold) { return playerUi.label(parent, value, size, color, bold); }
    GradientDrawable background(int color, int radius) { return playerUi.background(color, radius); }
    GradientDrawable borderedBackground(int color, int strokeColor, int radius) { return playerUi.borderedBackground(color, strokeColor, radius); }
    LinearLayout card() { return playerUi.card(); }
    Button pill(String value, boolean selected) { return playerUi.pill(value, selected); }
    Button transportButton(String value, boolean primary) { return playerUi.transportButton(value, primary); }
    Button miniTransportToggle(String value) { return playerUi.miniTransportToggle(value); }
    void addBackHeader(LinearLayout panel, String heading, Runnable onBack) { playerUi.addBackHeader(panel, heading, onBack); }
    LinearLayout hubTile(String heading, String sub, Runnable action) { return playerUi.hubTile(heading, sub, action); }
    LinearLayout.LayoutParams hubTileParams(boolean left) { return playerUi.hubTileParams(left); }
    LinearLayout hubRow(String heading, String sub, Runnable action) { return playerUi.hubRow(heading, sub, action); }
}
