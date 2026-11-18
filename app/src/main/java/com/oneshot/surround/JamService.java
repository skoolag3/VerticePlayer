package com.oneshot.surround;

import android.app.*;
import android.content.*;
import android.os.IBinder;

/** Keeps an explicitly started local Jam alive while both screens are locked. */
public final class JamService extends Service {
    private static JamSession session;
    static JamSession current() { return session; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("vertice_jam", "Jam local", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 20, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent end = PendingIntent.getService(this, 21, new Intent(this, JamService.class).setAction("LEAVE"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(43, new Notification.Builder(this, "vertice_jam")
            .setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("Vértice · Jam Wi-Fi Direct")
            .setContentText("Conexão local ativa · toque para abrir o player")
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar Jam", end).build());
        session = new JamSession(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "LEAVE".equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        if (session != null) { session.close(); session = null; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
