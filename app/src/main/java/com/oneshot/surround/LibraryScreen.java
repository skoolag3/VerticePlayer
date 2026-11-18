package com.oneshot.surround;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Builds the libraryPanel controls with a virtualized RecyclerView for 60 FPS scrolling across 900+ tracks. */
final class LibraryScreen {
    private final MainActivity host;

    LibraryScreen(MainActivity host) {
        this.host = host;
    }

    void build(LinearLayout libraryPanel) {
        LinearLayout queueHeader = host.row();
        queueHeader.setGravity(Gravity.CENTER_VERTICAL);
        libraryPanel.addView(queueHeader);
        host.label(queueHeader, "Biblioteca", 24, host.TEXT, true);
        queueHeader.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        host.queueCount = host.label(queueHeader, "0 faixas", 12, host.MUTED, false);
        host.queueCount.setPadding(0, 0, host.dp(10), 0);
        Button librarySettings = host.pill("⚙", false);
        librarySettings.setTextSize(16);
        librarySettings.setPadding(0, 0, 0, 0);
        queueHeader.addView(librarySettings, new LinearLayout.LayoutParams(host.dp(40), host.dp(36)));
        librarySettings.setContentDescription("Configurar pastas da biblioteca");
        librarySettings.setOnClickListener(view -> host.showLibrarySettings());

        // Source Switcher: Separates Local Library from Spotify Search
        LinearLayout sourceSwitchRow = host.row();
        libraryPanel.addView(sourceSwitchRow);
        Button localSourceBtn = host.pill("📁 Biblioteca Local", true);
        Button spotifySourceBtn = host.pill("🟢 Pesquisar Spotify", false);
        localSourceBtn.setTextSize(11.5f);
        spotifySourceBtn.setTextSize(11.5f);
        sourceSwitchRow.addView(localSourceBtn, host.weighted());
        sourceSwitchRow.addView(spotifySourceBtn, host.weighted());

        LinearLayout localContainer = host.column();
        libraryPanel.addView(localContainer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout spotifyContainer = host.column();
        spotifyContainer.setVisibility(View.GONE);
        libraryPanel.addView(spotifyContainer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        localSourceBtn.setOnClickListener(v -> {
            MaterialUi.buttonState(localSourceBtn, true);
            MaterialUi.buttonState(spotifySourceBtn, false);
            localContainer.setVisibility(View.VISIBLE);
            spotifyContainer.setVisibility(View.GONE);
        });
        spotifySourceBtn.setOnClickListener(v -> {
            MaterialUi.buttonState(localSourceBtn, false);
            MaterialUi.buttonState(spotifySourceBtn, true);
            localContainer.setVisibility(View.GONE);
            spotifyContainer.setVisibility(View.VISIBLE);
        });

        buildSpotifySearchSection(spotifyContainer);

        MaterialButtonToggleGroup categories = new MaterialButtonToggleGroup(host);
        categories.setSingleSelection(true);
        categories.setSelectionRequired(true);
        localContainer.addView(categories);
        String[] views = {"Artistas", "Músicas", "Álbuns", "Tags/List"};
        host.libraryTabs = new Button[4];
        for (int index = 0; index < views.length; index++) {
            int category = index;
            Button button = host.pill(views[index], index == host.libraryView);
            button.setId(View.generateViewId());
            ((MaterialButton) button).setCheckable(true);
            button.setTextSize(11.5f);
            button.setPadding(host.dp(4), 0, host.dp(4), 0);
            categories.addView(button, host.weighted());
            host.libraryTabs[index] = button;
            button.setOnClickListener(view -> {
                host.libraryView = category;
                host.selectedGroup = null;
                host.collectionFilter = null;
                host.renderQueue();
            });
        }

        TextInputLayout searchBox = new TextInputLayout(host);
        searchBox.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        searchBox.setHint("Buscar músicas, artistas, álbuns ou #tag");
        searchBox.setStartIconDrawable(R.drawable.ic_search);
        searchBox.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        host.librarySearch = new TextInputEditText(searchBox.getContext());
        host.librarySearch.setSingleLine(true);
        host.librarySearch.setTextColor(host.TEXT);
        host.librarySearch.setHintTextColor(host.MUTED);
        searchBox.addView(host.librarySearch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLayout.topMargin = host.dp(8);
        localContainer.addView(searchBox, searchLayout);
        host.librarySearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) { host.renderQueue(); }
            public void afterTextChanged(Editable text) { }
        });

        LinearLayout shortcuts = host.row();
        localContainer.addView(shortcuts);
        Button untagged = host.pill("🏷 Sem Tag", false);
        Button favorites = host.pill("♡ Favoritas", false);
        Button recent = host.pill("◷ Recentes", false);
        Button mostPlayed = host.pill("★ Top", false);
        untagged.setTextSize(10.5f); untagged.setPadding(host.dp(4), 0, host.dp(4), 0);
        favorites.setTextSize(10.5f); favorites.setPadding(host.dp(4), 0, host.dp(4), 0);
        recent.setTextSize(10.5f); recent.setPadding(host.dp(4), 0, host.dp(4), 0);
        mostPlayed.setTextSize(10.5f); mostPlayed.setPadding(host.dp(4), 0, host.dp(4), 0);
        shortcuts.addView(untagged, host.weighted());
        shortcuts.addView(favorites, host.weighted());
        shortcuts.addView(recent, host.weighted());
        shortcuts.addView(mostPlayed, host.weighted());

        untagged.setOnClickListener(v -> { host.libraryView = 1; host.selectedGroup = null; host.collectionFilter = "untagged"; host.renderQueue(); });
        favorites.setOnClickListener(v -> { host.libraryView = 1; host.selectedGroup = null; host.collectionFilter = "favorites"; host.renderQueue(); });
        recent.setOnClickListener(v -> { host.libraryView = 1; host.selectedGroup = null; host.collectionFilter = "recent"; host.renderQueue(); });
        mostPlayed.setOnClickListener(v -> { host.libraryView = 1; host.selectedGroup = null; host.collectionFilter = "mostPlayed"; host.renderQueue(); });

        host.libraryHighlights = host.column();
        localContainer.addView(host.libraryHighlights);
        host.label(host.libraryHighlights, "Álbuns da biblioteca", 15, host.TEXT, true);
        android.widget.HorizontalScrollView albumScroll = new android.widget.HorizontalScrollView(host);
        albumScroll.setHorizontalScrollBarEnabled(false);
        host.albumShelf = host.row();
        albumScroll.addView(host.albumShelf);
        host.libraryHighlights.addView(albumScroll);

        host.alphabetIndexView = host.pill("Ir para letra   A–Z", false);
        host.alphabetIndexView.setVisibility(View.GONE);
        localContainer.addView(host.alphabetIndexView);
        host.alphabetIndexView.setOnClickListener(v -> host.libraryRenderer.chooseLetter());

        host.libraryRecycler = new RecyclerView(host);
        host.libraryRecycler.setLayoutManager(new LinearLayoutManager(host));
        host.libraryRecycler.setItemAnimator(null);
        host.libraryRecycler.setHasFixedSize(false);
        host.libraryRecycler.setClipToPadding(false);
        host.libraryRecycler.setPadding(0, host.dp(4), 0, host.dp(16));
        localContainer.addView(host.libraryRecycler, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Legacy fallback container kept non-null if anything references it
        host.queueItems = host.column();
        host.queueItems.setVisibility(View.GONE);
        localContainer.addView(host.queueItems);
    }

    private void buildSpotifySearchSection(LinearLayout container) {
        host.space(container, 6);
        LinearLayout topBar = host.row();
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(topBar);
        android.widget.EditText queryInput = new android.widget.EditText(host);
        queryInput.setHint("Pesquisar música ou artista no Spotify...");
        queryInput.setSingleLine(true);
        queryInput.setTextColor(host.TEXT);
        queryInput.setHintTextColor(host.MUTED);
        queryInput.setTextSize(13f);
        topBar.addView(queryInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button searchBtn = host.pill("Buscar", true);
        searchBtn.setTextSize(11.5f);
        topBar.addView(searchBtn, new LinearLayout.LayoutParams(host.dp(88), host.dp(40)));

        Button openBridgeBtn = host.pill("🌐 Painel / Web Player Spotify", false);
        openBridgeBtn.setTextSize(11f);
        container.addView(openBridgeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(38)));
        openBridgeBtn.setOnClickListener(v -> SpotifyBridgeScreen.open(host));

        android.widget.ScrollView resultsScroll = new android.widget.ScrollView(host);
        LinearLayout resultsCol = host.column();
        resultsScroll.addView(resultsCol);
        container.addView(resultsScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        host.label(resultsCol, "Digite qualquer música ou artista acima para buscar no catálogo do Spotify, separado das suas pastas locais.", 12f, host.MUTED, false);

        searchBtn.setOnClickListener(v -> {
            String q = queryInput.getText().toString().trim();
            if (q.isEmpty()) return;
            searchBtn.setText("...");
            resultsCol.removeAllViews();
            SpotifyLiveBridge.searchSpotifyCatalogAsync(host, q, items -> {
                searchBtn.setText("Buscar");
                resultsCol.removeAllViews();
                if (items.isEmpty()) {
                    host.label(resultsCol, "Nenhum resultado encontrado para \"" + q + "\".", 12f, host.MUTED, false);
                    return;
                }
                for (SpotifyLiveBridge.SpotifySearchItem item : items) {
                    LinearLayout card = host.card();
                    LinearLayout row = host.row();
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    card.addView(row);

                    LinearLayout info = host.column();
                    row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    host.label(info, item.title, 13.5f, host.TEXT, true);
                    String meta = item.artist
                        + (!item.album.isEmpty() ? " • " + item.album : "")
                        + (item.durationMs > 0 ? " • " + host.formatTime(item.durationMs) : "");
                    host.label(info, meta, 11.5f, host.MUTED, false);

                    Button playSpot = host.pill("🟢 Tocar", true);
                    playSpot.setTextSize(11f);
                    row.addView(playSpot, new LinearLayout.LayoutParams(host.dp(88), host.dp(36)));
                    playSpot.setOnClickListener(b -> SpotifyLiveBridge.playSearchItemInSpotify(host, item));
                    resultsCol.addView(card);
                }
            });
        });
    }
}
