package com.oneshot.surround;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.oneshot.surround.MainActivity.ValueChanged;
import java.util.Locale;

/** Shared studio UI builders, dynamic cover palette extractor, and two-way Knob bindings. */
final class PlayerUi {
    private final MainActivity host;
    private Bitmap lastThemedCover;

    PlayerUi(MainActivity host) {
        this.host = host;
    }

    AudioSlider addSlider(LinearLayout parent, String name, int minimum, int maximum, int initial, ValueChanged callback) {
        LinearLayout header = host.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(header);
        host.label(header, name, 12.5f, host.TEXT, true);
        header.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        TextView amount = host.label(header, formatSlider(name, initial), 12, host.TECH_CYAN, true);
        amount.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        AudioSlider slider = new AudioSlider(host, maximum - minimum, initial - minimum);
        slider.view.setContentDescription(name);
        slider.view.setLabelFormatter(value -> formatSlider(name, minimum + Math.round(value)));
        slider.view.setTrackActiveTintList(ColorStateList.valueOf(host.ACCENT));
        slider.view.setTrackInactiveTintList(ColorStateList.valueOf(0xFF232736));
        slider.view.setThumbTintList(ColorStateList.valueOf(host.ACCENT));
        slider.view.setTrackHeight(dp(4));
        slider.view.setThumbRadius(dp(7));
        parent.addView(slider.view);

        slider.view.addOnChangeListener((bar, progress, user) -> {
            int value = minimum + Math.round(progress);
            amount.setText(formatSlider(name, value));
            if (user) callback.onChange(value);
        });
        return slider;
    }

    /**
     * Adds a studio RotaryKnobView into row while backing it with an AudioSlider so
     * EffectsController presets and persistence remain 100% compatible.
     */
    AudioSlider addKnob(LinearLayout row, String knobTitle, String sliderName,
                        int minimum, int maximum, int initial, int defaultValue,
                        int accentColor, ValueChanged callback) {
        AudioSlider slider = new AudioSlider(host, maximum - minimum, initial - minimum);
        RotaryKnobView knob = new RotaryKnobView(host, knobTitle, minimum, maximum, initial, defaultValue);
        knob.setAccentColor(accentColor);
        knob.setFormatter(val -> formatSlider(sliderName, val));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(112), 1);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        row.addView(knob, lp);

        final boolean[] syncing = new boolean[1];
        knob.setOnRawProgressChanged(raw -> {
            syncing[0] = true;
            slider.setProgress(raw);
            syncing[0] = false;
            callback.onChange(minimum + raw);
        });

        slider.view.addOnChangeListener((bar, progress, user) -> {
            if (syncing[0]) return;
            knob.setRawProgress(Math.round(progress));
        });
        return slider;
    }

    String formatSlider(String name, int value) {
        if (name.equals("Velocidade")) return String.format(Locale.ROOT, "%.2fx", value / 100f);
        if (name.equals("Pré-amplificação") || name.equals("Bass") || name.equals("Treble") || name.endsWith("Hz")) {
            return (value > 0 ? "+" : "") + value + " dB";
        }
        return value + "%";
    }

    String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    int dp(int size) {
        return Math.round(size * host.getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(host);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(host);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    void space(LinearLayout parent, int height) {
        parent.addView(new View(host), new LinearLayout.LayoutParams(1, dp(height)));
    }

    TextView label(LinearLayout parent, String value, float size, int color, boolean bold) {
        TextView text = new TextView(host);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setPadding(0, dp(2), 0, dp(2));
        parent.addView(text);
        return text;
    }

    TextView label(LinearLayout parent, String value, int size, int color, boolean bold) {
        return label(parent, value, (float) size, color, bold);
    }

    TextView monoBadge(LinearLayout parent, String value, int tintColor) {
        TextView badge = new TextView(host);
        badge.setText(value);
        badge.setTextSize(10.5f);
        badge.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        badge.setTextColor(tintColor);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(dp(8), dp(4), dp(8), dp(4));
        badge.setMinHeight(dp(24));
        int fill = (0x18 << 24) | (tintColor & 0x00FFFFFF);
        int stroke = (0x45 << 24) | (tintColor & 0x00FFFFFF);
        badge.setBackground(borderedBackground(fill, stroke, 6));
        parent.addView(badge);
        return badge;
    }

    GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    GradientDrawable borderedBackground(int color, int strokeColor, int radius) {
        GradientDrawable drawable = background(color, radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        com.google.android.material.shape.MaterialShapeDrawable drawable = new com.google.android.material.shape.MaterialShapeDrawable(
            com.google.android.material.shape.ShapeAppearanceModel.builder().setAllCornerSizes(dp(16)).build());
        drawable.setFillColor(ColorStateList.valueOf(MainActivity.SURFACE));
        drawable.setStroke(dp(1), MainActivity.BORDER);
        view.setBackground(drawable);
        view.setForeground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1E9D7BFF), null,
            new com.google.android.material.shape.MaterialShapeDrawable(drawable.getShapeAppearanceModel())));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(10));
        view.setLayoutParams(params);
        return view;
    }

    Button pill(String value, boolean selected) {
        com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(host);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(selected ? host.BACKGROUND : host.TEXT);
        button.setCornerRadius(dp(12));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(12), 0, dp(12), 0);
        MaterialUi.buttonState(button, selected);
        if (value.equals("☰") || value.equals("⚙")) {
            button.setText("");
            button.setIconResource(value.equals("☰") ? R.drawable.ic_menu : R.drawable.ic_settings);
            button.setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
            button.setIconPadding(0);
            button.setIconSize(dp(20));
            button.setContentDescription(value.equals("☰") ? "Abrir o menu" : "Opções da biblioteca");
        }
        return button;
    }

    SpannableString richModeText(String title, String sub) {
        String full = title + "\n" + sub;
        SpannableString ss = new SpannableString(full);
        int split = title.length();
        ss.setSpan(new StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new RelativeSizeSpan(0.76f), split + 1, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    Button transportButton(String value, boolean primary) {
        Button button = new TransportButton(host);
        button.setText(value);
        button.setTextColor(primary ? host.BACKGROUND : host.TEXT);
        button.setContentDescription(primary ? "Reproduzir ou pausar" : value.contains("❮") ? "Faixa anterior" : "Próxima faixa");
        button.setTextSize(primary ? 20 : 16);
        button.setMinWidth(dp(48));
        ((com.google.android.material.button.MaterialButton) button).setCornerRadius(dp(primary ? 36 : 16));
        MaterialUi.buttonState(button, primary);
        return button;
    }

    Button miniTransportToggle(String value) {
        Button button = new TransportButton(host);
        button.setText(value);
        button.setTextColor(host.MUTED);
        MaterialUi.buttonState(button, false);
        button.setContentDescription(value.contains("↻") ? "Alternar repetição" : "Alternar ordem aleatória");
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    void addBackHeader(LinearLayout panel, String heading, Runnable onBack) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = pill("‹ Voltar", false);
        back.setTextSize(12);
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setOnClickListener(v -> onBack.run());
        bar.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        TextView titleView = label(bar, heading, 16, host.TEXT, true);
        titleView.setPadding(dp(12), 0, 0, 0);
        panel.addView(bar);
        space(panel, 10);
    }

    LinearLayout hubTile(String heading, String sub, Runnable action) {
        LinearLayout tile = card();
        tile.setPadding(dp(14), dp(14), dp(14), dp(14));
        tile.setMinimumHeight(dp(108));
        int icon = heading.contains("Equalizador") || heading.contains("CludesAmp") ? R.drawable.ic_equalizer
            : heading.contains("Espaço") ? R.drawable.ic_surround : R.drawable.ic_headphones;
        ImageView glyph = new ImageView(host);
        glyph.setImageResource(icon);
        glyph.setImageTintList(ColorStateList.valueOf(heading.contains("Saída") ? MainActivity.TECH_CYAN : MainActivity.ACCENT));
        tile.addView(glyph, new LinearLayout.LayoutParams(dp(28), dp(34)));
        label(tile, heading, 14, host.TEXT, true);
        label(tile, sub, 11.5f, host.MUTED, false);
        tile.setOnClickListener(v -> action.run());
        return tile;
    }

    LinearLayout.LayoutParams hubTileParams(boolean left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(left ? 0 : dp(5), 0, left ? dp(5) : 0, dp(10));
        return p;
    }

    LinearLayout hubRow(String heading, String sub, Runnable action) {
        LinearLayout item = card();
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(r);
        LinearLayout textCol = column();
        r.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        label(textCol, heading, 14, host.TEXT, true);
        label(textCol, sub, 11.5f, host.MUTED, false);
        label(r, "›", 18, host.MUTED, true);
        item.setOnClickListener(v -> action.run());
        return item;
    }

    /** Lightweight vibrant album accent extraction without external Palette dependency. */
    int extractDynamicAccent(Bitmap cover) {
        if (cover == null || cover.isRecycled() || cover.getWidth() <= 0 || cover.getHeight() <= 0) {
            return MainActivity.ACCENT;
        }
        try {
            Bitmap tiny = Bitmap.createScaledBitmap(cover, 16, 16, true);
            float[] hsv = new float[3];
            float bestScore = -1f;
            int bestColor = MainActivity.ACCENT;
            for (int y = 0; y < tiny.getHeight(); y++) {
                for (int x = 0; x < tiny.getWidth(); x++) {
                    int px = tiny.getPixel(x, y);
                    if (Color.alpha(px) < 128) continue;
                    Color.colorToHSV(px, hsv);
                    float sat = hsv[1];
                    float val = hsv[2];
                    if (sat < 0.24f || val < 0.25f) continue;
                    float score = sat * 1.4f + (1f - Math.abs(val - 0.68f));
                    if (score > bestScore) {
                        bestScore = score;
                        float[] boosted = {hsv[0], Math.max(0.55f, Math.min(0.88f, sat * 1.15f)), Math.max(0.76f, Math.min(0.95f, val * 1.1f))};
                        bestColor = Color.HSVToColor(boosted);
                    }
                }
            }
            if (tiny != cover) tiny.recycle();
            return bestColor;
        } catch (Throwable ignored) {
            return MainActivity.ACCENT;
        }
    }

    void syncArtworkTheme(Bitmap cover) {
        if (cover == lastThemedCover && cover != null) return;
        lastThemedCover = cover;
        int accent = extractDynamicAccent(cover);
        host.dynamicAccent = accent;

        if (host.miniArtwork != null) {
            if (cover != null && !cover.isRecycled()) {
                host.miniArtwork.setImageBitmap(cover);
            } else {
                host.miniArtwork.setImageResource(R.drawable.ic_app);
            }
        }
        if (host.artwork != null) {
            host.artwork.setAccentColor(accent);
        }
        if (host.progress != null) {
            host.progress.setProgressTintList(ColorStateList.valueOf(accent));
            host.progress.setThumbTintList(ColorStateList.valueOf(accent));
        }
        if (host.miniProgressFill != null) {
            host.miniProgressFill.setBackgroundColor(accent);
        }
        updateTechHeader();
    }

    void syncMiniPlayerFromMain() {
        if (host.miniTitle != null && host.title != null) {
            host.miniTitle.setText(host.title.getText());
        }
        if (host.miniArtist != null && host.artist != null) {
            host.miniArtist.setText(host.artist.getText());
        }
        if (host.miniPlayButton != null && host.playButton != null) {
            host.miniPlayButton.setText(host.playButton.getText());
            MaterialUi.buttonState(host.miniPlayButton, host.playing);
        }
        Bitmap cover = PlaybackService.isJamGuest()
            ? PlaybackService.getCurrentArtwork()
            : (host.currentIndex >= 0 && host.currentIndex < host.queue.size()
                ? host.queue.get(host.currentIndex).artwork
                : null);
        if (cover == null) cover = PlaybackService.getCurrentArtwork();
        if (cover != null) syncArtworkTheme(cover);
        refreshMiniVisibility();
    }

    void refreshMiniVisibility() {
        if (host.miniPlayerBar == null) return;
        boolean hasTrack = host.currentIndex >= 0 || PlaybackService.isJamGuest()
            || (host.player != null && host.player.isRunning());
        boolean show = host.activeRootTab != 0 && hasTrack;
        host.miniPlayerBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    void updateMiniProgress(long positionMs, long durationMs) {
        if (host.miniProgressFill == null || host.miniPlayerBar == null || host.miniPlayerBar.getVisibility() != View.VISIBLE) return;
        int fullWidth = host.miniPlayerBar.getWidth();
        if (fullWidth <= 0) return;
        float ratio = durationMs > 0 ? Math.max(0f, Math.min(1f, (float) positionMs / durationMs)) : 0f;
        ViewGroup.LayoutParams lp = host.miniProgressFill.getLayoutParams();
        int targetW = Math.round(fullWidth * ratio);
        if (lp.width != targetW) {
            lp.width = targetW;
            host.miniProgressFill.setLayoutParams(lp);
        }
    }

    void updateTechHeader() {
        if (host.techHeader == null) return;
        String route = host.outputRoute != null && host.outputRoute.getText() != null
            ? host.outputRoute.getText().toString().replace("●", "").trim()
            : "Android Audio";
        if (route.contains("Android durante") || route.contains("Detectando")) route = "Saída padrão";
        if (route.length() > 18) route = route.replace(" disponível", "").replace(" estéreo", "");

        String src = host.player != null ? host.player.getSourceBadge() : "";
        String codecShort = src.contains("·") ? src.substring(0, src.indexOf('·')).trim() : "PCM";
        if (codecShort.isEmpty() || codecShort.startsWith("Fonte")) codecShort = "64-BIT";

        String fmt = host.player != null ? host.player.getFormatInfo() : "";
        String rateTag = "";
        int hzIdx = fmt.indexOf(" Hz");
        if (hzIdx > 0) {
            int start = fmt.lastIndexOf(' ', hzIdx - 1);
            if (start >= 0) {
                try {
                    int hz = Integer.parseInt(fmt.substring(start + 1, hzIdx).trim());
                    rateTag = (hz % 1000 == 0) ? (hz / 1000) + "kHz " : String.format(Locale.ROOT, "%.1fkHz ", hz / 1000.0);
                } catch (NumberFormatException ignored) { }
            }
        }

        boolean bypassed = host.compareOutputToggle != null && host.compareOutputToggle.isChecked();
        String dspState = bypassed ? "BYPASS A/B" : host.modeName().toUpperCase(Locale.ROOT);
        host.techHeader.setText(route + " · " + rateTag + codecShort + " · " + dspState);

        if (host.techDot != null) {
            int dotColor = bypassed ? MainActivity.SIGNAL_WARN
                : (host.mode != SpatialProcessor.OFF ? MainActivity.TECH_CYAN : MainActivity.SIGNAL_OK);
            host.techDot.setBackground(background(dotColor, 99));
        }
    }
}
