package com.oneshot.surround;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import static android.content.Context.MODE_PRIVATE;

/** Builds the Audiophile 3D Stage, Binaural Matrix, and Zero-Loss Structural Layer Unmasker controls. */
final class SpatialScreen {
    private final MainActivity host;

    SpatialScreen(MainActivity host) {
        this.host = host;
    }

    void build(LinearLayout spatialPanel) {
        // 1. Stage visualizer + Studio Rotary Intensity Knob side-by-side
        LinearLayout consoleCard = host.card();
        spatialPanel.addView(consoleCard);

        LinearLayout consoleHeader = host.row();
        consoleHeader.setGravity(Gravity.CENTER_VERTICAL);
        consoleCard.addView(consoleHeader);

        LinearLayout titleCol = host.column();
        consoleHeader.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        host.label(titleCol, "MOTOR ESPACIAL BINAURAL", 11, MainActivity.TECH_CYAN, true);
        host.label(titleCol, "Palco 3D, ITD/ILD e Pinna HRTF sem perda de centro", 12, host.MUTED, false);

        host.intensityValue = host.playerUi.monoBadge(consoleHeader, host.intensity + "%", MainActivity.ACCENT);

        host.space(consoleCard, 8);

        LinearLayout stageRow = host.row();
        stageRow.setGravity(Gravity.CENTER_VERTICAL);
        consoleCard.addView(stageRow);

        host.spatialStage = new SpatialStageView(host);
        host.spatialStage.setStrength(host.intensity);
        host.spatialStage.setBackground(host.borderedBackground(MainActivity.BACKGROUND, MainActivity.BORDER, 14));
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(0, host.dp(168), 1.45f);
        stageLp.rightMargin = host.dp(8);
        stageRow.addView(host.spatialStage, stageLp);

        LinearLayout knobBox = host.column();
        knobBox.setGravity(Gravity.CENTER);
        knobBox.setPadding(host.dp(6), host.dp(8), host.dp(6), host.dp(8));
        knobBox.setBackground(host.borderedBackground(MainActivity.SURFACE_TOP, MainActivity.BORDER, 14));
        stageRow.addView(knobBox, new LinearLayout.LayoutParams(0, host.dp(168), 1f));

        host.intensitySlider = host.playerUi.addKnob(
            knobBox,
            "Intensidade 3D",
            "Intensidade",
            0, 100, host.intensity, 80,
            MainActivity.ACCENT,
            value -> {
                host.intensity = value;
                host.spatialStage.setStrength(value);
                host.intensityValue.setText(value + "%");
                host.player.setSpatial(host.mode, value);
                host.getPreferences(MODE_PRIVATE).edit().putInt("spatialIntensity", value).apply();
            }
        );
        if (knobBox.getChildCount() > 0) {
            LinearLayout.LayoutParams knobLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(132));
            knobBox.getChildAt(0).setLayoutParams(knobLp);
        }

        // 2. 9 Binaural & Surround Mode Grid (3x3)
        LinearLayout matrixCard = host.card();
        spatialPanel.addView(matrixCard);
        TextView matrixTitle = host.label(matrixCard, "MATRIZ DE CAMPO SONORO", 11, MainActivity.ACCENT, true);
        matrixTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        host.space(matrixCard, 6);

        host.holoButton = host.pill("", false);
        host.holoButton.setText(host.playerUi.richModeText("Holográfico", "6-Pilar 3D"));

        host.roomButton = host.pill("", false);
        host.roomButton.setText(host.playerUi.richModeText("Sala 3D", "ITDG Estúdio"));

        host.wideButton = host.pill("", false);
        host.wideButton.setText(host.playerUi.richModeText("Amplo", "M/S Master"));

        host.frontButton = host.pill("", false);
        host.frontButton.setText(host.playerUi.richModeText("Crossfeed", "Monitor Meier"));

        host.surroundButton = host.pill("", false);
        host.surroundButton.setText(host.playerUi.richModeText("Surround", "Multi-Canal"));

        host.theaterButton = host.pill("", false);
        host.theaterButton.setText(host.playerUi.richModeText("Cinema", "Sub + Centro"));

        host.ambioButton = host.pill("", false);
        host.ambioButton.setText(host.playerUi.richModeText("Ambio RACE", "Crosstalk"));

        host.orbitButton = host.pill("", false);
        host.orbitButton.setText(host.playerUi.richModeText("Órbita 8D", "Rotação 360°"));

        host.offButton = host.pill("", true);
        host.offButton.setText(host.playerUi.richModeText("Desligado", "Estéreo Puro"));

        Button[] spatialButtons = {
            host.holoButton, host.roomButton, host.wideButton,
            host.frontButton, host.surroundButton, host.theaterButton,
            host.ambioButton, host.orbitButton, host.offButton
        };

        LinearLayout spatialRow = null;
        for (int index = 0; index < spatialButtons.length; index++) {
            if (index % 3 == 0) {
                spatialRow = host.row();
                matrixCard.addView(spatialRow);
            }
            Button button = spatialButtons[index];
            button.setTextSize(11);
            button.setSingleLine(false);
            button.setMaxLines(2);
            button.setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, host.dp(60), 1);
            size.setMargins(host.dp(3), host.dp(3), host.dp(3), host.dp(3));
            spatialRow.addView(button, size);
        }

        host.wideButton.setOnClickListener(view -> host.chooseMode(SpatialProcessor.WIDE));
        host.roomButton.setOnClickListener(view -> host.chooseMode(SpatialProcessor.ROOM));
        host.orbitButton.setOnClickListener(view -> host.chooseMode(SpatialProcessor.ORBIT));
        host.offButton.setOnClickListener(view -> host.chooseMode(SpatialProcessor.OFF));
        host.frontButton.setOnClickListener(view -> host.chooseMode(SpatialProcessor.FRONT));
        host.surroundButton.setOnClickListener(view -> host.chooseMode(SpatialProcessor.SURROUND));
        host.theaterButton.setOnClickListener(view -> host.chooseMode(SpatialProcessor.THEATER));
        host.ambioButton.setOnClickListener(view -> host.chooseMode(SpatialProcessor.AMBIO));
        host.holoButton.setOnClickListener(view -> host.chooseMode(SpatialProcessor.HOLOGRAPHIC));

        // 3. Structural Deconstruction & Zero-Loss Layer Unmasking Card (Ad-Libs, Center Lock & Beat Separation)
        LinearLayout unmaskCard = host.card();
        spatialPanel.addView(unmaskCard);

        LinearLayout unmaskHeader = host.row();
        unmaskHeader.setGravity(Gravity.CENTER_VERTICAL);
        unmaskCard.addView(unmaskHeader);
        TextView unmaskTag = host.label(unmaskHeader, "DESCONSTRUÇÃO ESTRUTURAL · ZERO-LOSS", 11, MainActivity.TECH_CYAN, true);
        unmaskTag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        unmaskHeader.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        host.playerUi.monoBadge(unmaskHeader, "UPWARD + PINNA", MainActivity.SIGNAL_OK);

        host.label(unmaskCard,
            "Revela ad-libs, segundas vozes e detalhes de fundo (compressão ascendente -42 a -15 dBFS) mantendo 100% do peso da voz principal e do beat.",
            11.5f, host.MUTED, false);
        host.space(unmaskCard, 6);

        final int[] unmaskState = {
            host.getPreferences(MODE_PRIVATE).getInt("structAdlibUnmask", 45),
            host.getPreferences(MODE_PRIVATE).getInt("structCenterLock", 40),
            host.getPreferences(MODE_PRIVATE).getInt("structBeatSeparate", 25)
        };
        host.player.setStructuralUnmask(unmaskState[0], unmaskState[1], unmaskState[2]);

        LinearLayout unmaskKnobs = host.row();
        unmaskCard.addView(unmaskKnobs);

        AudioSlider adlibKnob = host.playerUi.addKnob(
            unmaskKnobs,
            "Ad-libs & Fundo",
            "Ad-libs",
            0, 100, unmaskState[0], 45,
            MainActivity.TECH_CYAN,
            val -> {
                unmaskState[0] = val;
                host.getPreferences(MODE_PRIVATE).edit().putInt("structAdlibUnmask", val).apply();
                host.player.setStructuralUnmask(unmaskState[0], unmaskState[1], unmaskState[2]);
            }
        );

        AudioSlider centerKnob = host.playerUi.addKnob(
            unmaskKnobs,
            "Trava Vocal",
            "Voz Centro",
            0, 100, unmaskState[1], 40,
            MainActivity.ACCENT,
            val -> {
                unmaskState[1] = val;
                host.getPreferences(MODE_PRIVATE).edit().putInt("structCenterLock", val).apply();
                host.player.setStructuralUnmask(unmaskState[0], unmaskState[1], unmaskState[2]);
            }
        );

        AudioSlider beatKnob = host.playerUi.addKnob(
            unmaskKnobs,
            "Separação Beat",
            "Punch Beat",
            0, 100, unmaskState[2], 25,
            MainActivity.SIGNAL_OK,
            val -> {
                unmaskState[2] = val;
                host.getPreferences(MODE_PRIVATE).edit().putInt("structBeatSeparate", val).apply();
                host.player.setStructuralUnmask(unmaskState[0], unmaskState[1], unmaskState[2]);
            }
        );

        host.space(unmaskCard, 6);
        LinearLayout presetRow = host.row();
        unmaskCard.addView(presetRow);
        Button offUnmask = host.pill("Desligar Lupa (0%)", false);
        Button studioUnmask = host.pill("Lupa de Ad-libs (70%)", false);
        presetRow.addView(offUnmask, host.weighted());
        presetRow.addView(studioUnmask, host.weighted());
        offUnmask.setOnClickListener(v -> {
            adlibKnob.setProgress(0);
            centerKnob.setProgress(0);
            beatKnob.setProgress(0);
            host.status.setText("Desconstrução estrutural desligada (mix original)");
        });
        studioUnmask.setOnClickListener(v -> {
            adlibKnob.setProgress(70);
            centerKnob.setProgress(55);
            beatKnob.setProgress(40);
            host.status.setText("Lupa de Ad-libs ativa · ad-libs revelados sem perder voz/beat");
        });

        // 4. Immersive Sensory & Codec-Aware Experiences Card
        LinearLayout expCard = host.card();
        spatialPanel.addView(expCard);

        LinearLayout expHeader = host.row();
        expHeader.setGravity(Gravity.CENTER_VERTICAL);
        expCard.addView(expHeader);
        TextView expTag = host.label(expHeader, "EXPERIÊNCIAS IMERSIVAS & CODEC DSP", 11, MainActivity.ACCENT, true);
        expTag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        expHeader.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        host.playerUi.monoBadge(expHeader, "6 MOTORES", MainActivity.TECH_CYAN);

        host.label(expCard,
            "Subwoofer Tátil (62 Hz Chest Thump), Abbey Road ADT Haas (voz dupla de veludo), Roland Dimension D, Sidechain Groove, Cabine/Arena e Codec-Care.",
            11.5f, host.MUTED, false);
        host.space(expCard, 6);

        final int[] expVals = {
            host.getPreferences(MODE_PRIVATE).getInt("expTactileSub", 0),
            host.getPreferences(MODE_PRIVATE).getInt("expHaasAdt", 0),
            host.getPreferences(MODE_PRIVATE).getInt("expDimensionD", 0),
            host.getPreferences(MODE_PRIVATE).getInt("expGroovePump", 0),
            host.getPreferences(MODE_PRIVATE).getInt("expVenueMode", 0)
        };
        final boolean[] expFlags = {
            host.getPreferences(MODE_PRIVATE).getBoolean("expTurntableVinyl", false),
            host.getPreferences(MODE_PRIVATE).getBoolean("expCodecAdaptive", true)
        };

        Runnable syncExp = () -> host.player.setSensoryExperience(
            expVals[0], expVals[1], expVals[2], expVals[3], expVals[4], expFlags[0], expFlags[1]
        );
        syncExp.run();

        LinearLayout expRow1 = host.row();
        expCard.addView(expRow1);
        AudioSlider subTactileKnob = host.playerUi.addKnob(
            expRow1, "Sub Tátil", "Sub Tátil", 0, 100, expVals[0], 0, MainActivity.ACCENT,
            val -> {
                expVals[0] = val;
                host.getPreferences(MODE_PRIVATE).edit().putInt("expTactileSub", val).apply();
                syncExp.run();
            }
        );
        AudioSlider haasAdtKnob = host.playerUi.addKnob(
            expRow1, "Voz Haas ADT", "Haas ADT", 0, 100, expVals[1], 0, MainActivity.TECH_CYAN,
            val -> {
                expVals[1] = val;
                host.getPreferences(MODE_PRIVATE).edit().putInt("expHaasAdt", val).apply();
                syncExp.run();
            }
        );

        LinearLayout expRow2 = host.row();
        expCard.addView(expRow2);
        AudioSlider dimDKnob = host.playerUi.addKnob(
            expRow2, "Dimension D", "Dimension D", 0, 100, expVals[2], 0, MainActivity.TECH_CYAN,
            val -> {
                expVals[2] = val;
                host.getPreferences(MODE_PRIVATE).edit().putInt("expDimensionD", val).apply();
                syncExp.run();
            }
        );
        AudioSlider groovePumpKnob = host.playerUi.addKnob(
            expRow2, "Groove Breather", "Sidechain", 0, 100, expVals[3], 0, MainActivity.SIGNAL_OK,
            val -> {
                expVals[3] = val;
                host.getPreferences(MODE_PRIVATE).edit().putInt("expGroovePump", val).apply();
                syncExp.run();
            }
        );

        host.space(expCard, 8);
        String[] venues = {
            "Desligado · Estúdio Neutro",
            "🚗 Cabine Automotiva Hi-Fi (Cabin Gain < 65 Hz + Vidros)",
            "🏟️ Arena / Festival Open-Air (P.A. Line Array)",
            "🍸 Jazz Club / Lounge Íntimo (Paredes de Madeira)"
        };
        AudioDropdown venuePicker = new AudioDropdown(host, "Acústica Física do Ambiente (Venue)", venues);
        venuePicker.setSelection(expVals[4]);
        expCard.addView(venuePicker);
        venuePicker.setOnSelection(pos -> {
            expVals[4] = pos;
            host.getPreferences(MODE_PRIVATE).edit().putInt("expVenueMode", pos).apply();
            syncExp.run();
            host.status.setText("Ambiente acústico: " + venues[pos]);
        });

        host.space(expCard, 6);
        com.google.android.material.materialswitch.MaterialSwitch vinylSwitch =
            new com.google.android.material.materialswitch.MaterialSwitch(host);
        vinylSwitch.setText("Vitrola Hi-Fi Technics SL-1200 (Cápsula Ortofon + 36 Hz Warmth)");
        vinylSwitch.setTextColor(host.TEXT);
        vinylSwitch.setChecked(expFlags[0]);
        expCard.addView(vinylSwitch);
        vinylSwitch.setOnCheckedChangeListener((btn, on) -> {
            expFlags[0] = on;
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("expTurntableVinyl", on).apply();
            syncExp.run();
            host.status.setText(on ? "Simulador Technics SL-1200 ativo" : "Simulador de vinil desligado");
        });

        com.google.android.material.materialswitch.MaterialSwitch codecSwitch =
            new com.google.android.material.materialswitch.MaterialSwitch(host);
        codecSwitch.setText("Codec-Care Automático (De-Quantizer & DSEE ao detectar MP3/AAC/Opus)");
        codecSwitch.setTextColor(host.TEXT);
        codecSwitch.setChecked(expFlags[1]);
        expCard.addView(codecSwitch);
        codecSwitch.setOnCheckedChangeListener((btn, on) -> {
            expFlags[1] = on;
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("expCodecAdaptive", on).apply();
            syncExp.run();
            host.status.setText(on ? "Codec-Care automático ativado para arquivos com perdas" : "Codec-Care automático desligado");
        });

        host.space(expCard, 6);
        LinearLayout expPresets = host.row();
        expCard.addView(expPresets);
        Button clearExp = host.pill("Zerar Experiências", false);
        Button sweetSpotExp = host.pill("✦ Experiência Suprema", false);
        expPresets.addView(clearExp, host.weighted());
        expPresets.addView(sweetSpotExp, host.weighted());
        clearExp.setOnClickListener(v -> {
            subTactileKnob.setProgress(0);
            haasAdtKnob.setProgress(0);
            dimDKnob.setProgress(0);
            groovePumpKnob.setProgress(0);
            venuePicker.setSelection(0);
            vinylSwitch.setChecked(false);
            host.status.setText("Experiências sensoriais zeradas");
        });
        sweetSpotExp.setOnClickListener(v -> {
            subTactileKnob.setProgress(45);
            haasAdtKnob.setProgress(40);
            dimDKnob.setProgress(50);
            groovePumpKnob.setProgress(30);
            host.status.setText("Experiência Suprema ativa (Sub Tátil + Haas ADT + Dimension D + Groove)");
        });

        host.space(spatialPanel, 12);
    }
}
