package com.oneshot.surround;

import android.content.res.ColorStateList;
import android.widget.Button;
import com.google.android.material.button.MaterialButton;

final class MaterialUi {
    private MaterialUi() { }
    static void buttonState(Button button, boolean selected) {
        if(button instanceof MaterialButton) {
            MaterialButton material=(MaterialButton)button;
            material.setBackgroundTintList(ColorStateList.valueOf(selected?0xffc5a3f5:0xff211d29));
            material.setStrokeColor(ColorStateList.valueOf(selected?0xffd9bfff:0xff48404f));
            material.setStrokeWidth(selected?0:1);
            if (button instanceof TransportButton && !selected) {
                material.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
                material.setStrokeWidth(0);
            }
            material.setRippleColor(ColorStateList.valueOf(0x44c5a3f5));
            material.setIconTint(ColorStateList.valueOf(selected?0xff27163d:0xffe9e1ed));
        } else button.setBackgroundTintList(ColorStateList.valueOf(selected?0xffc5a3f5:0xff211d29));
        button.setTextColor(selected?0xff27163d:0xffe9e1ed);
    }
}
