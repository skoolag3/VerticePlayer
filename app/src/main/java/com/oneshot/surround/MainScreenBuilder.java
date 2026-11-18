package com.oneshot.surround;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.navigation.NavigationBarView;
import java.util.ArrayList;

import static android.content.Context.MODE_PRIVATE;

/**
 * Audiophile Studio Dark UI shell:
 * - No-scroll main Player with ambient album color glow & technical telemetry header
 * - Unified Estúdio DSP tab with segmented switcher (Espaço 3D | Equalizador | CludesAmp | Saída DAC)
 * - Persistent Mini-Player bar across Biblioteca / Estúdio DSP / Início
 * - 4-tab OLED BottomNavigationView replacing the legacy popup menu
 */
final class MainScreenBuilder {
    private final MainActivity host;

    MainScreenBuilder(MainActivity host) {
        this.host = host;
    }

    void buildScreen() {
        LinearLayout rootShell = host.column();
        rootShell.setBackgroundColor(host.BACKGROUND);
        host.setContentView(rootShell);

        ScrollView scroll = new ScrollView(host);
        host.mainScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        scroll.setOnScrollChangeListener((view, x, y, px, py) -> host.loadVisibleCovers());

        LinearLayout page = host.column();
        page.setPadding(host.dp(16), host.dp(10), host.dp(16), host.dp(12));
        scroll.addView(page);

        FrameLayout centerFrame = new FrameLayout(host);
        host.musicBackground = new MusicBackground(host);
        centerFrame.addView(host.musicBackground);
        centerFrame.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootShell.addView(centerFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Hidden anchor for host.outputRoute updates so legacy callbacks drive techHeader cleanly
        host.outputRoute = new TextView(host);
        host.outputRoute.setVisibility(View.GONE);
        page.addView(host.outputRoute);

        // 4 Primary Top-Level Panels (libraryPanel sits outside ScrollView so RecyclerView virtualizes 900+ tracks at 60 FPS)
        LinearLayout playerPanel = host.column();
        LinearLayout libraryPanel = host.column();
        libraryPanel.setPadding(host.dp(16), host.dp(10), host.dp(16), 0);
        libraryPanel.setVisibility(View.GONE);
        LinearLayout studioPanel = host.column();
        LinearLayout homePanel = host.column();
        LinearLayout[] scrollPanels = {playerPanel, studioPanel, homePanel};
        for (LinearLayout p : scrollPanels) page.addView(p);
        centerFrame.addView(libraryPanel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout[] rootPanels = {playerPanel, libraryPanel, studioPanel, homePanel};

        // Sub-panels inside Estúdio DSP
        LinearLayout spatialPanel = host.column();
        LinearLayout eqPanel = host.column();
        LinearLayout cludesPanel = host.column();
        LinearLayout outputPanel = host.column();
        LinearLayout[] studioPanels = {spatialPanel, eqPanel, cludesPanel, outputPanel};
        String[] studioTitles = {"Espaço 3D", "Equalizador", "CludesAmp", "Saída DAC"};
        Button[] studioTabs = new Button[studioPanels.length];

        // --- Persistent Mini-Player Bar (shown outside Player tab) ---
        host.miniPlayerBar = host.column();
        host.miniPlayerBar.setBackgroundColor(MainActivity.SURFACE_TOP);
        host.miniPlayerBar.setVisibility(View.GONE);
        rootShell.addView(host.miniPlayerBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Top 2dp dynamic progress accent strip
        View miniTrack = new View(host);
        miniTrack.setBackgroundColor(MainActivity.BORDER);
        FrameLayout miniTrackWrap = new FrameLayout(host);
        miniTrackWrap.addView(miniTrack, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(2)));
        host.miniProgressFill = new View(host);
        host.miniProgressFill.setBackgroundColor(host.dynamicAccent);
        miniTrackWrap.addView(host.miniProgressFill, new FrameLayout.LayoutParams(0, host.dp(2)));
        host.miniPlayerBar.addView(miniTrackWrap, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(2)));

        LinearLayout miniRow = host.row();
        miniRow.setGravity(Gravity.CENTER_VERTICAL);
        miniRow.setPadding(host.dp(14), host.dp(8), host.dp(10), host.dp(8));
        miniRow.setMinimumHeight(host.dp(60));
        host.miniPlayerBar.addView(miniRow);

        host.miniArtwork = new ImageView(host);
        host.miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        host.miniArtwork.setImageResource(R.drawable.ic_app);
        host.miniArtwork.setBackground(host.background(MainActivity.SURFACE, 10));
        host.miniArtwork.setClipToOutline(true);
        LinearLayout.LayoutParams miniArtLp = new LinearLayout.LayoutParams(host.dp(42), host.dp(42));
        miniArtLp.rightMargin = host.dp(12);
        miniRow.addView(host.miniArtwork, miniArtLp);

        LinearLayout miniInfo = host.column();
        miniRow.addView(miniInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        host.miniTitle = host.label(miniInfo, "Vértice Player", 13, host.TEXT, true);
        host.miniTitle.setSingleLine(true);
        host.miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        host.miniArtist = host.label(miniInfo, "Toque para abrir o player", 11, host.MUTED, false);
        host.miniArtist.setSingleLine(true);
        host.miniArtist.setEllipsize(TextUtils.TruncateAt.END);

        host.miniPlayButton = host.miniTransportToggle("▶");
        ((MaterialButton) host.miniPlayButton).setIconSize(host.dp(20));
        ((MaterialButton) host.miniPlayButton).setCornerRadius(host.dp(19));
        LinearLayout.LayoutParams miniBtnLp1 = new LinearLayout.LayoutParams(host.dp(38), host.dp(38));
        miniBtnLp1.setMargins(host.dp(6), 0, host.dp(4), 0);
        miniRow.addView(host.miniPlayButton, miniBtnLp1);
        host.miniPlayButton.setOnClickListener(v -> host.toggle());

        host.miniNextButton = host.miniTransportToggle("❯❯");
        ((MaterialButton) host.miniNextButton).setIconSize(host.dp(18));
        ((MaterialButton) host.miniNextButton).setCornerRadius(host.dp(19));
        LinearLayout.LayoutParams miniBtnLp2 = new LinearLayout.LayoutParams(host.dp(38), host.dp(38));
        miniRow.addView(host.miniNextButton, miniBtnLp2);
        host.miniNextButton.setOnClickListener(v -> host.nextTrack(false));

        // --- 4-Tab OLED Bottom Navigation Bar ---
        BottomNavigationView bottomBar = new BottomNavigationView(host);
        bottomBar.setBackgroundColor(0xFF0E1017);
        ColorStateList navColors = new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{MainActivity.ACCENT, MainActivity.MUTED}
        );
        bottomBar.setItemIconTintList(navColors);
        bottomBar.setItemTextColor(navColors);
        bottomBar.setItemActiveIndicatorColor(ColorStateList.valueOf(0x289D7BFF));

        View navDivider = new View(host);
        navDivider.setBackgroundColor(MainActivity.BORDER);
        rootShell.addView(navDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1)));
        rootShell.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[] navLabels = {"Player", "Biblioteca", "Estúdio DSP", "Início"};
        int[] navIcons = {R.drawable.ic_home, R.drawable.ic_library, R.drawable.ic_equalizer, R.drawable.ic_settings};
        for (int index = 0; index < 4; index++) {
            bottomBar.getMenu().add(0, index + 1, index, navLabels[index]).setIcon(navIcons[index]);
        }
        bottomBar.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);

        Runnable[] showRootTab = new Runnable[rootPanels.length];
        for (int i = 0; i < rootPanels.length; i++) {
            final int target = i;
            showRootTab[i] = () -> {
                host.activeRootTab = target;
                for (int j = 0; j < rootPanels.length; j++) {
                    rootPanels[j].setVisibility(j == target ? View.VISIBLE : View.GONE);
                }
                // Updating the checked state must not dispatch the selection callback again.
                bottomBar.getMenu().findItem(target + 1).setChecked(true);
                host.playerUi.refreshMiniVisibility();
                boolean isLibrary = (target == 1);
                scroll.setVisibility(isLibrary ? View.GONE : View.VISIBLE);
                if (!isLibrary) {
                    scroll.smoothScrollTo(0, 0);
                } else if (host.libraryRecycler != null) {
                    host.renderQueue();
                }
            };
        }

        Runnable[] selectStudioTab = new Runnable[studioPanels.length];
        for (int i = 0; i < studioPanels.length; i++) {
            final int sub = i;
            selectStudioTab[i] = () -> {
                for (int j = 0; j < studioPanels.length; j++) {
                    studioPanels[j].setVisibility(j == sub ? View.VISIBLE : View.GONE);
                    boolean sel = (j == sub);
                    if (studioTabs[j] != null) {
                        MaterialUi.buttonState(studioTabs[j], sel);
                    }
                }
                showRootTab[2].run();
            };
        }
        host.studioTabSelectors = selectStudioTab;

        miniInfo.setOnClickListener(v -> showRootTab[0].run());
        host.miniArtwork.setOnClickListener(v -> showRootTab[0].run());

        bottomBar.setOnItemSelectedListener(item -> {
            int idx = item.getItemId() - 1;
            if (idx >= 0 && idx < showRootTab.length) {
                showRootTab[idx].run();
            }
            return true;
        });

        // --- TAB 0: AUDIOPHILE NO-SCROLL PLAYER SCREEN ---
        AppearanceSettings appearance = new AppearanceSettings(host);

        // Top Technical Telemetry Bar + Jam Trigger
        LinearLayout topHeader = host.row();
        topHeader.setGravity(Gravity.CENTER_VERTICAL);
        homePanel.addView(topHeader);

        LinearLayout techChip = host.row();
        techChip.setGravity(Gravity.CENTER_VERTICAL);
        techChip.setPadding(host.dp(10), host.dp(5), host.dp(10), host.dp(5));
        techChip.setBackground(host.borderedBackground(MainActivity.SURFACE, MainActivity.BORDER, 99));
        topHeader.addView(techChip, new LinearLayout.LayoutParams(0, host.dp(32), 1));

        host.techDot = new View(host);
        host.techDot.setBackground(host.background(MainActivity.SIGNAL_OK, 99));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(host.dp(7), host.dp(7));
        dotLp.rightMargin = host.dp(7);
        techChip.addView(host.techDot, dotLp);

        host.techHeader = host.label(techChip, "VÉRTICE · 64-BIT DSP", 10.5f, host.TEXT, true);
        host.techHeader.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        host.techHeader.setSingleLine(true);
        host.techHeader.setEllipsize(TextUtils.TruncateAt.END);
        techChip.setOnClickListener(v -> selectStudioTab[3].run());

        Button jamChip = host.pill("Jam", false);
        jamChip.setTextSize(11);
        jamChip.setPadding(host.dp(10), 0, host.dp(10), 0);
        LinearLayout.LayoutParams jamLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, host.dp(32));
        jamLp.leftMargin = host.dp(6);
        topHeader.addView(jamChip, jamLp);
        jamChip.setOnClickListener(v -> host.jamScreen.open());

        host.space(playerPanel, 8);

        // Centerpiece Free-Floating Cover Art Stage (Full Width, Unclipped) + Separate Bottom Pill Bar
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        int stageHeight = Math.min(screenW - host.dp(32), Math.min(host.dp(430), Math.round(screenH * 0.50f)));
        stageHeight = Math.max(host.dp(176), stageHeight);

        FrameLayout artworkStage = new FrameLayout(host);
        artworkStage.setClipChildren(false);
        artworkStage.setClipToPadding(false);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageHeight);
        stageLp.gravity = Gravity.CENTER_HORIZONTAL;
        playerPanel.setClipChildren(false);
        playerPanel.setClipToPadding(false);
        playerPanel.addView(artworkStage, stageLp);

        host.artwork = new ArtworkView(host);
        host.artwork.setAnalyzer(host.player.getVisualAnalyzer());
        FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageHeight, Gravity.CENTER);
        artworkStage.addView(host.artwork, artLp);

        Button appearanceButton = host.pill("", false);
        com.google.android.material.button.MaterialButton tune =
            (com.google.android.material.button.MaterialButton) appearanceButton;
        tune.setIconResource(R.drawable.ic_tune);
        tune.setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
        tune.setIconPadding(0);
        tune.setPadding(0, 0, 0, 0);
        tune.setContentDescription("Aparência do player");
        FrameLayout.LayoutParams tuneLayout = new FrameLayout.LayoutParams(host.dp(48), host.dp(48), Gravity.TOP | Gravity.END);
        artworkStage.addView(tune, tuneLayout);
        tune.setOnClickListener(v -> {
            LinearLayout settings = host.column();
            settings.setPadding(host.dp(20), 0, host.dp(20), host.dp(8));
            appearance.build(settings);
            ScrollView scrollSettings = new ScrollView(host);
            scrollSettings.addView(settings);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                .setTitle("Aparência").setView(scrollSettings).setPositiveButton("Pronto", null).show();
        });

        Button spotifyPill = host.pill("🟢 Spotify DSP", false);
        spotifyPill.setTextSize(10.5f);
        spotifyPill.setPadding(host.dp(10), 0, host.dp(10), 0);
        FrameLayout.LayoutParams spotLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, host.dp(36), Gravity.TOP | Gravity.START);
        spotLp.topMargin = host.dp(6);
        artworkStage.addView(spotifyPill, spotLp);
        spotifyPill.setOnClickListener(v -> SpotifyBridgeScreen.open(host));

        // Title + Artist
        host.title = host.label(playerPanel, "Sua música começa aqui", 19, host.TEXT, true);
        host.title.setSingleLine(true);
        host.title.setEllipsize(TextUtils.TruncateAt.END);
        host.title.setGravity(Gravity.CENTER_HORIZONTAL);

        host.artist = host.label(playerPanel, "Adicione arquivos FLAC ou músicas locais", 12.5f, host.MUTED, false);
        host.artist.setSingleLine(true);
        host.artist.setEllipsize(TextUtils.TruncateAt.END);
        host.artist.setGravity(Gravity.CENTER_HORIZONTAL);

        host.space(playerPanel, 6);

        // Quick Actions Row (Tags button replaces Favoritar on the main player screen; long-press toggles Favorite too!)
        LinearLayout trackActions = host.row();
        playerPanel.addView(trackActions);

        host.favoriteButton = action("🏷 Tags", R.drawable.ic_favorite_border);
        host.favoriteButton.setTextSize(11);
        host.favoriteButton.setPadding(host.dp(4), 0, host.dp(4), 0);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(0, host.dp(48), 1);
        actLp.setMargins(host.dp(3), 0, host.dp(3), 0);
        trackActions.addView(host.favoriteButton, actLp);
        host.favoriteButton.setOnClickListener(v -> {
            if (host.currentIndex < 0 || host.currentIndex >= host.queue.size()) {
                host.status.setText("Escolha uma música para adicionar Tags");
                return;
            }
            TagDialogHelper.showTrackTagSheet(host, host.queue.get(host.currentIndex), () -> {
                host.refreshFavorite();
                host.renderQueue();
            });
        });
        host.favoriteButton.setOnLongClickListener(v -> {
            if (host.currentIndex < 0 || host.currentIndex >= host.queue.size()) return true;
            host.collections.toggleFavorite(host.queue.get(host.currentIndex).uri.toString());
            host.refreshFavorite();
            host.renderQueue();
            return true;
        });

        host.endMarkerButton = action("Marcar fim", R.drawable.ic_content_cut);
        host.endMarkerButton.setTextSize(11);
        host.endMarkerButton.setPadding(host.dp(4), 0, host.dp(4), 0);
        trackActions.addView(host.endMarkerButton, actLp);
        host.endMarkerButton.setOnClickListener(v -> host.toggleEndMarker());

        Button queueButton = action("Fila", R.drawable.ic_queue_music);
        queueButton.setTextSize(11);
        queueButton.setPadding(host.dp(4), 0, host.dp(4), 0);
        trackActions.addView(queueButton, actLp);
        queueButton.setOnClickListener(v -> host.showPlaybackQueue());

        host.space(playerPanel, 6);

        // Timeline SeekBar + Monospace Timecodes
        host.progress = new NeonSeekBar(host);
        host.progress.setMax(1000);
        host.progress.setProgressTintList(ColorStateList.valueOf(host.ACCENT));
        host.progress.setThumbTintList(ColorStateList.valueOf(host.ACCENT));
        playerPanel.addView(host.progress);
        host.progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && host.durationMs > 0) host.elapsed.setText(host.formatTime(host.durationMs * value / 1000));
            }
            public void onStartTrackingTouch(SeekBar bar) { host.changingProgress = true; }
            public void onStopTrackingTouch(SeekBar bar) {
                if (PlaybackService.isJamGuest()) host.status.setText("Quem criou a Jam controla a posição da música");
                else if (host.durationMs > 0) {
                    host.currentPositionMs = host.durationMs * bar.getProgress() / 1000;
                    host.player.seekTo(host.currentPositionMs);
                }
                host.changingProgress = false;
            }
        });

        LinearLayout timeline = host.row();
        timeline.setPadding(host.dp(8), 0, host.dp(8), 0);
        playerPanel.addView(timeline);
        host.elapsed = host.label(timeline, "0:00", 11.5f, host.MUTED, true);
        host.elapsed.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timeline.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        host.duration = host.label(timeline, "0:00", 11.5f, host.MUTED, true);
        host.duration.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        host.space(playerPanel, 6);

        // Transport Buttons (keeps host.shuffleButton, host.playButton, host.repeatButton!)
        LinearLayout transport = host.row();
        transport.setGravity(Gravity.CENTER);
        playerPanel.addView(transport);

        host.shuffleButton = host.miniTransportToggle("⇄");
        Button previous = host.transportButton("❮❮", false);
        host.playButton = host.transportButton("▶", true);
        Button next = host.transportButton("❯❯", false);
        host.repeatButton = host.miniTransportToggle("↻");

        LinearLayout.LayoutParams smallToggleLp = new LinearLayout.LayoutParams(host.dp(42), host.dp(42));
        smallToggleLp.setMargins(host.dp(2), 0, host.dp(6), 0);
        LinearLayout.LayoutParams prevNextLp = new LinearLayout.LayoutParams(0, host.dp(48), 1);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(host.dp(64), host.dp(64));
        playLp.setMargins(host.dp(10), 0, host.dp(10), 0);
        LinearLayout.LayoutParams smallRightLp = new LinearLayout.LayoutParams(host.dp(42), host.dp(42));
        smallRightLp.setMargins(host.dp(6), 0, host.dp(2), 0);

        transport.addView(host.shuffleButton, smallToggleLp);
        transport.addView(previous, prevNextLp);
        transport.addView(host.playButton, playLp);
        transport.addView(next, prevNextLp);
        transport.addView(host.repeatButton, smallRightLp);

        host.shuffleButton.setOnClickListener(view -> host.toggleShuffleMode());
        previous.setOnClickListener(view -> host.previousTrack());
        host.playButton.setOnClickListener(view -> host.toggle());
        next.setOnClickListener(view -> host.nextTrack(false));
        host.repeatButton.setOnClickListener(view -> host.cycleRepeatMode());
        appearance.apply();

        host.space(playerPanel, 8);

        // Compact 1-Line AutoMix Control Strip (keeps host.autoMixToggle!)
        LinearLayout autoMixStrip = host.row();
        autoMixStrip.setGravity(Gravity.CENTER_VERTICAL);
        autoMixStrip.setPadding(host.dp(10), host.dp(2), host.dp(10), host.dp(2));
        autoMixStrip.setBackground(host.borderedBackground(MainActivity.SURFACE, MainActivity.BORDER, 14));
        homePanel.addView(autoMixStrip, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42)));

        host.autoMixToggle = new MaterialCheckBox(host);
        host.autoMixToggle.setText("AutoMix Harmônico");
        host.autoMixToggle.setTextSize(12.5f);
        host.autoMixToggle.setTextColor(host.TEXT);
        host.autoMixToggle.setButtonTintList(ColorStateList.valueOf(host.ACCENT));
        boolean autoMixInitial = host.getPreferences(MODE_PRIVATE).getBoolean("autoMixEnabled", false);
        host.autoMixToggle.setChecked(autoMixInitial);
        autoMixStrip.addView(host.autoMixToggle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        boolean savedCrossfade = host.getPreferences(MODE_PRIVATE).getBoolean("autoMixCrossfade", true);
        int savedFadeOverride = host.getPreferences(MODE_PRIVATE).getInt("autoMixFadeMs", 0);
        int savedFxStyle = host.getPreferences(MODE_PRIVATE).getInt("autoMixFxStyle", -1);
        boolean savedConsiderTags = host.getPreferences(MODE_PRIVATE).getBoolean("autoMixConsiderTags", true);
        String savedTargetTag = host.getPreferences(MODE_PRIVATE).getString("autoMixTargetTag", "");
        PlaybackService.setCrossfadeEnabled(savedCrossfade);
        PlaybackService.setCrossfadeOverrideMs(savedFadeOverride);
        PlaybackService.setForcedTransitionStyle(savedFxStyle);
        PlaybackService.setConsiderTagsInAutoMix(savedConsiderTags);
        PlaybackService.setTargetAutoMixTag(savedTargetTag);

        TextView autoMixBadge = host.playerUi.monoBadge(autoMixStrip,
            autoMixInitial ? (savedCrossfade ? "CROSSFADE" : "SECO") : "OFF",
            autoMixInitial ? MainActivity.SIGNAL_OK : MainActivity.MUTED);

        TextView infoIcon = host.label(autoMixStrip, "  ⚙ ", 15, MainActivity.TECH_CYAN, true);
        infoIcon.setPadding(host.dp(8), host.dp(4), host.dp(4), host.dp(4));
        infoIcon.setOnClickListener(v -> showAutoMixSettingsDialog(autoMixBadge));

        PlaybackService.setAutoMix(host, host.autoMixToggle.isChecked());
        host.autoMixToggle.setOnCheckedChangeListener((button, enabled) -> {
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("autoMixEnabled", enabled).apply();
            PlaybackService.setAutoMix(host, enabled);
            if (enabled && host.shuffleEnabled) {
                host.shuffleEnabled = false;
                host.getPreferences(MODE_PRIVATE).edit().putBoolean("shuffleEnabled", false).apply();
                PlaybackService.setShuffleEnabled(false);
                host.refreshPlaybackModes();
            }
            int badgeColor = enabled ? MainActivity.SIGNAL_OK : MainActivity.MUTED;
            autoMixBadge.setText(enabled ? (PlaybackService.isCrossfadeEnabled() ? "CROSSFADE" : "SECO") : "OFF");
            autoMixBadge.setTextColor(badgeColor);
            autoMixBadge.setBackground(host.borderedBackground(
                (0x18 << 24) | (badgeColor & 0x00FFFFFF),
                (0x45 << 24) | (badgeColor & 0x00FFFFFF), 6));
            String scopeLabel = PlaybackService.getActiveScopeName();
            String scopeNote = (scopeLabel != null && !scopeLabel.isEmpty()) ? (" (" + scopeLabel + ")") : "";
            host.status.setText(enabled
                ? ("AutoMix ligado" + scopeNote + " · toque em ⚙ para Crossfade/FX")
                : "AutoMix desligado · ordem da biblioteca");
        });

        host.space(playerPanel, 4);
        host.status = host.label(homePanel, "Escolha uma música para ouvir a diferença.", 11, host.MUTED, false);
        host.status.setGravity(Gravity.CENTER_HORIZONTAL);
        host.status.setSingleLine(true);
        host.status.setEllipsize(TextUtils.TruncateAt.END);

        // --- TAB 1: BIBLIOTECA ---
        new LibraryScreen(host).build(libraryPanel);

        // --- TAB 2: ESTÚDIO DSP UNIFICADO ---
        LinearLayout studioTopRow = host.row();
        studioTopRow.setGravity(Gravity.CENTER_VERTICAL);
        studioPanel.addView(studioTopRow);
        TextView studioTag = host.label(studioTopRow, "ESTÚDIO DSP · 64-BIT", 11, MainActivity.TECH_CYAN, true);
        studioTag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        studioTopRow.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        host.label(studioTopRow, "Áudio em tempo real", 11, host.MUTED, false);

        host.space(studioPanel, 6);

        LinearLayout segmentedWrap = host.row();
        segmentedWrap.setPadding(host.dp(3), host.dp(3), host.dp(3), host.dp(3));
        segmentedWrap.setBackground(host.borderedBackground(MainActivity.SURFACE, MainActivity.BORDER, 14));
        studioPanel.addView(segmentedWrap);

        for (int index = 0; index < studioTitles.length; index++) {
            final int tabIdx = index;
            Button tabBtn = host.pill(studioTitles[index], index == 0);
            tabBtn.setTextSize(10.5f);
            tabBtn.setMinHeight(host.dp(34));
            tabBtn.setMinimumHeight(host.dp(34));
            tabBtn.setPadding(host.dp(4), 0, host.dp(4), 0);
            ((MaterialButton) tabBtn).setCornerRadius(host.dp(10));
            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, host.dp(34), 1);
            tabLp.setMargins(host.dp(2), 0, host.dp(2), 0);
            segmentedWrap.addView(tabBtn, tabLp);
            studioTabs[index] = tabBtn;
            tabBtn.setOnClickListener(v -> selectStudioTab[tabIdx].run());
        }

        host.space(studioPanel, 10);
        for (LinearLayout sp : studioPanels) studioPanel.addView(sp);
        new SpatialScreen(host).build(spatialPanel);
        new EqualizerScreen(host).build(eqPanel);
        new CludesScreen(host).build(cludesPanel);
        new OutputScreen(host).build(outputPanel);
        for (int j = 0; j < studioPanels.length; j++) {
            studioPanels[j].setVisibility(j == 0 ? View.VISIBLE : View.GONE);
            MaterialUi.buttonState(studioTabs[j], j == 0);
        }

        // --- TAB 3: INÍCIO / JAM & APARÊNCIA ---
        LinearLayout heroCard = host.card();
        homePanel.addView(heroCard);
        TextView heroBrand = host.label(heroCard, "VÉRTICE AUDIO STUDIO", 11, MainActivity.TECH_CYAN, true);
        heroBrand.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        host.label(heroCard, "Mesa de som & Aparência", 20, host.TEXT, true);
        host.label(heroCard, "Atalhos de estúdio, sincronização Jam Wi-Fi e tema visual.", 12, host.MUTED, false);
        host.space(heroCard, 8);
        Button jamLaunch = host.pill("✦  Abrir Jam · Wi-Fi Direct", true);
        heroCard.addView(jamLaunch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42)));
        jamLaunch.setOnClickListener(v -> host.jamScreen.open());

        host.space(heroCard, 6);
        Button spotifyLaunch = host.pill("🟢  Spotify & Spotron 64-Bit DSP Bridge", false);
        heroCard.addView(spotifyLaunch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42)));
        spotifyLaunch.setOnClickListener(v -> SpotifyBridgeScreen.open(host));

        LinearLayout gridRow1 = host.row();
        homePanel.addView(gridRow1);
        gridRow1.addView(host.hubTile("Espaço 3D", "Holográfico & Binaural", () -> selectStudioTab[0].run()), host.hubTileParams(true));
        gridRow1.addView(host.hubTile("Equalizador", "10 bandas paramétricas", () -> selectStudioTab[1].run()), host.hubTileParams(false));

        LinearLayout gridRow2 = host.row();
        homePanel.addView(gridRow2);
        gridRow2.addView(host.hubTile("CludesAmp", "Knobs & Harmônicos", () -> selectStudioTab[2].run()), host.hubTileParams(true));
        gridRow2.addView(host.hubTile("Saída DAC", "Signal Path & A/B", () -> selectStudioTab[3].run()), host.hubTileParams(false));

        homePanel.addView(host.hubRow("♫  Biblioteca de Músicas", "Artistas, álbuns, pastas e Mais Ouvidas", () -> showRootTab[1].run()));

        LinearLayout appearanceCard = host.card();
        homePanel.addView(appearanceCard);
        appearance.build(appearanceCard);

        // --- Live Observers to Sync Mini-Player, Album Theme & Tech Header Automatically ---
        TextWatcher titleSync = new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                if (host.miniTitle != null) host.miniTitle.setText(s);
                host.playerUi.refreshMiniVisibility();
                Bitmap cover = PlaybackService.isJamGuest()
                    ? PlaybackService.getCurrentArtwork()
                    : (host.currentIndex >= 0 && host.currentIndex < host.queue.size()
                        ? host.queue.get(host.currentIndex).artwork : null);
                if (cover == null) cover = PlaybackService.getCurrentArtwork();
                host.playerUi.syncArtworkTheme(cover);
            }
        };
        host.title.addTextChangedListener(titleSync);

        host.artist.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                if (host.miniArtist != null) host.miniArtist.setText(s);
            }
        });

        host.playButton.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                if (host.miniPlayButton != null) {
                    host.miniPlayButton.setText(s);
                    boolean isPlayingNow = host.playing || (host.player != null && host.player.isRunning() && !host.player.isPaused());
                    MaterialUi.buttonState(host.miniPlayButton, isPlayingNow);
                }
                host.playerUi.refreshMiniVisibility();
            }
        });

        host.outputRoute.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                host.playerUi.updateTechHeader();
            }
        });

        // Start safely on Player tab
        host.playerUi.syncArtworkTheme(null);
        showRootTab[0].run();
    }

    private void showAutoMixSettingsDialog(TextView autoMixBadge) {
        LinearLayout box = host.column();
        box.setPadding(host.dp(20), host.dp(12), host.dp(20), host.dp(8));

        String scopeName = PlaybackService.getActiveScopeName();
        String scopeText = (scopeName == null || scopeName.isEmpty())
            ? "Escopo ativo: Biblioteca Completa (todas as músicas)"
            : "Escopo ativo: " + scopeName + " (" + PlaybackService.getActiveScopeSize() + " faixas)";
        TextView scopeView = host.label(box, scopeText, 11.5f, MainActivity.TECH_CYAN, true);
        scopeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        host.space(box, 8);

        com.google.android.material.materialswitch.MaterialSwitch crossfadeSwitch =
            new com.google.android.material.materialswitch.MaterialSwitch(host);
        crossfadeSwitch.setText("Crossfade DJ (misturar entrada/saída)");
        crossfadeSwitch.setTextColor(host.TEXT);
        crossfadeSwitch.setChecked(PlaybackService.isCrossfadeEnabled());
        box.addView(crossfadeSwitch);

        host.label(box, "Desative o Crossfade se quiser apenas a seleção harmônica inteligente de músicas (troca seca no Cue-Point).", 11, host.MUTED, false);
        host.space(box, 10);

        String[] durLabels = {
            "Automático (alinhado aos compassos BPM)",
            "Curto · 2.0 segundos",
            "Médio · 3.5 segundos",
            "Longo · 5.0 segundos",
            "Club Mix · 6.5 segundos"
        };
        int[] durValues = {0, 2000, 3500, 5000, 6500};
        int curDur = PlaybackService.getCrossfadeOverrideMs();
        int durIdx = 0;
        for (int i = 0; i < durValues.length; i++) {
            if (durValues[i] == curDur) { durIdx = i; break; }
        }
        AudioDropdown durPicker = new AudioDropdown(host, "Duração do Crossfade", durLabels);
        durPicker.setSelection(durIdx);
        box.addView(durPicker);

        host.space(box, 8);

        String[] fxLabels = {
            "Automático DJ (escolhe conforme BPM e vocais)",
            "Bass-Swap Blend (troca limpa de sub-graves)",
            "Filter Sweep (varredura High-Pass / Low-Pass)",
            "Dub Echo Freeze (cauda de eco espacial)",
            "Power Cut (corte rápido para vocais densos)"
        };
        int[] fxValues = {-1, 0, 1, 2, 3};
        int curFx = PlaybackService.getForcedTransitionStyle();
        int fxIdx = 0;
        for (int i = 0; i < fxValues.length; i++) {
            if (fxValues[i] == curFx) { fxIdx = i; break; }
        }
        AudioDropdown fxPicker = new AudioDropdown(host, "Estilo de Transição DSP", fxLabels);
        fxPicker.setSelection(fxIdx);
        box.addView(fxPicker);

        host.space(box, 10);

        com.google.android.material.materialswitch.MaterialSwitch considerTagsSwitch =
            new com.google.android.material.materialswitch.MaterialSwitch(host);
        considerTagsSwitch.setText("Considerar Tags no AutoMix (Agregador de Estilo/Vibe)");
        considerTagsSwitch.setTextColor(host.TEXT);
        considerTagsSwitch.setChecked(PlaybackService.isConsiderTagsInAutoMix());
        box.addView(considerTagsSwitch);
        host.label(box, "Usa suas tags (Phonk, Trap, Love Song e personalizadas) + a Chance % configurada em cada tag para guiar o AutoMix.", 11, host.MUTED, false);

        host.space(box, 8);

        ArrayList<TrackTagDatabase.TagDefinition> allDefs = TrackTagDatabase.get(host).getAllDefinitions();
        String[] tagFilterLabels = new String[allDefs.size() + 1];
        String[] tagFilterValues = new String[allDefs.size() + 1];
        tagFilterLabels[0] = "Automático · Seguir as Tags da música atual";
        tagFilterValues[0] = "";
        int selectedTagIdx = 0;
        String activeTargetTag = PlaybackService.getTargetAutoMixTag();
        for (int i = 0; i < allDefs.size(); i++) {
            TrackTagDatabase.TagDefinition def = allDefs.get(i);
            tagFilterLabels[i + 1] = "Focar em: 🏷 " + def.name + " (Chance " + def.autoMixChancePct + "%)";
            tagFilterValues[i + 1] = def.name;
            if (def.name.equalsIgnoreCase(activeTargetTag)) {
                selectedTagIdx = i + 1;
            }
        }
        AudioDropdown tagFilterPicker = new AudioDropdown(host, "Foco de Tag no AutoMix", tagFilterLabels);
        tagFilterPicker.setSelection(selectedTagIdx);
        box.addView(tagFilterPicker);

        considerTagsSwitch.setOnCheckedChangeListener((btn, on) -> {
            PlaybackService.setConsiderTagsInAutoMix(on);
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("autoMixConsiderTags", on).apply();
        });
        tagFilterPicker.setOnSelection(pos -> {
            String chosen = tagFilterValues[Math.max(0, Math.min(tagFilterValues.length - 1, pos))];
            PlaybackService.setTargetAutoMixTag(chosen);
            host.getPreferences(MODE_PRIVATE).edit().putString("autoMixTargetTag", chosen).apply();
        });

        crossfadeSwitch.setOnCheckedChangeListener((btn, on) -> {
            PlaybackService.setCrossfadeEnabled(on);
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("autoMixCrossfade", on).apply();
            if (host.autoMixToggle != null && host.autoMixToggle.isChecked()) {
                autoMixBadge.setText(on ? "CROSSFADE" : "SECO");
            }
        });
        durPicker.setOnSelection(pos -> {
            int ms = durValues[Math.max(0, Math.min(durValues.length - 1, pos))];
            PlaybackService.setCrossfadeOverrideMs(ms);
            host.getPreferences(MODE_PRIVATE).edit().putInt("autoMixFadeMs", ms).apply();
        });
        fxPicker.setOnSelection(pos -> {
            int style = fxValues[Math.max(0, Math.min(fxValues.length - 1, pos))];
            PlaybackService.setForcedTransitionStyle(style);
            host.getPreferences(MODE_PRIVATE).edit().putInt("autoMixFxStyle", style).apply();
        });

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
            .setTitle("Configurações do AutoMix")
            .setView(box)
            .setNeutralButton("Usar Biblioteca Toda", (d, w) -> {
                PlaybackService.setPlaybackScope(null, null);
                host.status.setText("AutoMix & fila agora usam toda a biblioteca");
            })
            .setPositiveButton("Concluído", null)
            .show();
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    }
    private Button action(String label, int icon) {
        com.google.android.material.button.MaterialButton button =
            (com.google.android.material.button.MaterialButton) host.pill(label, false);
        button.setIconResource(icon);
        button.setIconSize(host.dp(17));
        button.setIconPadding(host.dp(5));
        button.setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
        return button;
    }
}
