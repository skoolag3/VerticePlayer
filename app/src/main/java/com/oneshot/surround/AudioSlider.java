package com.oneshot.surround;

import com.google.android.material.slider.Slider;

/** Material slider with integer progress for the player's existing DSP settings. */
final class AudioSlider {
    final Slider view;
    AudioSlider(MainActivity host, int range, int initial) {
        view = new Slider(host);
        view.setValueFrom(0); view.setValueTo(Math.max(1, range)); view.setStepSize(1);
        setProgress(initial);
    }
    int getProgress() { return Math.round(view.getValue()); }
    void setProgress(int value) { view.setValue(Math.max(0,Math.min(view.getValueTo(),value))); }
}
