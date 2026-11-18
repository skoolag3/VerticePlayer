package com.oneshot.surround;

import android.content.Context;
import android.content.res.ColorStateList;
import com.google.android.material.button.MaterialButton;

/** Standard Material icon button accepting the player's existing state commands. */
public final class TransportButton extends MaterialButton {
    private boolean ready;
    public TransportButton(Context context) {
        super(context); ready=true;
        setIconGravity(ICON_GRAVITY_TEXT_START); setIconPadding(0);
        setIconSize(Math.round(26*getResources().getDisplayMetrics().density));
        setInsetTop(0); setInsetBottom(0); setPadding(0,0,0,0);
        setMinWidth(0); setMinimumWidth(0); setMinHeight(0); setMinimumHeight(0);
    }
    @Override public void setText(CharSequence text, BufferType type) {
        if(!ready) { super.setText(text,type); return; }
        String action=text==null?"":text.toString();
        int icon=action.contains("❚")?R.drawable.ic_pause:action.contains("❮")?R.drawable.ic_previous:
            action.contains("❯")?R.drawable.ic_next:action.contains("↻")?R.drawable.ic_repeat:
            action.contains("⇄")?R.drawable.ic_shuffle:R.drawable.ic_play;
        super.setText(action.contains("1")?"1":"",type);
        setIconResource(icon);
        setContentDescription(action.contains("❚")?"Pausar":action.contains("❮")?"Anterior":
            action.contains("❯")?"Próxima":action.contains("↻")?"Repetição":action.contains("⇄")?"Aleatório":"Reproduzir");
    }
    @Override public void setTextColor(int color) {
        super.setTextColor(color);
        if(ready) setIconTint(ColorStateList.valueOf(color));
    }
}
