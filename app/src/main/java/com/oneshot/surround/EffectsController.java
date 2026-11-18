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

import static android.content.Context.*;
import static com.oneshot.surround.MainActivity.*;
import com.oneshot.surround.MainActivity.ThumbnailRequest;
import com.oneshot.surround.MainActivity.ValueChanged;

/** Extracted responsibility of the player activity. */
final class EffectsController {
    private final MainActivity host;
    EffectsController(MainActivity host) { this.host = host; }

    void chooseMode(int nextMode) {
        host.mode = nextMode;
        host.player.setSpatial(host.mode, host.intensity);
        host.getPreferences(MODE_PRIVATE).edit().putInt("spatialMode", host.mode).apply();
        for (Button button : new Button[]{host.wideButton, host.roomButton, host.orbitButton, host.offButton, host.frontButton, host.surroundButton, host.theaterButton, host.ambioButton, host.holoButton})
            if (button != null) MaterialUi.buttonState(button, button == host.selectedButton());
        host.status.setText(host.mode == SpatialProcessor.OFF ? "Efeito desligado • compare o mesmo trecho" : "DSP ativo • " + host.modeName());
        host.refreshOutputBadges();
    }

    Button selectedButton() {
        if (host.mode == SpatialProcessor.WIDE) return host.wideButton;
        if (host.mode == SpatialProcessor.ROOM) return host.roomButton;
        if (host.mode == SpatialProcessor.ORBIT) return host.orbitButton;
        if (host.mode == SpatialProcessor.FRONT) return host.frontButton;
        if (host.mode == SpatialProcessor.SURROUND) return host.surroundButton;
        if (host.mode == SpatialProcessor.THEATER) return host.theaterButton;
        if (host.mode == SpatialProcessor.AMBIO) return host.ambioButton;
        if (host.mode == SpatialProcessor.HOLOGRAPHIC) return host.holoButton;
        return host.offButton;
    }

    String modeName() {
        if (host.mode == SpatialProcessor.OFF) return "Desligado";
        if (host.mode == SpatialProcessor.WIDE) return "Amplo";
        if (host.mode == SpatialProcessor.ORBIT) return "Órbita 8D";
        if (host.mode == SpatialProcessor.FRONT) return "Crossfeed Monitor";
        if (host.mode == SpatialProcessor.SURROUND) return "Surround";
        if (host.mode == SpatialProcessor.THEATER) return "Cinema";
        if (host.mode == SpatialProcessor.AMBIO) return "Ambio RACE";
        if (host.mode == SpatialProcessor.HOLOGRAPHIC) return "Holográfico 3D";
        return "Sala 3D";
    }

    void refreshRoute() {
        AudioManager manager = (AudioManager)host.getSystemService(AUDIO_SERVICE);
        String route = "Saída selecionada pelo Android durante a reprodução";
        if (manager != null) {
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET) { route = "●  Fone Bluetooth estéreo disponível"; break; }
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) route = "●  Bluetooth em modo chamada/mono";
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET) route = "●  Fone com fio disponível";
            }
        }
        host.outputRoute.setText(route);
    }

    void refreshOutputBadges() {
        if (host.sourceBadgeView != null) host.sourceBadgeView.setText(host.player.getSourceBadge());
        if (host.rendererBadgeView != null) host.rendererBadgeView.setText(host.player.getRendererBadge());
        if (host.dspStatus != null) host.dspStatus.setText(host.compareOutputToggle != null && host.compareOutputToggle.isChecked()
            ? "DSP em comparação: efeitos desativados temporariamente"
            : "DSP: 3D " + (host.mode == SpatialProcessor.OFF ? "off" : "on")
                + " · EQ " + (host.eqToggle != null && host.eqToggle.isChecked() ? "on" : "off")
                + " · Cludes " + (host.cludesToggle != null && host.cludesToggle.isChecked() ? "on" : "off"));
    }

    void updateCludes() {
        if (host.cludesToggle == null) return;
        boolean enabled = host.cludesToggle.isChecked();
        host.player.setCludes(enabled, host.cludesVolume, host.cludesWidth, host.cludesBass, host.cludesTreble,
            host.cludesClarity, host.cludesLoudness, host.cludesEnhancer, host.cludesBands);
        if (host.cludesReverbBar != null) host.player.setReverb(enabled ? host.cludesReverbBar.getProgress() : 0);
        if (host.cludesSpeedBar != null) host.player.setSpeed(enabled ? (host.cludesSpeedBar.getProgress() + 50) / 100f : 1f,
            !enabled || host.pitchToggle == null || host.pitchToggle.isChecked());
        if (!enabled && host.cludesOrbit) {
            host.cludesOrbit = false;
            host.chooseMode(host.previousSpatialMode);
        }
        if (enabled && host.cludesOrbitPreset && !host.cludesOrbit) {
            host.previousSpatialMode = host.mode;
            host.cludesOrbit = true;
            host.chooseMode(SpatialProcessor.ORBIT);
        }
        android.content.SharedPreferences.Editor editor = host.getPreferences(MODE_PRIVATE).edit();
        editor.putBoolean("cludesEnabled", host.cludesToggle.isChecked())
            .putInt("cludesVolume", host.cludesVolume).putInt("cludesWidth", host.cludesWidth)
            .putInt("cludesBass", host.cludesBass)
            .putInt("cludesTreble", host.cludesTreble).putInt("cludesClarity", host.cludesClarity)
            .putBoolean("cludesLoudness", host.cludesLoudness).putBoolean("cludesEnhancer", host.cludesEnhancer);
        for (int index = 0; index < 5; index++) editor.putInt("cludesBand" + index, (int)host.cludesBands[index]);
        editor.apply();
        host.refreshOutputBadges();
    }

    void applyCludesPreset(int preset) {
        for (int index = 0; index < host.presetTiles.length; index++)
            if (host.presetTiles[index] != null) host.presetTiles[index].setSelected(index == preset);
        host.cludesVolume = 100;
        host.cludesWidth = 100;
        host.cludesBass = 0;
        host.cludesTreble = 0;
        host.cludesClarity = 0;
        host.cludesLoudness = false;
        host.cludesEnhancer = false;
        for (int index = 0; index < 5; index++) host.cludesBands[index] = 0;
        int reverb = 0;
        int speed = 100;
        boolean keepPitch = true;
        if (preset == 1) { host.cludesBass = 8; host.cludesEnhancer = true; host.cludesBands[0] = 5; host.cludesBands[1] = 3; }
        if (preset == 2) { speed = 80; reverb = 48; keepPitch = false; }
        if (preset == 3) { speed = 125; host.cludesTreble = 3; host.cludesClarity = 25; keepPitch = false; }
        if (preset == 4) { speed = 95; host.cludesBass = 2; host.cludesTreble = -5; host.cludesBands[3] = -2; keepPitch = false; }
        if (host.cludesOrbit) { host.cludesOrbit = false; host.chooseMode(host.previousSpatialMode); }
        host.cludesOrbitPreset = preset == 5;
        host.cludesToggle.setChecked(preset > 0);
        host.cludesVolumeBar.setProgress(host.cludesVolume);
        host.cludesWidthBar.setProgress(host.cludesWidth);
        host.cludesBassBar.setProgress(host.cludesBass + 12);
        host.cludesTrebleBar.setProgress(host.cludesTreble + 12);
        host.cludesClarityBar.setProgress(host.cludesClarity);
        host.loudnessToggle.setChecked(host.cludesLoudness);
        host.enhancerToggle.setChecked(host.cludesEnhancer);
        for (int index = 0; index < 5; index++) host.cludesEqBars[index].setProgress((int)host.cludesBands[index] + 12);
        host.cludesReverbBar.setProgress(reverb);
        host.cludesSpeedBar.setProgress(speed - 50);
        host.pitchToggle.setChecked(keepPitch);
        host.getPreferences(MODE_PRIVATE).edit().putInt("reverb", reverb).putInt("speed", speed)
            .putBoolean("keepPitch", keepPitch).apply();
        host.updateCludes();
        host.status.setText("Preset " + new String[]{"Flat", "Bass boost", "Slowed + reverb", "Nightcore", "Lo-fi", "8D"}[preset] + " ativo");
    }

    void saveCludesPreset() {
        android.content.SharedPreferences.Editor editor = host.getPreferences(MODE_PRIVATE).edit();
        editor.putBoolean("savedCludesEnabled", host.cludesToggle.isChecked())
            .putInt("savedCludesVolume", host.cludesVolume).putInt("savedCludesWidth", host.cludesWidth)
            .putInt("savedCludesBass", host.cludesBass)
            .putInt("savedCludesTreble", host.cludesTreble).putInt("savedCludesClarity", host.cludesClarity)
            .putBoolean("savedCludesLoudness", host.cludesLoudness).putBoolean("savedCludesEnhancer", host.cludesEnhancer)
            .putInt("savedReverb", host.cludesReverbBar.getProgress())
            .putInt("savedSpeed", host.cludesSpeedBar.getProgress() + 50)
            .putBoolean("savedPitch", host.pitchToggle.isChecked())
            .putInt("savedSpatialMode", host.cludesOrbit ? host.previousSpatialMode : host.mode)
            .putBoolean("savedCludesOrbit", host.cludesOrbitPreset)
            .putBoolean("hasSavedCludes", true);
        for (int index = 0; index < 5; index++) editor.putInt("savedCludesBand" + index, (int)host.cludesBands[index]);
        editor.apply();
        host.status.setText("Ajuste CludesAmp salvo");
    }

    void loadCludesPreset() {
        android.content.SharedPreferences prefs = host.getPreferences(MODE_PRIVATE);
        if (!prefs.getBoolean("hasSavedCludes", false)) { host.status.setText("Nenhum ajuste salvo ainda"); return; }
        host.cludesVolume = prefs.getInt("savedCludesVolume", 100);
        host.cludesWidth = prefs.getInt("savedCludesWidth", 100);
        host.cludesBass = prefs.getInt("savedCludesBass", 0);
        host.cludesTreble = prefs.getInt("savedCludesTreble", 0);
        host.cludesClarity = prefs.getInt("savedCludesClarity", 0);
        host.cludesLoudness = prefs.getBoolean("savedCludesLoudness", false);
        host.cludesEnhancer = prefs.getBoolean("savedCludesEnhancer", false);
        host.cludesOrbitPreset = prefs.getBoolean("savedCludesOrbit", false);
        if (host.cludesOrbit) { host.cludesOrbit = false; host.chooseMode(host.previousSpatialMode); }
        host.chooseMode(prefs.getInt("savedSpatialMode", SpatialProcessor.OFF));
        for (int index = 0; index < 5; index++) host.cludesBands[index] = prefs.getInt("savedCludesBand" + index, 0);
        host.cludesToggle.setChecked(prefs.getBoolean("savedCludesEnabled", true));
        host.cludesVolumeBar.setProgress(host.cludesVolume);
        host.cludesWidthBar.setProgress(host.cludesWidth);
        host.cludesBassBar.setProgress(host.cludesBass + 12);
        host.cludesTrebleBar.setProgress(host.cludesTreble + 12);
        host.cludesClarityBar.setProgress(host.cludesClarity);
        host.loudnessToggle.setChecked(host.cludesLoudness);
        host.enhancerToggle.setChecked(host.cludesEnhancer);
        for (int index = 0; index < 5; index++) host.cludesEqBars[index].setProgress((int)host.cludesBands[index] + 12);
        int reverb = prefs.getInt("savedReverb", 0);
        int speed = prefs.getInt("savedSpeed", 100);
        boolean pitch = prefs.getBoolean("savedPitch", true);
        host.cludesReverbBar.setProgress(reverb);
        host.cludesSpeedBar.setProgress(speed - 50);
        host.pitchToggle.setChecked(pitch);
        host.getPreferences(MODE_PRIVATE).edit().putInt("reverb", reverb).putInt("speed", speed)
            .putBoolean("keepPitch", pitch).apply();
        host.updateCludes();
        host.status.setText("Ajuste CludesAmp restaurado");
    }

    void saveCustomEq() {
        android.content.SharedPreferences.Editor editor = host.getPreferences(MODE_PRIVATE).edit();
        for (int index = 0; index < 10; index++) editor.putInt("custom" + index, (int)host.eqGains[index]);
        editor.apply();
    }
}
