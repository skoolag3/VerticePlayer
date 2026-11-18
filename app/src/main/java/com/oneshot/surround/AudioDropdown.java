package com.oneshot.surround;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import java.util.function.IntConsumer;

/** Material exposed dropdown with explicit studio dark box sizing so labels never clip. */
final class AudioDropdown extends TextInputLayout {
    private final MaterialAutoCompleteTextView input;
    private final String[] options;
    private IntConsumer onSelection;

    AudioDropdown(Context context, String label, String[] options) {
        super(context, null, com.google.android.material.R.attr.textInputStyle);
        this.options = options;
        float density = context.getResources().getDisplayMetrics().density;
        int dp12 = Math.round(12 * density);
        int dp14 = Math.round(14 * density);
        int dp54 = Math.round(54 * density);

        setBoxBackgroundMode(BOX_BACKGROUND_OUTLINE);
        setBoxBackgroundColor(MainActivity.SURFACE_TOP);
        float radius = 12 * density;
        setBoxCornerRadii(radius, radius, radius, radius);
        ColorStateList strokeStates = new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
            new int[]{MainActivity.ACCENT, MainActivity.BORDER}
        );
        setBoxStrokeColorStateList(strokeStates);
        setDefaultHintTextColor(ColorStateList.valueOf(MainActivity.MUTED));
        setEndIconTintList(ColorStateList.valueOf(MainActivity.ACCENT));
        setHint(label);
        setEndIconMode(END_ICON_DROPDOWN_MENU);

        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        outer.topMargin = Math.round(6 * density);
        outer.bottomMargin = Math.round(8 * density);
        setLayoutParams(outer);

        input = new MaterialAutoCompleteTextView(getContext());
        input.setInputType(0);
        input.setMinHeight(dp54);
        input.setMinimumHeight(dp54);
        input.setPadding(dp14, dp12, dp14, dp12);
        input.setTextSize(13.5f);
        input.setTextColor(MainActivity.TEXT);
        input.setDropDownBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF1A1D28));
        input.setSingleLine(false);
        input.setMaxLines(2);
        input.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_dropdown_item_1line, options));
        addView(input, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        input.setOnItemClickListener((parent, view, position, id) -> {
            if (onSelection != null) onSelection.accept(position);
        });
    }

    void setSelection(int selected) {
        int value = Math.max(0, Math.min(options.length - 1, selected));
        input.setText(options[value], false);
        if (onSelection != null) onSelection.accept(value);
    }

    void setOnSelection(IntConsumer listener) {
        onSelection = listener;
    }
}
