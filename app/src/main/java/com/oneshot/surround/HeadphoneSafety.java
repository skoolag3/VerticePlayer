package com.oneshot.surround;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** System noisy warning plus device removal protection, owned by the playback service. */
final class HeadphoneSafety {
    private final Context context;
    private final AudioManager manager;
    private final Runnable disconnect;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) disconnect.run();
        }
    };
    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            for (AudioDeviceInfo device : removed) if (device.isSink() && isHeadphone(device.getType())) {
                disconnect.run(); break;
            }
        }
    };
    HeadphoneSafety(Context context, Runnable disconnect) {
        this.context=context; this.disconnect=disconnect;
        manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
    }
    void start() {
        IntentFilter filter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver,filter);
        if(manager!=null) manager.registerAudioDeviceCallback(devices,new Handler(Looper.getMainLooper()));
    }
    void close() {
        context.unregisterReceiver(receiver);
        if(manager!=null) manager.unregisterAudioDeviceCallback(devices);
    }
    static boolean isHeadphone(int type) {
        return type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            || type==AudioDeviceInfo.TYPE_WIRED_HEADSET || type==AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            || type==AudioDeviceInfo.TYPE_USB_HEADSET || type==AudioDeviceInfo.TYPE_USB_DEVICE
            || type==26 || type==27 || type==30 || type==23;
    }
}
