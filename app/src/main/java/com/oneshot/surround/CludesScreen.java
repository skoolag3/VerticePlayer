package com.oneshot.surround;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.materialswitch.MaterialSwitch;

import static android.content.Context.MODE_PRIVATE;

/** Builds the CludesAmp Rack UI with 2x3 Studio Rotary Knobs, Presets, and Mastering Switches. */
final class CludesScreen {
    private final MainActivity host;

    CludesScreen(MainActivity host) {
        this.host = host;
    }

    void build(LinearLayout cludesPanel) {
        host.cludesVolume = host.getPreferences(MODE_PRIVATE).getInt("cludesVolume", 100);
        host.cludesWidth = host.getPreferences(MODE_PRIVATE).getInt("cludesWidth", 100);
        host.cludesBass = host.getPreferences(MODE_PRIVATE).getInt("cludesBass", 0);
        host.cludesTreble = host.getPreferences(MODE_PRIVATE).getInt("cludesTreble", 0);
        host.cludesClarity = host.getPreferences(MODE_PRIVATE).getInt("cludesClarity", 0);
        host.cludesLoudness = host.getPreferences(MODE_PRIVATE).getBoolean("cludesLoudness", false);
        host.cludesEnhancer = host.getPreferences(MODE_PRIVATE).getBoolean("cludesEnhancer", false);

        // 1. Header + Master Switch + Quick Presets
        LinearLayout topCard = host.card();
        cludesPanel.addView(topCard);

        LinearLayout headerRow = host.row();
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        topCard.addView(headerRow);

        LinearLayout titleCol = host.column();
        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView rackTag = host.label(titleCol, "CLUDESAMP RACK", 11, MainActivity.TECH_CYAN, true);
        rackTag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        host.label(titleCol, "Armônico, dinâmica M/S e reverb FDN", 12, host.MUTED, false);

        host.cludesToggle = new MaterialSwitch(host);
        host.cludesToggle.setChecked(host.getPreferences(MODE_PRIVATE).getBoolean("cludesEnabled", false));
        host.cludesToggle.setContentDescription("Ativar CludesAmp");
        headerRow.addView(host.cludesToggle);
        host.cludesToggle.setOnCheckedChangeListener((button, enabled) -> host.updateCludes());

        host.space(topCard, 6);
        String[] cludesPresets = {"Normal", "Bass boost", "Slowed+", "Nightcore", "Lo-fi", "8D"};
        for (int index = 0; index < cludesPresets.length; index++) {
            if (index % 3 == 0) topCard.addView(host.row());
            int preset = index;
            PresetTileView option = new PresetTileView(host, cludesPresets[index], index);
            host.presetTiles[index] = option;
            LinearLayout.LayoutParams tileSize = new LinearLayout.LayoutParams(0, host.dp(68), 1);
            tileSize.setMargins(host.dp(3), host.dp(3), host.dp(3), host.dp(3));
            ((LinearLayout) topCard.getChildAt(topCard.getChildCount() - 1)).addView(option, tileSize);
            option.setOnClickListener(view -> host.applyCludesPreset(preset));
        }

        // 2. 2x3 Studio Rotary Knobs Grid
        LinearLayout knobsCard = host.card();
        cludesPanel.addView(knobsCard);

        LinearLayout knobsHeader = host.row();
        knobsHeader.setGravity(Gravity.CENTER_VERTICAL);
        knobsCard.addView(knobsHeader);
        TextView knobsTitle = host.label(knobsHeader, "CONTROLES ANALÓGICOS", 11, MainActivity.ACCENT, true);
        knobsTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        knobsHeader.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        host.label(knobsHeader, "2x toque reseta", 10.5f, host.MUTED, false);

        host.space(knobsCard, 4);

        LinearLayout knobRow1 = host.row();
        knobsCard.addView(knobRow1);
        host.cludesVolumeBar = host.playerUi.addKnob(knobRow1, "Volume", "Volume", 0, 600, host.cludesVolume, 100, MainActivity.ACCENT, value -> {
            host.cludesVolume = value;
            host.updateCludes();
        });
        host.cludesWidthBar = host.playerUi.addKnob(knobRow1, "Largura", "Largura estéreo", 0, 200, host.cludesWidth, 100, MainActivity.TECH_CYAN, value -> {
            host.cludesWidth = value;
            host.updateCludes();
        });
        host.cludesBassBar = host.playerUi.addKnob(knobRow1, "Bass", "Bass", -12, 12, host.cludesBass, 0, MainActivity.ACCENT, value -> {
            host.cludesBass = value;
            host.updateCludes();
        });

        LinearLayout knobRow2 = host.row();
        knobsCard.addView(knobRow2);
        host.cludesTrebleBar = host.playerUi.addKnob(knobRow2, "Treble", "Treble", -12, 12, host.cludesTreble, 0, MainActivity.TECH_CYAN, value -> {
            host.cludesTreble = value;
            host.updateCludes();
        });
        host.cludesClarityBar = host.playerUi.addKnob(knobRow2, "Clarity", "Clarity", 0, 100, host.cludesClarity, 0, MainActivity.ACCENT, value -> {
            host.cludesClarity = value;
            host.updateCludes();
        });
        int initialReverb = host.getPreferences(MODE_PRIVATE).getInt("reverb", 0);
        host.cludesReverbBar = host.playerUi.addKnob(knobRow2, "Reverb", "Reverb", 0, 100, initialReverb, 0, MainActivity.TECH_CYAN, value -> {
            host.getPreferences(MODE_PRIVATE).edit().putInt("reverb", value).apply();
            host.updateCludes();
        });

        // 3. Psychoacoustic Switches + 5-Band Sub-EQ
        LinearLayout switchesCard = host.card();
        cludesPanel.addView(switchesCard);
        TextView switchesTitle = host.label(switchesCard, "CIRCUITOS PSICOACÚSTICOS", 11, MainActivity.ACCENT, true);
        switchesTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        host.loudnessToggle = new MaterialSwitch(host);
        host.loudnessToggle.setText("Loudness Fletcher-Munson");
        host.loudnessToggle.setTextColor(host.TEXT);
        host.loudnessToggle.setChecked(host.cludesLoudness);
        switchesCard.addView(host.loudnessToggle);
        host.loudnessToggle.setOnCheckedChangeListener((button, enabled) -> {
            host.cludesLoudness = enabled;
            host.updateCludes();
        });

        host.enhancerToggle = new MaterialSwitch(host);
        host.enhancerToggle.setText("Bass Enhancer (Sub-harmônico)");
        host.enhancerToggle.setTextColor(host.TEXT);
        host.enhancerToggle.setChecked(host.cludesEnhancer);
        switchesCard.addView(host.enhancerToggle);
        host.enhancerToggle.setOnCheckedChangeListener((button, enabled) -> {
            host.cludesEnhancer = enabled;
            host.updateCludes();
        });

        MaterialSwitch pultecSwitch = new MaterialSwitch(host);
        pultecSwitch.setText("Pultec EQP-1A Trick (+55 Hz Sub Boost / -118 Hz Clean)");
        pultecSwitch.setTextColor(host.TEXT);
        boolean savedPultec = host.getPreferences(MODE_PRIVATE).getBoolean("cludesPultec", false);
        pultecSwitch.setChecked(savedPultec);
        host.player.setCludesPultec(savedPultec);
        switchesCard.addView(pultecSwitch);
        pultecSwitch.setOnCheckedChangeListener((button, enabled) -> {
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("cludesPultec", enabled).apply();
            host.player.setCludesPultec(enabled);
            host.status.setText(enabled ? "Pultec Low-End Trick ativo (+55 Hz / -118 Hz)" : "Pultec Trick desligado");
        });

        MaterialSwitch tuning432Switch = new MaterialSwitch(host);
        tuning432Switch.setText("Afinação Natural A = 432 Hz (-0.318 st)");
        tuning432Switch.setTextColor(host.TEXT);
        boolean saved432 = host.getPreferences(MODE_PRIVATE).getBoolean("tuning432Hz", false);
        tuning432Switch.setChecked(saved432);
        host.player.setTuning432Hz(saved432);
        switchesCard.addView(tuning432Switch);
        tuning432Switch.setOnCheckedChangeListener((button, enabled) -> {
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("tuning432Hz", enabled).apply();
            host.player.setTuning432Hz(enabled);
            host.status.setText(enabled ? "Afinação A=432 Hz ativa (harmonia natural)" : "Afinação padrão A=440 Hz");
        });

        host.space(switchesCard, 4);
        Button expandEq = host.pill("EQ CludesAmp · 5 bandas   ›", false);
        switchesCard.addView(expandEq, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(40)));
        LinearLayout cludesEqPanel = host.column();
        switchesCard.addView(cludesEqPanel);
        cludesEqPanel.setVisibility(View.GONE);
        expandEq.setOnClickListener(v -> {
            boolean opening = cludesEqPanel.getVisibility() != View.VISIBLE;
            cludesEqPanel.setVisibility(opening ? View.VISIBLE : View.GONE);
            expandEq.setText(opening ? "EQ CludesAmp · 5 bandas   ⌄" : "EQ CludesAmp · 5 bandas   ›");
        });

        host.cludesEqBars = new AudioSlider[5];
        String[] frequencies = {"60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz"};
        for (int index = 0; index < 5; index++) {
            int band = index;
            host.cludesBands[index] = host.getPreferences(MODE_PRIVATE).getInt("cludesBand" + index, 0);
            host.cludesEqBars[index] = host.addSlider(cludesEqPanel, frequencies[index], -12, 12, (int) host.cludesBands[index], value -> {
                host.cludesBands[band] = value;
                host.updateCludes();
            });
        }

        // 4. Speed / Key-Lock + User Preset Memory
        LinearLayout speedCard = host.card();
        cludesPanel.addView(speedCard);
        host.cludesSpeedBar = host.addSlider(speedCard, "Velocidade", 50, 150, host.getPreferences(MODE_PRIVATE).getInt("speed", 100), value -> {
            host.getPreferences(MODE_PRIVATE).edit().putInt("speed", value).apply();
            host.updateCludes();
        });

        host.pitchToggle = new MaterialSwitch(host);
        host.pitchToggle.setText("Preservar tom ao mudar velocidade");
        host.pitchToggle.setTextColor(host.TEXT);
        host.pitchToggle.setChecked(host.getPreferences(MODE_PRIVATE).getBoolean("keepPitch", true));
        speedCard.addView(host.pitchToggle);
        host.pitchToggle.setOnCheckedChangeListener((button, checked) -> {
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("keepPitch", checked).apply();
            host.updateCludes();
        });

        host.space(speedCard, 6);
        LinearLayout customPresets = host.row();
        speedCard.addView(customPresets);
        Button saveCludes = host.pill("Salvar ajuste", false);
        Button loadCludes = host.pill("Usar salvo", false);
        customPresets.addView(saveCludes, host.weighted());
        customPresets.addView(loadCludes, host.weighted());
        saveCludes.setOnClickListener(view -> host.saveCludesPreset());
        loadCludes.setOnClickListener(view -> host.loadCludesPreset());

        host.updateCludes();
        host.space(cludesPanel, 12);
    }
}
