package com.oneshot.surround;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.Locale;

import static android.content.Context.MODE_PRIVATE;

/** Builds the 10-band Parametric Equalizer screen with AutoEQ Curves, Mid/Side Topology, and L/R Balance. */
final class EqualizerScreen {
    private final MainActivity host;

    EqualizerScreen(MainActivity host) {
        this.host = host;
    }

    void build(LinearLayout eqPanel) {
        LinearLayout eqCard = host.card();
        eqPanel.addView(eqCard);

        LinearLayout headerRow = host.row();
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        eqCard.addView(headerRow);
        TextView tag = host.label(headerRow, "PARAMETRIC EQ · 10 BANDAS", 11, MainActivity.TECH_CYAN, true);
        tag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        headerRow.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        host.playerUi.monoBadge(headerRow, "M/S BIQUAD 64-BIT", MainActivity.SIGNAL_OK);

        host.eqToggle = new MaterialSwitch(host);
        host.eqToggle.setText("Equalizador ativo");
        host.eqToggle.setTextColor(host.TEXT);
        boolean eqSaved = host.getPreferences(MODE_PRIVATE).getBoolean("eqEnabled", true);
        host.eqToggle.setChecked(eqSaved);
        host.player.setEqualizerEnabled(eqSaved);
        eqCard.addView(host.eqToggle);

        host.presetPicker = new AudioDropdown(host, "Preset / Curva AutoEQ", MainActivity.PRESET_NAMES);
        eqCard.addView(host.presetPicker);

        // Quick AutoEQ / GitHub Target Curve Browser button
        host.space(eqCard, 6);
        Button autoEqBtn = host.pill("🎧  Catálogo AutoEQ · Fones & Alvos Harman", false);
        autoEqBtn.setTextSize(11.5f);
        eqCard.addView(autoEqBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(38)));
        autoEqBtn.setOnClickListener(v -> showAutoEqCatalogDialog());

        host.space(eqCard, 6);

        // Item 4: Mid / Side Independent EQ Topology selector (Roon / FabFilter Pro-Q style)
        String[] topoLabels = {
            "Estéreo L+R (Normal)",
            "Apenas MID · Centro (Voz, Bumbo & Baixo)",
            "Apenas SIDE · Palco Lateral (Violões & Ar)"
        };
        int savedTopo = host.getPreferences(MODE_PRIVATE).getInt("eqTopology", 0);
        AudioDropdown topoPicker = new AudioDropdown(host, "Topologia de Canal EQ (L+R / Mid / Side)", topoLabels);
        topoPicker.setSelection(savedTopo);
        host.player.setEqualizerTopology(savedTopo);
        eqCard.addView(topoPicker);
        topoPicker.setOnSelection(pos -> {
            host.getPreferences(MODE_PRIVATE).edit().putInt("eqTopology", pos).apply();
            host.player.setEqualizerTopology(pos);
            host.status.setText("Topologia EQ: " + topoLabels[pos]);
        });

        int preamp = host.getPreferences(MODE_PRIVATE).getInt("preamp", 0);
        host.preampSlider = host.addSlider(eqCard, "Pré-amplificação", 0, 24, preamp + 12, value -> {
            int db = value - 12;
            host.getPreferences(MODE_PRIVATE).edit().putInt("preamp", db).apply();
            host.player.setEqualizer(host.eqGains, db);
            if (host.equalizerView != null) host.equalizerView.invalidate();
        });

        // Precision L/R Channel Balance Trim (-6.0 dB to +6.0 dB in 0.5 dB steps)
        int savedBalSteps = host.getPreferences(MODE_PRIVATE).getInt("eqBalanceSteps", 0); // -12..+12 => -6.0..+6.0 dB
        host.player.setChannelBalanceDb(savedBalSteps * 0.5);
        LinearLayout balHeader = host.row();
        balHeader.setGravity(Gravity.CENTER_VERTICAL);
        eqCard.addView(balHeader);
        host.label(balHeader, "Balanço Fino L/R (Calibração)", 12.5f, host.TEXT, true);
        balHeader.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        TextView balValText = host.label(balHeader, formatBalance(savedBalSteps), 11.5f, MainActivity.TECH_CYAN, true);
        balValText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        AudioSlider balSlider = new AudioSlider(host, 24, savedBalSteps + 12);
        eqCard.addView(balSlider.view);
        balSlider.view.addOnChangeListener((slider, value, fromUser) -> {
                int steps = Math.round(value) - 12;
                balValText.setText(formatBalance(steps));
                host.getPreferences(MODE_PRIVATE).edit().putInt("eqBalanceSteps", steps).apply();
                host.player.setChannelBalanceDb(steps * 0.5);
        });

        host.equalizerView = new EqualizerView(host, host.eqGains, (band, gain) -> {
            if (host.eqSliders != null && host.eqSliders[band] != null) {
                host.eqSliders[band].setProgress(gain + 12);
            }
        });
        LinearLayout.LayoutParams graphLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(136));
        graphLayout.topMargin = host.dp(8);
        graphLayout.bottomMargin = host.dp(8);
        eqCard.addView(host.equalizerView, graphLayout);

        LinearLayout slidersRow = host.row();
        slidersRow.setGravity(Gravity.CENTER_HORIZONTAL);
        eqCard.addView(slidersRow);

        String[] bandLabels = {"31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
        host.eqSliders = new SeekBar[10];
        host.eqValues = new TextView[10];
        for (int index = 0; index < 10; index++) {
            int band = index;
            int saved = host.getPreferences(MODE_PRIVATE).getInt("eq" + band, 0);
            host.eqGains[band] = saved;
            LinearLayout strip = host.column();
            strip.setGravity(Gravity.CENTER_HORIZONTAL);
            slidersRow.addView(strip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            host.eqValues[band] = host.label(strip, (saved > 0 ? "+" : "") + saved, 10, host.ACCENT, true);
            host.eqValues[band].setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            host.eqValues[band].setGravity(Gravity.CENTER_HORIZONTAL);

            FrameLayout verticalSlot = new FrameLayout(host);
            strip.addView(verticalSlot, new LinearLayout.LayoutParams(host.dp(30), host.dp(158)));

            SeekBar bar = new SeekBar(host);
            bar.setMax(24);
            bar.setProgress(saved + 12);
            bar.setRotation(270);
            bar.setProgressTintList(ColorStateList.valueOf(host.ACCENT));
            bar.setThumbTintList(ColorStateList.valueOf(host.ACCENT));
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(host.dp(146), host.dp(30), Gravity.CENTER);
            verticalSlot.addView(bar, barParams);
            host.eqSliders[band] = bar;

            TextView freqText = host.label(strip, bandLabels[band], 9.5f, host.MUTED, false);
            freqText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
            freqText.setGravity(Gravity.CENTER_HORIZONTAL);

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                    int db = value - 12;
                    host.eqGains[band] = db;
                    host.eqValues[band].setText((db > 0 ? "+" : "") + db);
                    host.getPreferences(MODE_PRIVATE).edit().putInt("eq" + band, db).apply();
                    host.player.setEqualizer(host.eqGains, host.preampSlider.getProgress() - 12);
                    host.equalizerView.invalidate();
                    if (fromUser && !host.loadingPreset) {
                        int customIdx = MainActivity.PRESET_NAMES.length - 1;
                        host.saveCustomEq();
                        host.loadingPreset = true;
                        host.presetPicker.setSelection(customIdx);
                        host.getPreferences(MODE_PRIVATE).edit().putInt("preset", customIdx).apply();
                        host.loadingPreset = false;
                    }
                }
                public void onStartTrackingTouch(SeekBar seekBar) { }
                public void onStopTrackingTouch(SeekBar seekBar) { }
            });
        }

        host.player.setEqualizer(host.eqGains, preamp);
        host.equalizerView.invalidate();

        int savedPreset = Math.min(
            MainActivity.PRESET_NAMES.length - 1,
            Math.max(0, host.getPreferences(MODE_PRIVATE).getInt("preset", 0))
        );
        host.loadingPreset = true;
        host.presetPicker.setSelection(savedPreset);
        host.loadingPreset = false;

        host.presetPicker.setOnSelection(position -> applyPresetIndex(position));

        host.eqToggle.setOnCheckedChangeListener((button, enabled) -> {
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("eqEnabled", enabled).apply();
            host.player.setEqualizerEnabled(enabled);
            host.status.setText(enabled ? "Equalizador ativado" : "Equalizador desativado");
            host.refreshOutputBadges();
        });

        LinearLayout bottomRow = host.row();
        eqPanel.addView(bottomRow);
        Button flatEq = host.pill("Zerar bandas (0 dB)", false);
        Button centerBal = host.pill("Centralizar L/R", false);
        bottomRow.addView(flatEq, host.weighted());
        bottomRow.addView(centerBal, host.weighted());
        flatEq.setOnClickListener(view -> applyPresetIndex(0));
        centerBal.setOnClickListener(view -> balSlider.setProgress(12));
    }

    private static String formatBalance(int steps) {
        if (steps == 0) return "Centro 0.0 dB";
        double db = Math.abs(steps * 0.5);
        return String.format(Locale.US, "%s +%.1f dB", steps < 0 ? "Esq (L)" : "Dir (R)", db);
    }

    private void applyPresetIndex(int position) {
        if (host.loadingPreset) return;
        host.getPreferences(MODE_PRIVATE).edit().putInt("preset", position).apply();
        host.loadingPreset = true;
        host.presetPicker.setSelection(position);
        for (int index = 0; index < 10; index++) {
            int gain = position < MainActivity.PRESETS.length
                ? MainActivity.PRESETS[position][index]
                : host.getPreferences(MODE_PRIVATE).getInt("custom" + index, 0);
            host.eqSliders[index].setProgress(gain + 12);
        }
        host.loadingPreset = false;
        host.status.setText("Equalizador · " + MainActivity.PRESET_NAMES[position]);
    }

    private void showAutoEqCatalogDialog() {
        final int startIdx = 14;
        final int endIdx = MainActivity.PRESETS.length;
        String[] items = new String[endIdx - startIdx];
        for (int i = startIdx; i < endIdx; i++) {
            items[i - startIdx] = MainActivity.PRESET_NAMES[i];
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
            .setTitle("Curvas AutoEQ & Referência")
            .setItems(items, (d, which) -> applyPresetIndex(startIdx + which))
            .setNegativeButton("Fechar", null)
            .show();
    }
}
