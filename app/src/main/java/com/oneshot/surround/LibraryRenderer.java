package com.oneshot.surround;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Virtualized 60 FPS Library Renderer powered by RecyclerView:
 * - Renders 900+ tracks, Artists, Albums, Tags, and Playlists with zero UI lag.
 * - Includes dedicated "Sem Tag" section + one-tap Batch Auto-Tagger (Embedded ID3 + iTunes API + Local Heuristics).
 * - Preserves A-Z fast jumping, clean primaryArtist grouping, unified albums, and scoped AutoMix context.
 */
final class LibraryRenderer {
    private final MainActivity host;
    private final Map<Character, Integer> letterPositions = new HashMap<>();
    private final ArrayList<Integer> currentContextIndices = new ArrayList<>();
    private String currentContextName = "";
    private LibraryRecyclerAdapter adapter;
    private TextView autoTagProgressLabel;

    LibraryRenderer(MainActivity host) {
        this.host = host;
    }

    static char normalizeInitial(String text) {
        if (text == null) return '#';
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return '#';
        String deaccent = Normalizer.normalize(trimmed.substring(0, 1), Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .toUpperCase(Locale.ROOT);
        if (deaccent.isEmpty()) return '#';
        char c = deaccent.charAt(0);
        return (c >= 'A' && c <= 'Z') ? c : '#';
    }

    private LibraryRecyclerAdapter ensureAdapter() {
        if (adapter == null) {
            adapter = new LibraryRecyclerAdapter(host, new LibraryRecyclerAdapter.Callbacks() {
                @Override
                public void onTrackClick(int trackIndex) {
                    if (!currentContextIndices.isEmpty()) {
                        PlaybackService.setPlaybackScope(currentContextIndices, currentContextName);
                    } else {
                        PlaybackService.setPlaybackScope(null, null);
                    }
                    host.playTrack(trackIndex);
                }

                @Override
                public void onTrackTagClick(TrackInfo track) {
                    TagDialogHelper.showTrackTagSheet(host, track, () -> {
                        host.refreshFavorite();
                        renderQueue();
                    });
                }

                @Override
                public void onGroupClick(String groupKey) {
                    host.selectedGroup = groupKey;
                    renderQueue();
                }
            });
            host.libraryRecycler.setAdapter(adapter);
        }
        return adapter;
    }

    void chooseLetter() {
        if (letterPositions.isEmpty()) return;
        String[] letters = new java.util.TreeSet<>(letterPositions.keySet()).stream()
            .map(String::valueOf).toArray(String[]::new);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
            .setTitle("Ir para letra")
            .setItems(letters, (dialog, index) -> scrollToLetter(letters[index].charAt(0)))
            .show();
    }

    void scrollToLetter(char letter) {
        if (host.libraryRecycler == null || letterPositions.isEmpty()) return;
        Integer pos = letterPositions.get(letter);
        if (pos == null) {
            for (char c = letter; c >= 'A'; c--) {
                if (letterPositions.containsKey(c)) { pos = letterPositions.get(c); break; }
            }
            if (pos == null) {
                for (char c = letter; c <= 'Z'; c++) {
                    if (letterPositions.containsKey(c)) { pos = letterPositions.get(c); break; }
                }
            }
        }
        if (pos == null) return;
        if (host.libraryRecycler.getLayoutManager() instanceof LinearLayoutManager) {
            ((LinearLayoutManager) host.libraryRecycler.getLayoutManager()).scrollToPositionWithOffset(pos, 0);
        } else {
            host.libraryRecycler.scrollToPosition(pos);
        }
    }

    void renderQueue() {
        if (host.libraryRecycler == null) return;
        LibraryRecyclerAdapter listAdapter = ensureAdapter();
        ArrayList<LibraryRecyclerAdapter.Item> items = new ArrayList<>();
        letterPositions.clear();
        currentContextIndices.clear();
        currentContextName = "";

        String query = host.librarySearch == null ? "" : host.librarySearch.getText().toString().toLowerCase(Locale.ROOT).trim();

        boolean highlights = host.libraryView == 1 && host.collectionFilter == null
            && query.isEmpty() && !host.queue.isEmpty();
        if (host.libraryHighlights != null) {
            host.libraryHighlights.setVisibility(highlights ? View.VISIBLE : View.GONE);
            if (highlights && host.albumShelf != null) {
                host.albumShelf.removeAllViews();
                HashSet<String> albums = new HashSet<>();
                for (TrackInfo track : host.queue) {
                    String key = albumGroupKey(track);
                    if (!albums.add(key)) continue;
                    LinearLayout album = host.column();
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(host.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT);
                    size.setMargins(0, host.dp(6), host.dp(10), host.dp(8));
                    host.albumShelf.addView(album, size);
                    ImageView cover = new ImageView(host);
                    cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    cover.setBackground(host.background(host.SURFACE, 10));
                    cover.setClipToOutline(true);
                    album.addView(cover, new LinearLayout.LayoutParams(host.dp(96), host.dp(96)));
                    bindShelfCover(cover, track);
                    TextView name = host.label(album, cleanAlbumTitle(track.album), 11, host.TEXT, true);
                    name.setSingleLine(true);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    TextView by = host.label(album, host.primaryArtist(track.artist), 10, host.MUTED, false);
                    by.setSingleLine(true);
                    album.setOnClickListener(v -> {
                        host.libraryView = 2;
                        host.selectedGroup = key;
                        host.collectionFilter = null;
                        renderQueue();
                    });
                    if (albums.size() >= 10) break;
                }
            }
        }

        host.queueCount.setText(host.queue.size() + (host.queue.size() == 1 ? " faixa" : " faixas"));
        if (host.libraryTabs != null) {
            for (int index = 0; index < host.libraryTabs.length; index++) {
                MaterialUi.buttonState(host.libraryTabs[index], index == host.libraryView);
                ((com.google.android.material.button.MaterialButton) host.libraryTabs[index]).setChecked(index == host.libraryView);
            }
        }

        if (host.queue.isEmpty()) {
            if (host.alphabetIndexView != null) host.alphabetIndexView.setVisibility(View.GONE);
            items.add(LibraryRecyclerAdapter.Item.headerView(
                makeNoteView("Biblioteca vazia. Use ⚙ para adicionar uma pasta raiz.")));
            listAdapter.submitList(items);
            return;
        }

        TrackTagDatabase tagDb = TrackTagDatabase.get(host);

        // Tab 3 (Tags & Playlists overview)
        if (host.libraryView == 3 && host.collectionFilter == null) {
            if (host.alphabetIndexView != null) host.alphabetIndexView.setVisibility(View.GONE);
            LinearLayout container = host.column();

            // Count untagged tracks
            int untaggedCount = 0;
            for (TrackInfo t : host.queue) {
                if (tagDb.getTagsForTrack(t.uri.toString()).isEmpty()) untaggedCount++;
            }

            // Auto-Tagger Card for 900+ tracks
            container.addView(buildAutoTaggerCard(untaggedCount, false));

            // Untagged filter card
            LinearLayout untaggedCard = host.card();
            container.addView(untaggedCard);
            LinearLayout uRow = host.row();
            uRow.setGravity(Gravity.CENTER_VERTICAL);
            untaggedCard.addView(uRow);
            host.label(uRow, "🏷  Músicas Sem Tag", 15, MainActivity.SIGNAL_WARN, true);
            uRow.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
            host.playerUi.monoBadge(uRow, untaggedCount + " faixas", MainActivity.SIGNAL_WARN);
            host.label(untaggedCard, "Veja apenas as músicas que ainda não têm nenhuma tag para organizar ou auto-taguear.", 11.5f, host.MUTED, false);
            untaggedCard.setOnClickListener(v -> {
                host.libraryView = 1;
                host.collectionFilter = "untagged";
                renderQueue();
            });

            LinearLayout topActions = host.row();
            container.addView(topActions);
            Button create = host.pill("＋ Criar playlist", false);
            Button createTag = host.pill("🏷 Criar tag", true);
            topActions.addView(create, host.weighted());
            topActions.addView(createTag, host.weighted());
            create.setOnClickListener(v -> createPlaylist(null));
            createTag.setOnClickListener(v -> TagDialogHelper.showCreateOrEditTagDialog(host, null, this::renderQueue));

            ArrayList<TrackInfo> topTracks = PlaybackService.getMostPlayedTracks(host, host.queue);
            LinearLayout autoCard = host.card();
            container.addView(autoCard);
            host.label(autoCard, "★  Mais Ouvidas (Automática)", 16, host.ACCENT, true);
            host.label(autoCard, topTracks.isEmpty()
                ? "Ouça músicas até o fim para preencher esta playlist"
                : topTracks.size() + (topTracks.size() == 1 ? " faixa mais ouvida" : " faixas ordenadas por plays"),
                12, host.MUTED, false);
            autoCard.setOnClickListener(v -> {
                host.collectionFilter = "mostPlayed";
                renderQueue();
            });

            host.space(container, 6);
            host.label(container, "TAGS & ESTILOS DO AUTOMIX", 11, MainActivity.TECH_CYAN, true);
            for (TrackTagDatabase.TagDefinition def : tagDb.getAllDefinitions()) {
                int tagCount = tagDb.countTracksWithTag(def.name, host.queue);
                LinearLayout tagCard = host.card();
                container.addView(tagCard);
                LinearLayout headerRow = host.row();
                headerRow.setGravity(Gravity.CENTER_VERTICAL);
                tagCard.addView(headerRow);
                host.label(headerRow, "🏷  " + def.name, 15, def.color, true);
                headerRow.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
                host.playerUi.monoBadge(headerRow, "AutoMix " + def.autoMixChancePct + "%", def.color);
                host.label(tagCard, tagCount + (tagCount == 1 ? " música marcada" : " músicas marcadas")
                    + (def.strictCluster ? " · Agrupamento Forte" : " · Mix Livre")
                    + " · Segure para configurar", 11.5f, host.MUTED, false);
                tagCard.setOnClickListener(v -> {
                    host.libraryView = 1;
                    host.collectionFilter = "tag:" + def.name;
                    renderQueue();
                });
                tagCard.setOnLongClickListener(v -> {
                    TagDialogHelper.showCreateOrEditTagDialog(host, def, this::renderQueue);
                    return true;
                });
            }

            host.space(container, 6);
            host.label(container, "PLAYLISTS MANUAIS", 11, MainActivity.ACCENT, true);
            for (String name : host.collections.names()) {
                LinearLayout item = host.card();
                container.addView(item);
                host.label(item, "♫  " + name, 16, host.TEXT, true);
                host.label(item, host.collections.tracks(name).size() + " faixas", 12, host.MUTED, false);
                item.setOnClickListener(v -> {
                    host.libraryView = 1;
                    host.collectionFilter = "playlist:" + name;
                    renderQueue();
                });
            }

            items.add(LibraryRecyclerAdapter.Item.headerView(container));
            listAdapter.submitList(items);
            return;
        }

        if (host.collectionFilter != null) {
            String labelName = host.collectionFilter.startsWith("playlist:") ? host.collectionFilter.substring(9)
                : host.collectionFilter.startsWith("tag:") ? ("Tag: " + host.collectionFilter.substring(4))
                : host.collectionFilter.equals("untagged") ? "Músicas Sem Tag"
                : host.collectionFilter.equals("favorites") ? "Favoritas"
                : host.collectionFilter.equals("mostPlayed") ? "Mais ouvidas" : "Recentes";
            currentContextName = labelName;
            Button back = host.pill("‹  " + labelName + " · ver tudo", false);
            back.setOnClickListener(v -> {
                host.collectionFilter = null;
                renderQueue();
            });
            items.add(LibraryRecyclerAdapter.Item.headerView(back));
        }

        if ("mostPlayed".equals(host.collectionFilter)) {
            if (host.alphabetIndexView != null) host.alphabetIndexView.setVisibility(View.GONE);
            ArrayList<TrackInfo> topTracks = PlaybackService.getMostPlayedTracks(host, host.queue);
            IdentityHashMap<TrackInfo, Integer> indices = new IdentityHashMap<>();
            for (int i = 0; i < host.queue.size(); i++) indices.put(host.queue.get(i), i);
            for (TrackInfo track : topTracks) {
                if (!query.isEmpty() && !track.title.toLowerCase(Locale.ROOT).contains(query)
                    && !track.artist.toLowerCase(Locale.ROOT).contains(query)
                    && !track.album.toLowerCase(Locale.ROOT).contains(query)) continue;
                Integer idx = indices.get(track);
                if (idx == null) continue;
                currentContextIndices.add(idx);
                int plays = PlaybackService.getPlayCount(host, track.uri);
                items.add(LibraryRecyclerAdapter.Item.track(idx, track, track.artist + " · " + plays + (plays == 1 ? " play" : " plays")));
            }
            if (currentContextIndices.isEmpty()) {
                items.add(LibraryRecyclerAdapter.Item.headerView(makeNoteView(
                    topTracks.isEmpty() ? "Nenhuma faixa completada ainda. Ouça músicas para gerar o ranking." : "Nenhuma faixa corresponde à busca.")));
            }
            listAdapter.submitList(items);
            return;
        }

        // Tab 0 (Artistas) or Tab 2 (Álbuns) group list — virtualized in RecyclerView!
        if (host.libraryView != 1 && host.libraryView != 3 && host.selectedGroup == null) {
            TreeMap<String, ArrayList<Integer>> groups = new TreeMap<>();
            for (int index = 0; index < host.queue.size(); index++) {
                groups.computeIfAbsent(groupKey(host.queue.get(index)), key -> new ArrayList<>()).add(index);
            }
            int displayedGroups = 0;
            for (Map.Entry<String, ArrayList<Integer>> entry : groups.entrySet()) {
                ArrayList<Integer> memberIndices = entry.getValue();
                TrackInfo example = host.queue.get(memberIndices.get(0));
                String name = host.libraryView == 0 ? primaryArtist(example.artist) : cleanAlbumTitle(example.album);
                String subtitle = host.libraryView == 0
                    ? memberIndices.size() + (memberIndices.size() == 1 ? " música" : " músicas")
                    : primaryArtist(example.artist) + " · " + memberIndices.size() + (memberIndices.size() == 1 ? " música" : " músicas");
                if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)
                    && !subtitle.toLowerCase(Locale.ROOT).contains(query)) continue;

                char initial = normalizeInitial(name);
                if (!letterPositions.containsKey(initial)) {
                    letterPositions.put(initial, items.size());
                }
                items.add(LibraryRecyclerAdapter.Item.group(name, subtitle, entry.getKey(), example));
                displayedGroups++;
            }
            if (host.alphabetIndexView != null) {
                host.alphabetIndexView.setVisibility(displayedGroups > 12 && letterPositions.size() > 1 ? View.VISIBLE : View.GONE);
            }
            if (displayedGroups == 0) {
                items.add(LibraryRecyclerAdapter.Item.headerView(makeNoteView("Nenhum resultado.")));
            }
            listAdapter.submitList(items);
            return;
        }

        if (host.selectedGroup != null) {
            Button back = host.pill("‹  Voltar para " + (host.libraryView == 0 ? "artistas" : "álbuns"), false);
            back.setOnClickListener(view -> {
                host.selectedGroup = null;
                renderQueue();
            });
            items.add(LibraryRecyclerAdapter.Item.headerView(back));
        }

        // Collect all matching tracks
        ArrayList<Integer> matchedIndices = new ArrayList<>(host.queue.size());
        Set<String> allowedSet = null;
        String activeTagFilter = null;
        boolean onlyUntagged = "untagged".equals(host.collectionFilter);
        if (host.collectionFilter != null && !onlyUntagged) {
            if (host.collectionFilter.startsWith("tag:")) {
                activeTagFilter = host.collectionFilter.substring(4);
            } else {
                allowedSet = host.collectionFilter.equals("favorites") ? host.collections.favorites()
                    : host.collectionFilter.equals("recent") ? host.collections.recent()
                    : host.collections.tracks(host.collectionFilter.substring(9));
            }
        }

        for (int index = 0; index < host.queue.size(); index++) {
            TrackInfo track = host.queue.get(index);
            String uriStr = track.uri.toString();
            if (onlyUntagged && !tagDb.getTagsForTrack(uriStr).isEmpty()) continue;
            if (activeTagFilter != null && !tagDb.hasTag(uriStr, activeTagFilter)) continue;
            if (allowedSet != null && !allowedSet.contains(uriStr)) continue;
            if (host.selectedGroup != null && !host.selectedGroup.equals(groupKey(track))) continue;
            if (!query.isEmpty()) {
                String tagBadge = tagDb.formatTrackTagsBadge(uriStr).toLowerCase(Locale.ROOT);
                if (!track.title.toLowerCase(Locale.ROOT).contains(query)
                    && !track.artist.toLowerCase(Locale.ROOT).contains(query)
                    && !track.album.toLowerCase(Locale.ROOT).contains(query)
                    && !tagBadge.contains(query)) continue;
            }
            matchedIndices.add(index);
        }

        if (onlyUntagged) {
            items.add(LibraryRecyclerAdapter.Item.headerView(buildAutoTaggerCard(matchedIndices.size(), true)));
        }

        // Sort alphabetically by title when viewing All Tracks (or Untagged) so A-Z jumping is exact
        if (host.selectedGroup == null && (host.collectionFilter == null || onlyUntagged)) {
            Collections.sort(matchedIndices, (a, b) -> {
                String ta = host.queue.get(a).title == null ? "" : host.queue.get(a).title;
                String tb = host.queue.get(b).title == null ? "" : host.queue.get(b).title;
                return ta.compareToIgnoreCase(tb);
            });
        }

        boolean scopedContext = (host.selectedGroup != null || host.collectionFilter != null);
        if (scopedContext && !onlyUntagged) {
            currentContextIndices.addAll(matchedIndices);
            if (host.selectedGroup != null && !matchedIndices.isEmpty()) {
                TrackInfo first = host.queue.get(matchedIndices.get(0));
                currentContextName = host.libraryView == 0
                    ? ("Artista: " + primaryArtist(first.artist))
                    : ("Álbum: " + cleanAlbumTitle(first.album));
            }
        }

        if (host.selectedGroup != null && !matchedIndices.isEmpty()) {
            TrackInfo first = host.queue.get(matchedIndices.get(0));
            LinearLayout groupHeader = host.column();
            if (host.libraryView == 2) {
                ImageView artwork = new ImageView(host);
                artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
                bindShelfCover(artwork, first);
                groupHeader.addView(artwork, new LinearLayout.LayoutParams(-1, host.dp(140)));
            }
            host.label(groupHeader, host.libraryView == 2 ? cleanAlbumTitle(first.album)
                : primaryArtist(first.artist), 20, host.TEXT, true);
            items.add(LibraryRecyclerAdapter.Item.headerView(groupHeader));
        }

        for (int index : matchedIndices) {
            TrackInfo track = host.queue.get(index);
            char initial = normalizeInitial(track.title);
            if (!letterPositions.containsKey(initial)) {
                letterPositions.put(initial, items.size());
            }
            items.add(LibraryRecyclerAdapter.Item.track(index, track, track.artist));
        }

        if (host.alphabetIndexView != null) {
            host.alphabetIndexView.setVisibility(matchedIndices.size() > 12 && !scopedContext && query.isEmpty() ? View.VISIBLE : View.GONE);
        }

        if (matchedIndices.isEmpty()) {
            items.add(LibraryRecyclerAdapter.Item.headerView(makeNoteView(
                onlyUntagged ? "🎉 Todas as suas músicas já possuem tags!" : "Nenhuma faixa corresponde à busca.")));
        } else {
            items.add(LibraryRecyclerAdapter.Item.headerView(makeNoteView(
                matchedIndices.size() + (matchedIndices.size() == 1 ? " música listada" : " músicas listadas")
                    + (scopedContext && !onlyUntagged ? " · AutoMix & fila focados neste contexto" : ""))));
        }

        listAdapter.submitList(items);
    }

    private LinearLayout buildAutoTaggerCard(int untaggedCount, boolean inUntaggedView) {
        LinearLayout card = host.card();
        LinearLayout top = host.row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top);
        TextView tag = host.label(top, "AUTO-TAGGER · API + ID3", 10.5f, MainActivity.TECH_CYAN, true);
        tag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        top.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        host.playerUi.monoBadge(top, untaggedCount + " sem tag", untaggedCount > 0 ? MainActivity.SIGNAL_WARN : MainActivity.SIGNAL_OK);

        host.label(card, "Classificar Biblioteca Automaticamente", 16, host.TEXT, true);
        autoTagProgressLabel = host.label(card,
            AutoTaggerService.isRunning()
                ? "Auto-Tagger rodando em segundo plano..."
                : "Lê o gênero embutido no arquivo + consulta a iTunes Search API (grátis) para taguear centenas de músicas de uma vez.",
            11.5f, host.MUTED, false);

        host.space(card, 8);
        LinearLayout btnRow = host.row();
        card.addView(btnRow);

        Button runBtn = host.pill(AutoTaggerService.isRunning() ? "⏹ Parar Auto-Tagger" : "✨ Auto-Taguear Sem Tag (" + untaggedCount + ")", true);
        runBtn.setTextSize(11.5f);
        btnRow.addView(runBtn, host.weighted());

        runBtn.setOnClickListener(v -> {
            if (AutoTaggerService.isRunning()) {
                AutoTaggerService.cancel();
                runBtn.setText("Cancelando...");
                return;
            }
            if (untaggedCount == 0) {
                host.status.setText("Todas as músicas já têm tags!");
                return;
            }
            runBtn.setText("⏹ Parar Auto-Tagger");
            AutoTaggerService.tagUntaggedTracks(host, new ArrayList<>(host.queue), true, new AutoTaggerService.ProgressListener() {
                @Override
                public void onProgress(int processed, int total, int taggedCount, String currentTrack) {
                    if (autoTagProgressLabel != null) {
                        autoTagProgressLabel.setText("Analisando " + processed + "/" + total + " · " + taggedCount + " tagueadas (" + currentTrack + ")");
                    }
                    host.status.setText("Auto-Tag: " + processed + "/" + total + " · " + taggedCount + " classificadas");
                }

                @Override
                public void onFinished(int processed, int taggedCount, boolean cancelled) {
                    if (autoTagProgressLabel != null) {
                        autoTagProgressLabel.setText(cancelled
                            ? "Pausado: " + taggedCount + " músicas tagueadas."
                            : "Concluído! " + taggedCount + " músicas receberam tags automaticamente.");
                    }
                    host.status.setText("Auto-Tag concluído: " + taggedCount + " músicas classificadas!");
                    host.refreshFavorite();
                    renderQueue();
                }
            });
        });

        return card;
    }

    private TextView makeNoteView(String msg) {
        TextView tv = new TextView(host);
        tv.setText(msg);
        tv.setTextSize(12.5f);
        tv.setTextColor(host.MUTED);
        tv.setPadding(host.dp(6), host.dp(12), host.dp(6), host.dp(12));
        return tv;
    }

    private void bindShelfCover(ImageView view, TrackInfo track) {
        if (track == null || track.uri == null) {
            view.setImageResource(R.drawable.ic_app);
            return;
        }
        String key = track.uri.toString();
        view.setTag(key);
        Bitmap cached = track.artwork != null && !track.artwork.isRecycled()
            ? track.artwork : CoverArtLoader.peek(track.uri);
        if (cached != null && !cached.isRecycled()) {
            track.artwork = cached;
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(R.drawable.ic_app);
        CoverArtLoader.load(host, track.uri, bitmap -> {
            if (bitmap != null && !bitmap.isRecycled()) {
                track.artwork = bitmap;
                host.runOnUiThread(() -> {
                    if (key.equals(view.getTag())) {
                        view.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    void appendTrackRow(int trackIndex, TrackInfo track, String subtitle) {
        // Maintained for source compatibility; RecyclerView handles row rendering in LibraryRecyclerAdapter.
    }

    private String cleanAlbumTitle(String album) {
        if (album == null || album.trim().isEmpty()) return "Álbum desconhecido";
        return album.trim();
    }

    private String albumGroupKey(TrackInfo track) {
        String raw = cleanAlbumTitle(track.album).toLowerCase(Locale.ROOT);
        return raw.replaceAll("\\s+", " ");
    }

    String groupKey(TrackInfo track) {
        return host.libraryView == 0
            ? primaryArtist(track.artist).toLowerCase(Locale.ROOT)
            : albumGroupKey(track);
    }

    void createPlaylist(String uri) {
        EditText name = new EditText(host);
        name.setHint("Nome da playlist");
        name.setSingleLine(true);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
            .setTitle("Nova playlist")
            .setView(name)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Criar", (dialog, which) -> {
                String value = name.getText().toString().trim();
                if (!value.isEmpty()) {
                    host.collections.add(value, uri);
                    renderQueue();
                }
            }).show();
    }

    void addToPlaylist(String uri) {
        ArrayList<String> names = host.collections.names();
        names.add("＋ Nova playlist");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
            .setTitle("Adicionar à playlist")
            .setItems(names.toArray(new String[0]), (dialog, selected) -> {
                if (selected == names.size() - 1) createPlaylist(uri);
                else {
                    host.collections.add(names.get(selected), uri);
                    host.status.setText("Adicionada a " + names.get(selected));
                }
            }).show();
    }

    String primaryArtist(String artist) {
        if (artist == null || artist.trim().isEmpty()) return "Artista desconhecido";
        String cleaned = artist.trim();
        String primary = cleaned.split(
            "(?i)\\s*(?:;|/|&|,|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bpart\\.?\\b|\\bwith\\b|\\bvs\\.?\\b)\\s*|\\s+x\\s+",
            2
        )[0].trim();
        return primary.isEmpty() ? cleaned : primary;
    }

    void loadVisibleCovers() {
        // Handled dynamically by LibraryRecyclerAdapter onBindViewHolder with RAM + Disk cache.
    }
}
