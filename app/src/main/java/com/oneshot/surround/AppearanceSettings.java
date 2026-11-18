package com.oneshot.surround;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Visual-only preferences, independent of audio effects, styled as an Audiophile Studio Control Panel. */
final class AppearanceSettings {
    static final String[] DISPLAY_MODES = {
        "Capa", "Órbita 3D"
    };

    static final String[] PROGRESS_MODES = {
        "Simples", "Aurora", "Plasma", "Pulso"
    };

    private final MainActivity host;
    private final SharedPreferences prefs;

    AppearanceSettings(MainActivity host) {
        this.host = host;
        this.prefs = host.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    }

    void apply() {
        if (host.artwork != null) {
            host.artwork.setAppearance(prefs.getInt("coverStyle", defaultCover()), false, false, false);
        }
        if (host.musicBackground != null) host.musicBackground.configure(
            chosenColor(), prefs.getInt("backgroundScene", 0));
        if (host.progress instanceof NeonSeekBar) {
            ((NeonSeekBar) host.progress).setEffect(prefs.getInt("progress", 1));
        }
    }

    private int defaultCover() {
        return prefs.getInt("display", 7) == 7 ? 1 : 0;
    }

    void build(LinearLayout panel) {
        host.space(panel, 10);

        picker(panel, "Capa", "coverStyle", DISPLAY_MODES, defaultCover());
        host.space(panel, 8);
        picker(panel, "Barra de progresso", "progress", PROGRESS_MODES, 1);
        host.space(panel, 8);

        Button choose = host.pill("Cor do fundo", false);
        panel.addView(choose);
        choose.setOnClickListener(v -> chooseColor());
        picker(panel, "Ambiente", "backgroundScene", new String[]{"Cor sólida", "Fluxo musical", "Estrelas", "Ondas circulares"}, 0);
        host.space(panel, 10);
        Button reset = host.pill("Restaurar padrão", false);
        panel.addView(reset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(40)));
        reset.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            apply();
            panel.removeAllViews();
            build(panel);
        });
    }

    private int chosenColor() {
        return prefs.getInt("backgroundRgb", MusicBackground.previousColor(prefs.getInt("backgroundColor", 0)));
    }

    private void chooseColor() {
        LinearLayout content = host.column();
        content.setPadding(host.dp(24), host.dp(8), host.dp(24), 0);
        View preview = new View(host);
        content.addView(preview, new LinearLayout.LayoutParams(-1,host.dp(52)));
        int[] channels = {android.graphics.Color.red(chosenColor()), android.graphics.Color.green(chosenColor()),
            android.graphics.Color.blue(chosenColor())};
        String[] names = {"Vermelho", "Verde", "Azul"};
        preview.setBackgroundColor(chosenColor());
        for (int i = 0; i < channels.length; i++) {
            final int channel = i;
            TextView label = host.label(content,names[i] + " · " + channels[i],14,host.TEXT,false);
            android.widget.SeekBar bar = new android.widget.SeekBar(host);
            bar.setMax(255);
            bar.setProgress(channels[i]);
            bar.setContentDescription(names[i]);
            content.addView(bar, new LinearLayout.LayoutParams(-1,host.dp(44)));
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(android.widget.SeekBar b) {}
                public void onStopTrackingTouch(android.widget.SeekBar b) {}
                public void onProgressChanged(android.widget.SeekBar b,int value,boolean user) {
                    channels[channel] = value;
                    label.setText(names[channel] + " · " + value);
                    preview.setBackgroundColor(android.graphics.Color.rgb(channels[0],channels[1],channels[2]));
                }
            });
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(host).setTitle("Cor do fundo")
            .setView(content).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",(dialog,which) -> {
                prefs.edit().putInt("backgroundRgb",android.graphics.Color.rgb(channels[0],channels[1],channels[2])).apply();
                apply();
            }).show();
    }

    private void picker(LinearLayout panel, String label, String key, String[] options, int defVal) {
        AudioDropdown picker = new AudioDropdown(host, label, options);
        picker.setSelection(prefs.getInt(key, defVal));
        panel.addView(picker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        picker.setOnSelection(position -> {
            prefs.edit().putInt(key, position).apply();
            apply();
        });
    }
}
