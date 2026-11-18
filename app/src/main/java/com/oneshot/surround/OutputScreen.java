package com.oneshot.surround;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.checkbox.MaterialCheckBox;

import static android.content.Context.MODE_PRIVATE;

/** Builds the Audiophile Signal Path Diagram, Mastering Processors, and Output Hardware Renderer screen. */
final class OutputScreen {
    private final MainActivity host;

    OutputScreen(MainActivity host) {
        this.host = host;
    }

    void build(LinearLayout outputPanel) {
        // 1. Signal Path Card (Roon / Neutron style pipeline diagram)
        LinearLayout signalCard = host.card();
        outputPanel.addView(signalCard);

        LinearLayout signalHeader = host.row();
        signalHeader.setGravity(Gravity.CENTER_VERTICAL);
        signalCard.addView(signalHeader);
        TextView sigTitle = host.label(signalHeader, "SIGNAL PATH · FLUXO DE ÁUDIO", 11, MainActivity.TECH_CYAN, true);
        sigTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        signalHeader.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        host.playerUi.monoBadge(signalHeader, "FLOAT64 ENGINE", MainActivity.SIGNAL_OK);

        host.space(signalCard, 8);

        // Node 1: Source
        View sourceDot = addSignalDotRow(signalCard, "FONTE / CODEC");
        LinearLayout sourceNode = (LinearLayout) signalCard.getChildAt(signalCard.getChildCount() - 1);
        host.sourceBadgeView = host.label(sourceNode, host.player.getSourceBadge(), 12, MainActivity.TEXT, true);
        host.sourceBadgeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        addConnector(signalCard, "decodificação FFmpeg → stereo float64");

        // Node 2: DSP Engine
        View dspDot = addSignalDotRow(signalCard, "MOTOR INTERNO");
        LinearLayout dspNode = (LinearLayout) signalCard.getChildAt(signalCard.getChildCount() - 1);
        TextView dspPipeBadge = host.label(dspNode, "DSP float64 · 64-bit interno", 12, MainActivity.TECH_CYAN, true);
        dspPipeBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        addConnector(signalCard, "limiter True-Peak + modelagem analógica + dither");

        // Node 3: Output Renderer
        View outDot = addSignalDotRow(signalCard, "SAÍDA ANDROID");
        LinearLayout outNode = (LinearLayout) signalCard.getChildAt(signalCard.getChildCount() - 1);
        host.rendererBadgeView = host.label(outNode, host.player.getRendererBadge(), 12, MainActivity.TEXT, true);
        host.rendererBadgeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        host.space(signalCard, 8);
        host.outputRouteDetails = host.label(signalCard, "Rota ativa: aguardando reprodução", 11.5f, MainActivity.TEXT, true);
        host.outputRouteDetails.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        host.dspStatus = host.label(signalCard, "", 11.5f, MainActivity.MUTED, false);
        host.dspStatus.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        host.outputDetails = host.label(signalCard, host.player.getFormatInfo(), 11, MainActivity.MUTED, false);
        host.outputDetails.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);

        // Live LED tint watchers for the 3 Signal Path nodes
        host.sourceBadgeView.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                String txt = s.toString();
                int c = txt.contains("sem perdas") ? MainActivity.SIGNAL_OK
                    : txt.contains("com perdas") ? MainActivity.SIGNAL_WARN : MainActivity.MUTED;
                sourceDot.setBackground(host.background(c, 99));
                host.sourceBadgeView.setTextColor(txt.contains("aguardando") ? MainActivity.MUTED : c);
            }
        });
        host.rendererBadgeView.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                String txt = s.toString();
                int c = txt.contains("float32") ? MainActivity.SIGNAL_OK
                    : txt.contains("PCM16") ? MainActivity.ACCENT : MainActivity.MUTED;
                outDot.setBackground(host.background(c, 99));
                host.rendererBadgeView.setTextColor(txt.contains("aguardando") ? MainActivity.MUTED : MainActivity.TEXT);
            }
        });
        host.dspStatus.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                boolean bypass = s.toString().contains("comparação");
                int c = bypass ? MainActivity.SIGNAL_WARN : MainActivity.TECH_CYAN;
                dspDot.setBackground(host.background(c, 99));
                dspPipeBadge.setText(bypass ? "BYPASS A/B · sem efeitos" : "DSP float64 · 64-bit interno");
                dspPipeBadge.setTextColor(c);
            }
        });

        // 2. Audiophile Mastering & Analog Coloration Card
        LinearLayout masterCard = host.card();
        outputPanel.addView(masterCard);
        TextView mstTitle = host.label(masterCard, "MASTERING & PROCESSADORES DE SAÍDA", 11, MainActivity.TECH_CYAN, true);
        mstTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        // Item 2: DAC Reconstruction Filter Emulation (Chord / AKM / R2R NOS)
        String[] dacFilters = {
            "Linear Phase · Fast Roll-Off (Palco Cirúrgico)",
            "Minimum Phase · Zero Pre-Ringing (Ataque Natural)",
            "R2R Ladder NOS · Non-Oversampling (Textura Orgânica)"
        };
        int savedDacFilter = host.getPreferences(MODE_PRIVATE).getInt("dacFilterMode", 0);
        AudioDropdown dacFilterPicker = new AudioDropdown(host, "Filtro Digital de Reconstrução DAC", dacFilters);
        dacFilterPicker.setSelection(savedDacFilter);
        host.player.setDacFilterMode(savedDacFilter);
        masterCard.addView(dacFilterPicker);
        dacFilterPicker.setOnSelection(pos -> {
            host.getPreferences(MODE_PRIVATE).edit().putInt("dacFilterMode", pos).apply();
            host.player.setDacFilterMode(pos);
            host.status.setText("Filtro DAC: " + dacFilters[pos]);
        });

        host.space(masterCard, 6);

        String[] satModes = {
            "Transparente · Desligado (Bit-Clean)",
            "Válvula Triodo Classe-A (Harmônicos Pares)",
            "Fita Magnética Studer (3ª Ordem + Coesão)",
            "Transformador Neve/Lundahl (Peso nos Graves)"
        };
        int savedSat = host.getPreferences(MODE_PRIVATE).getInt("saturationMode",
            host.getPreferences(MODE_PRIVATE).getBoolean("analogWarmth", false) ? 1 : 0);
        AudioDropdown satPicker = new AudioDropdown(host, "Estágio Analógico de Saída", satModes);
        satPicker.setSelection(savedSat);
        host.player.setSaturationMode(savedSat);
        masterCard.addView(satPicker);
        satPicker.setOnSelection(pos -> {
            host.getPreferences(MODE_PRIVATE).edit()
                .putInt("saturationMode", pos)
                .putBoolean("analogWarmth", pos > 0).apply();
            host.player.setSaturationMode(pos);
            host.status.setText("Estágio analógico: " + satModes[pos]);
        });

        host.space(masterCard, 6);

        // Item 1: Hi-Res Spectral Restorer (Sony DSEE Ultimate style)
        CheckBox hiResToggle = new MaterialCheckBox(host);
        hiResToggle.setText("Hi-Res Spectral Restorer (DSEE · Ar 14–22 kHz)");
        hiResToggle.setTextColor(host.TEXT);
        hiResToggle.setButtonTintList(ColorStateList.valueOf(host.ACCENT));
        boolean savedHiRes = host.getPreferences(MODE_PRIVATE).getBoolean("hiResRestorer", false);
        hiResToggle.setChecked(savedHiRes);
        host.player.setHiResRestorer(savedHiRes);
        masterCard.addView(hiResToggle);
        hiResToggle.setOnCheckedChangeListener((btn, enabled) -> {
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("hiResRestorer", enabled).apply();
            host.player.setHiResRestorer(enabled);
            host.status.setText(enabled ? "Hi-Res Spectral Restorer (14–22 kHz) ativo" : "Hi-Res Spectral Restorer desligado");
        });
        host.label(masterCard, "Reconstrói harmônicos de oitava superior perdidos em arquivos MP3, AAC e Opus.", 11, host.MUTED, false);

        host.space(masterCard, 4);

        CheckBox dynRestoreToggle = new MaterialCheckBox(host);
        dynRestoreToggle.setText("Restaurador Dinâmico & De-Clipper (Transientes)");
        dynRestoreToggle.setTextColor(host.TEXT);
        dynRestoreToggle.setButtonTintList(ColorStateList.valueOf(host.ACCENT));
        boolean savedDyn = host.getPreferences(MODE_PRIVATE).getBoolean("dynamicRestorer", false);
        dynRestoreToggle.setChecked(savedDyn);
        host.player.setDynamicRestorer(savedDyn);
        masterCard.addView(dynRestoreToggle);
        dynRestoreToggle.setOnCheckedChangeListener((btn, enabled) -> {
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("dynamicRestorer", enabled).apply();
            host.player.setDynamicRestorer(enabled);
            host.status.setText(enabled ? "De-Clipper & expansão de transientes ativos" : "Restaurador dinâmico desligado");
        });
        host.label(masterCard, "Recupera o impacto de bateria e picos achatados por masterização alta (Loudness War).", 11, host.MUTED, false);

        host.space(masterCard, 4);

        // Item 7: EBU R128 Continuous Auto-Gain Normalizer
        CheckBox autoGainToggle = new MaterialCheckBox(host);
        autoGainToggle.setText("Normalizador EBU R128 (Auto-Gain entre faixas)");
        autoGainToggle.setTextColor(host.TEXT);
        autoGainToggle.setButtonTintList(ColorStateList.valueOf(host.ACCENT));
        boolean savedAutoGain = host.getPreferences(MODE_PRIVATE).getBoolean("autoGainEnabled", false);
        autoGainToggle.setChecked(savedAutoGain);
        host.player.setAutoGainEnabled(savedAutoGain);
        masterCard.addView(autoGainToggle);
        autoGainToggle.setOnCheckedChangeListener((btn, enabled) -> {
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("autoGainEnabled", enabled).apply();
            host.player.setAutoGainEnabled(enabled);
            host.status.setText(enabled ? "Normalizador EBU R128 Auto-Gain ativo" : "Auto-Gain desligado (volume original)");
        });
        host.label(masterCard, "Iguala suavemente o volume perceptivo entre gravações antigas baixas e masters modernos.", 11, host.MUTED, false);

        host.space(masterCard, 4);

        CheckBox phaseToggle = new MaterialCheckBox(host);
        phaseToggle.setText("Inversão de Polaridade Absoluta (180° Ø)");
        phaseToggle.setTextColor(host.TEXT);
        phaseToggle.setButtonTintList(ColorStateList.valueOf(host.ACCENT));
        boolean savedPhase = host.getPreferences(MODE_PRIVATE).getBoolean("phaseInvert", false);
        phaseToggle.setChecked(savedPhase);
        host.player.setPhaseInvert(savedPhase);
        masterCard.addView(phaseToggle);
        phaseToggle.setOnCheckedChangeListener((btn, enabled) -> {
            host.getPreferences(MODE_PRIVATE).edit().putBoolean("phaseInvert", enabled).apply();
            host.player.setPhaseInvert(enabled);
            host.status.setText(enabled ? "Polaridade absoluta invertida (180°)" : "Polaridade normal (0°)");
        });

        host.space(masterCard, 8);

        String[] ditherProfiles = {
            "Noise-Shaping Psicoacústico 2ª Ordem (Recomendado)",
            "TPDF Triangular Puro (Estúdio Clássico)",
            "Desligado (Truncamento Direto)"
        };
        int savedDither = host.getPreferences(MODE_PRIVATE).getInt("ditherMode", 0);
        AudioDropdown ditherPicker = new AudioDropdown(host, "Quantização & Dither PCM16", ditherProfiles);
        ditherPicker.setSelection(savedDither);
        host.player.setDitherMode(savedDither);
        masterCard.addView(ditherPicker);
        ditherPicker.setOnSelection(pos -> {
            host.getPreferences(MODE_PRIVATE).edit().putInt("ditherMode", pos).apply();
            host.player.setDitherMode(pos);
        });

        // 3. Hardware Output Profile & Reference A/B Card
        LinearLayout configCard = host.card();
        outputPanel.addView(configCard);
        TextView cfgTitle = host.label(configCard, "CONFIGURAÇÃO DE HARDWARE & A/B", 11, MainActivity.ACCENT, true);
        cfgTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        host.compareOutputToggle = new MaterialCheckBox(host);
        host.compareOutputToggle.setText("Comparar sem efeitos (Bypass A/B)");
        host.compareOutputToggle.setTextColor(host.TEXT);
        host.compareOutputToggle.setButtonTintList(ColorStateList.valueOf(host.ACCENT));
        configCard.addView(host.compareOutputToggle);
        host.player.setBypassEffects(false);
        host.compareOutputToggle.setOnCheckedChangeListener((button, bypass) -> {
            host.player.setBypassEffects(bypass);
            host.refreshOutputBadges();
        });
        host.label(configCard, "Desliga temporariamente 3D, EQ, CludesAmp, reverb, saturação e velocidade no mesmo trecho.", 11.5f, host.MUTED, false);

        host.space(configCard, 10);
        String[] profiles = {"Auto · float32 em DAC USB", "PCM 16 bits", "Forçar float32"};
        AudioDropdown outputPicker = new AudioDropdown(host, "Perfil de saída AudioTrack", profiles);
        configCard.addView(outputPicker);
        outputPicker.setSelection(host.getPreferences(MODE_PRIVATE).getInt("outputProfile", 0));
        host.player.setOutputProfile(host.getPreferences(MODE_PRIVATE).getInt("outputProfile", 0));
        outputPicker.setOnSelection(position -> {
            host.player.setOutputProfile(position);
            host.getPreferences(MODE_PRIVATE).edit().putInt("outputProfile", position).apply();
        });
        host.label(configCard, "Aplicado a partir da próxima faixa. Float32 preserva toda a margem dinâmica em DACs compatíveis.", 11.5f, host.MUTED, false);

        host.space(configCard, 8);
        Button codecInfo = host.pill("Ver detalhes FFmpegKit", false);
        configCard.addView(codecInfo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(40)));
        codecInfo.setOnClickListener(view -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
            .setTitle("Decodificação nativa")
            .setMessage("FFmpeg " + com.arthenica.ffmpegkit.FFmpegKitConfig.getFFmpegVersion()
                + " decodifica FLAC, MP3, AAC, Opus, Vorbis, ALAC e outros formatos. Todo DSP roda em float64; saída usa float32 ou PCM16 com Noise-Shaping de 2ª ordem. FFmpegKit: LGPL 3.0.")
            .setPositiveButton("OK", null).show());

        host.space(outputPanel, 12);
    }

    private View addSignalDotRow(LinearLayout parent, String stepLabel) {
        LinearLayout node = host.column();
        node.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
        node.setBackground(host.borderedBackground(MainActivity.SURFACE_TOP, MainActivity.BORDER, 12));
        parent.addView(node, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout top = host.row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        node.addView(top);

        View dot = new View(host);
        dot.setBackground(host.background(MainActivity.TECH_CYAN, 99));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(host.dp(8), host.dp(8));
        dotLp.rightMargin = host.dp(8);
        top.addView(dot, dotLp);

        TextView step = host.label(top, stepLabel, 10, MainActivity.MUTED, true);
        step.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        return dot;
    }

    private void addConnector(LinearLayout parent, String note) {
        TextView arrow = host.label(parent, "  │ ↓  " + note, 10.5f, MainActivity.MUTED, false);
        arrow.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        arrow.setPadding(host.dp(10), host.dp(4), 0, host.dp(4));
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    }
}
