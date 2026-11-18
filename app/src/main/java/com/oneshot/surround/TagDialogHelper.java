package com.oneshot.surround;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.Set;

/**
 * Interactive Studio BottomSheet & Dialogs for:
 * 1. One-tap assigning/removing tags on any track (Phonk, Trap, Love Song, + Custom Tags)
 * 2. Creating or editing custom tags with AutoMix probability (0..200%), Strict Cluster lock, and Energy Bias
 * 3. Quick actions for Favorites and Playlists inside the same unified sheet (for long-press convenience)
 */
final class TagDialogHelper {
    private TagDialogHelper() { }

    static void showTrackTagSheet(MainActivity host, TrackInfo track, Runnable onChanged) {
        if (host == null || track == null) return;
        final String uri = track.uri.toString();
        final TrackTagDatabase db = TrackTagDatabase.get(host);

        BottomSheetDialog dialog = new BottomSheetDialog(host);
        LinearLayout sheet = host.column();
        sheet.setPadding(host.dp(18), host.dp(16), host.dp(18), host.dp(18));
        sheet.setBackground(host.borderedBackground(MainActivity.SURFACE, MainActivity.BORDER, 22));

        // Top Header
        LinearLayout header = host.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        sheet.addView(header);
        TextView badge = host.label(header, "TAGS & AUTOMIX ENGINE", 10.5f, MainActivity.TECH_CYAN, true);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));

        // Keep Favorites accessible right in the header!
        boolean isFav = host.collections.favorites().contains(uri);
        Button favPill = host.pill(isFav ? "♥ Favorita" : "♡ Favoritar", isFav);
        favPill.setTextSize(11);
        favPill.setPadding(host.dp(10), 0, host.dp(10), 0);
        header.addView(favPill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, host.dp(32)));
        favPill.setOnClickListener(v -> {
            boolean nowFav = host.collections.toggleFavorite(uri);
            favPill.setText(nowFav ? "♥ Favorita" : "♡ Favoritar");
            MaterialUi.buttonState(favPill, nowFav);
            if (onChanged != null) onChanged.run();
        });

        TextView trackTitle = host.label(sheet, track.title, 18, MainActivity.TEXT, true);
        trackTitle.setSingleLine(true);
        host.label(sheet, track.artist + " · Toque nas tags para ativar/desativar (segure numa tag para editar chance no AutoMix)", 11.5f, MainActivity.MUTED, false);

        host.space(sheet, 10);

        ScrollView scroll = new ScrollView(host);
        LinearLayout content = host.column();
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Runnable[] rebuildGrid = new Runnable[1];
        rebuildGrid[0] = () -> {
            content.removeAllViews();
            ArrayList<TrackTagDatabase.TagDefinition> allDefs = db.getAllDefinitions();
            Set<String> activeTags = db.getTagsForTrack(uri);

            LinearLayout row = null;
            for (int i = 0; i < allDefs.size(); i++) {
                if (i % 2 == 0) {
                    row = host.row();
                    content.addView(row);
                }
                final TrackTagDatabase.TagDefinition def = allDefs.get(i);
                boolean selected = db.hasTag(uri, def.name);

                String labelText = (selected ? "✓ " : "🏷 ") + def.name
                    + (def.autoMixChancePct != 100 ? " (" + def.autoMixChancePct + "%)" : "");
                Button chip = host.pill(labelText, selected);
                chip.setTextSize(11.5f);
                chip.setSingleLine(true);
                if (selected) {
                    chip.setTextColor(def.color);
                }

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, host.dp(44), 1);
                lp.setMargins(host.dp(4), host.dp(4), host.dp(4), host.dp(4));
                row.addView(chip, lp);

                chip.setOnClickListener(v -> {
                    boolean added = db.toggleTrackTag(uri, def.name);
                    rebuildGrid[0].run();
                    host.refreshFavorite();
                    if (onChanged != null) onChanged.run();
                    host.status.setText((added ? "Tag '" + def.name + "' adicionada a " : "Tag '" + def.name + "' removida de ") + track.title);
                });

                chip.setOnLongClickListener(v -> {
                    showCreateOrEditTagDialog(host, def, () -> {
                        rebuildGrid[0].run();
                        if (onChanged != null) onChanged.run();
                    });
                    return true;
                });
            }
            if (allDefs.size() % 2 == 1 && row != null) {
                row.addView(new View(host), new LinearLayout.LayoutParams(0, host.dp(44), 1));
            }
        };
        rebuildGrid[0].run();

        host.space(sheet, 10);

        // Bottom Action Buttons: Create Custom Tag + Add to Playlist + Close
        LinearLayout actionsRow = host.row();
        sheet.addView(actionsRow);

        Button newTagBtn = host.pill("＋ Criar Tag Personalizada", true);
        newTagBtn.setTextSize(11.5f);
        LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(0, host.dp(42), 1.2f);
        newLp.rightMargin = host.dp(6);
        actionsRow.addView(newTagBtn, newLp);
        newTagBtn.setOnClickListener(v -> showCreateOrEditTagDialog(host, null, () -> {
            rebuildGrid[0].run();
            if (onChanged != null) onChanged.run();
        }));

        Button playlistBtn = host.pill("♫ Playlist", false);
        playlistBtn.setTextSize(11.5f);
        LinearLayout.LayoutParams plLp = new LinearLayout.LayoutParams(0, host.dp(42), 0.8f);
        actionsRow.addView(playlistBtn, plLp);
        playlistBtn.setOnClickListener(v -> {
            dialog.dismiss();
            host.addToPlaylist(uri);
        });

        host.space(sheet, 6);
        Button closeBtn = host.pill("Concluído", false);
        sheet.addView(closeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(40)));
        closeBtn.setOnClickListener(v -> dialog.dismiss());

        int height = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.78f);
        dialog.setContentView(sheet, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        dialog.show();
        dialog.getBehavior().setPeekHeight(height);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    /**
     * Dialog for creating a new custom Tag or editing an existing Tag's AutoMix behavior
     * (Chance % in AutoMix, Strict Cluster Lock, Energy Bias, Color).
     */
    static void showCreateOrEditTagDialog(MainActivity host, TrackTagDatabase.TagDefinition existing, Runnable onSaved) {
        final TrackTagDatabase db = TrackTagDatabase.get(host);
        LinearLayout box = host.column();
        box.setPadding(host.dp(20), host.dp(12), host.dp(20), host.dp(8));

        EditText nameInput = new EditText(host);
        nameInput.setHint("Nome da tag (ex: Drift Phonk, Sad Trap, Treino)");
        nameInput.setSingleLine(true);
        nameInput.setTextColor(MainActivity.TEXT);
        nameInput.setHintTextColor(MainActivity.MUTED);
        if (existing != null) {
            nameInput.setText(existing.name);
            if (existing.builtIn) nameInput.setEnabled(false);
        }
        box.addView(nameInput);

        host.space(box, 10);

        final int initialChance = existing != null ? existing.autoMixChancePct : 130;
        final int[] chanceHolder = {initialChance};
        LinearLayout chanceRow = host.row();
        chanceRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(chanceRow);
        host.label(chanceRow, "Chance / Peso no AutoMix", 12.5f, MainActivity.TEXT, true);
        chanceRow.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        TextView chanceVal = host.label(chanceRow, initialChance + "%", 12, MainActivity.TECH_CYAN, true);
        chanceVal.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        SeekBar chanceSlider = new SeekBar(host);
        chanceSlider.setMax(200);
        chanceSlider.setProgress(initialChance);
        chanceSlider.setProgressTintList(ColorStateList.valueOf(MainActivity.TECH_CYAN));
        chanceSlider.setThumbTintList(ColorStateList.valueOf(MainActivity.TECH_CYAN));
        box.addView(chanceSlider);
        chanceSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                chanceHolder[0] = value;
                chanceVal.setText(value + "%");
            }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        host.label(box, "100% = normal · 150%+ = puxa muito mais no AutoMix · <50% = toca raramente.", 11, MainActivity.MUTED, false);

        host.space(box, 8);

        MaterialSwitch clusterSwitch = new MaterialSwitch(host);
        clusterSwitch.setText("Agrupar Forte no AutoMix (Evitar misturar com vibes opostas)");
        clusterSwitch.setTextColor(MainActivity.TEXT);
        clusterSwitch.setChecked(existing == null || existing.strictCluster);
        box.addView(clusterSwitch);

        host.space(box, 8);

        String[] energyLabels = {
            "Clima Calmo / Chill (-2)",
            "Suave / Melódico (-1)",
            "Neutro / Versátil (0)",
            "Energia Alta / Groove (+1)",
            "Agressivo / Hype Máximo (+2)"
        };
        int initialBiasIdx = existing != null ? Math.max(0, Math.min(4, existing.energyBias + 2)) : 3;
        final int[] biasHolder = {initialBiasIdx - 2};
        AudioDropdown energyPicker = new AudioDropdown(host, "Perfil de Energia no AutoMix", energyLabels);
        energyPicker.setSelection(initialBiasIdx);
        box.addView(energyPicker);
        energyPicker.setOnSelection(pos -> biasHolder[0] = pos - 2);

        String[] colorLabels = {
            "Violeta Vértice",
            "Ciano Técnico",
            "Rosa Phonk / Neon",
            "Verde Esmeralda",
            "Âmbar Gold"
        };
        final int[] palette = {0xFF9D7BFF, 0xFF00E5FF, 0xFFFF3D71, 0xFF2EE59D, 0xFFFFB74D};
        int initialColorIdx = 0;
        if (existing != null) {
            for (int i = 0; i < palette.length; i++) {
                if (palette[i] == existing.color) { initialColorIdx = i; break; }
            }
        }
        final int[] colorHolder = {existing != null ? existing.color : palette[0]};
        host.space(box, 8);
        AudioDropdown colorPicker = new AudioDropdown(host, "Cor do Badge da Tag", colorLabels);
        colorPicker.setSelection(initialColorIdx);
        box.addView(colorPicker);
        colorPicker.setOnSelection(pos -> colorHolder[0] = palette[Math.max(0, Math.min(palette.length - 1, pos))]);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(host)
            .setTitle(existing == null ? "Nova Tag Personalizada" : "Configurar Tag: " + existing.name)
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar Tag", (d, w) -> {
                String tagName = existing != null ? existing.name : nameInput.getText().toString().trim();
                if (tagName.isEmpty()) return;
                db.saveOrUpdateTagDefinition(
                    tagName, colorHolder[0], chanceHolder[0],
                    clusterSwitch.isChecked(), biasHolder[0]
                );
                if (onSaved != null) onSaved.run();
                host.status.setText("Tag '" + tagName + "' salva (" + chanceHolder[0] + "% no AutoMix)");
            });

        if (existing != null && !existing.builtIn) {
            builder.setNeutralButton("Excluir Tag", (d, w) -> {
                db.deleteCustomTag(existing.name);
                if (onSaved != null) onSaved.run();
                host.status.setText("Tag '" + existing.name + "' excluída");
            });
        }
        builder.show();
    }
}
