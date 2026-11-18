package com.oneshot.surround;

import android.annotation.SuppressLint;
import android.content.*;
import android.net.NetworkInfo;
import android.net.wifi.p2p.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** One host and one guest. Android owns the encrypted Wi-Fi group; Jam authenticates both apps. */
@SuppressLint("MissingPermission")
final class JamSession implements AutoCloseable {
    private static final int PORT = 39847;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
    private final JamTransfer transfer;
    private final ArrayList<WifiP2pDevice> peers = new ArrayList<>();
    private final android.os.PowerManager.WakeLock wakeLock;
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<>());
    private volatile JamWire wire;
    private volatile ServerSocket server;
    private volatile boolean closed, connecting;
    private boolean host, selected, uploadBusy, localPaused, failed;
    private volatile String status = "Escolha criar uma sala ou procurar o celular do seu amigo.";
    private String code = "", sentUri = "", sentTrack = "", receivedTrack = "";
    private volatile String readyTrack = "";
    private JamTransfer.Received received;
    private android.net.Uri receivedUri;
    private JSONObject remoteState;
    private final JamClock clock = new JamClock();
    private long lastSeekAt, receivedAt;
    private int lastPercent = -1;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)
                    && intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                fail("Wi-Fi desligado. Ative o Wi-Fi e abra uma nova Jam.");
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                if (selected) try { manager.requestPeers(channel, list -> { peers.clear(); peers.addAll(list.getDeviceList()); }); }
                catch (SecurityException error) { fail("Permissão de dispositivos próximos removida"); }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (info != null && info.isConnected()) requestConnection();
                else if (wire != null || connecting) fail("Conexão com o outro celular encerrada");
            }
        }
    };

    JamSession(Context context) {
        this.context = context;
        transfer = new JamTransfer(context);
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager == null ? null : manager.initialize(context, Looper.getMainLooper(), () -> fail("Wi-Fi Direct indisponível. Reabra a Jam."));
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter);
        wakeLock = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vertice:jam");
        wakeLock.acquire(2 * 60 * 60 * 1000L);
        sender.scheduleWithFixedDelay(() -> main.post(this::tick), 750, 750, TimeUnit.MILLISECONDS);
    }

    String status() { return status; }
    String code() { return code; }
    boolean isHost() { return host; }
    boolean isGuest() { return selected && !host; }
    boolean started() { return selected; }
    List<WifiP2pDevice> peers() { return new ArrayList<>(peers); }
    boolean supported() { return manager != null && channel != null; }

    void create() {
        if (!supported() || selected) return;
        selected = true; host = true; code = JamWire.newCode();
        status = "Criando sala…";
        manager.createGroup(channel, action("Sala criada. No outro celular, procure este aparelho e informe o código.", this::requestConnection));
    }

    void discover(String enteredCode) {
        if (!supported() || selected) return;
        String clean = enteredCode.replaceAll("[^A-Za-z0-9]", "");
        if (clean.length() != 12) { status = "Digite os 12 caracteres do código mostrado no outro celular."; return; }
        selected = true; host = false; code = enteredCode;
        PlaybackService.beginJamGuest(context);
        status = "Procurando celulares próximos…";
        manager.discoverPeers(channel, action("Toque no celular que criou a sala. Aceite a conexão no outro aparelho.", null));
    }

    void connect(WifiP2pDevice device) {
        if (host || closed || connecting || wire != null) return;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress; config.groupOwnerIntent = 0;
        status = "Conectando a " + device.deviceName + "…";
        manager.connect(channel, config, action("Aguardando autorização de Wi-Fi Direct no outro celular…", null));
    }

    private WifiP2pManager.ActionListener action(String success, Runnable next) {
        return new WifiP2pManager.ActionListener() {
            public void onSuccess() { if (!closed) { status = success; if (next != null) next.run(); } }
            public void onFailure(int reason) { fail("Wi-Fi Direct: " + (reason == WifiP2pManager.BUSY ? "ocupado. Encerre a Jam e tente novamente." : reason == WifiP2pManager.P2P_UNSUPPORTED ? "não suportado neste celular." : "falha " + reason + ". Ative Wi-Fi e localização e tente novamente.")); }
        };
    }

    private void requestConnection() {
        if (closed || !selected || !supported()) return;
        manager.requestConnectionInfo(channel, info -> {
            if (closed || !info.groupFormed || info.groupOwnerAddress == null || connecting || wire != null) return;
            if (host != info.isGroupOwner) { fail("Grupo Wi-Fi antigo detectado. Encerre a Jam nos dois aparelhos e crie outra sala."); return; }
            connecting = true;
            new Thread(() -> {
                try {
                    if (host) acceptGuest(info.groupOwnerAddress);
                    else {
                        Socket socket = new Socket(); sockets.add(socket);
                        socket.connect(new InetSocketAddress(info.groupOwnerAddress, PORT), 15000);
                        attach(new JamWire(socket, code, false));
                    }
                } catch (Exception error) { if (!closed) fail("Não foi possível entrar na Jam: " + message(error)); }
            }, "jam-connect").start();
        });
    }

    private void acceptGuest(InetAddress address) throws Exception {
        ServerSocket listener = new ServerSocket(); server = listener;
        listener.setReuseAddress(true); listener.bind(new InetSocketAddress(address, PORT));
        for (int attempt = 0; !closed && attempt < 5; attempt++) {
            Socket socket = listener.accept(); sockets.add(socket);
            try { attach(new JamWire(socket, code, true)); listener.close(); return; }
            catch (Exception error) { socket.close(); sockets.remove(socket); status = "Código incorreto ou conexão recusada. Confira o código e tente novamente."; }
        }
        throw new IOException("Muitas tentativas. Crie uma nova sala.");
    }

    private void attach(JamWire connection) {
        if (closed) { try { connection.close(); } catch (Exception ignored) { } return; }
        wire = connection; connecting = false;
        status = host ? "Amigo conectado. Escolha uma música no player." : "Conectado. Aguardando música do anfitrião…";
        new Thread(() -> readLoop(connection), "jam-receive").start();
    }

    private void readLoop(JamWire connection) {
        try {
            while (!closed && wire == connection) {
                JamWire.Packet packet = connection.read();
                JSONObject json = new JSONObject(packet.json);
                String type = json.getString("type");
                if (host) {
                    if ("ping".equals(type)) connection.send(new JSONObject().put("type", "pong").put("sent", json.getLong("sent")).put("host", SystemClock.elapsedRealtime()).toString(), null);
                    else if ("ready".equals(type)) readyTrack = json.getString("track");
                    else throw new IOException("Comando de convidado inválido");
                } else if ("file".equals(type)) {
                    main.post(() -> { PlaybackService.pauseJamGuest(); receivedTrack = ""; received = null; remoteState = null; });
                    transfer.begin(json); status = "Recebendo música original…"; lastPercent = -1;
                } else if ("chunk".equals(type)) {
                    int percent = transfer.chunk(packet.blob);
                    if (percent != lastPercent) { status = "Recebendo música original · " + percent + "%"; lastPercent = percent; }
                } else if ("end".equals(type)) {
                    JamTransfer.Received track = transfer.finish(json.getString("track"));
                    String token = json.getString("track");
                    main.post(() -> {
                        if (closed) return;
                        try { receivedUri = track.uri(context); received = track; receivedTrack = token; status = "Arquivo íntegro · aguardando sincronização"; }
                        catch (RuntimeException error) { fail("Não foi possível abrir a música recebida"); }
                    });
                    connection.send(new JSONObject().put("type", "ready").put("track", token).toString(), null);
                } else if ("state".equals(type)) {
                    long arrived = SystemClock.elapsedRealtime();
                    main.post(() -> { remoteState = json; receivedAt = arrived; syncGuest(); });
                } else if ("pong".equals(type)) {
                    long now = SystemClock.elapsedRealtime(), sent = json.getLong("sent"), remote = json.getLong("host");
                    main.post(() -> clock.observe(sent, now, remote));
                } else throw new IOException("Pacote desconhecido");
            }
        } catch (Exception error) { if (!closed) fail("Jam interrompida: " + message(error)); }
    }

    private void tick() {
        if (closed || failed || wire == null) return;
        JamWire connection = wire;
        if (!host) {
            sendAsync(new JSONObjectBuilder().put("type", "ping").put("sent", SystemClock.elapsedRealtime()).value);
            if (remoteState != null && SystemClock.elapsedRealtime() - receivedAt > 4000) {
                PlaybackService.pauseJamGuest(); status = "Sinal instável · áudio pausado até reconectar";
            }
            return;
        }
        PlaybackService.JamSnapshot snapshot = PlaybackService.jamSnapshot();
        if (snapshot.uri == null) return;
        if (!snapshot.uri.toString().equals(sentUri) && !uploadBusy) {
            uploadBusy = true; readyTrack = ""; sentTrack = UUID.randomUUID().toString();
            String token = sentTrack;
            status = "Enviando arquivo original: " + snapshot.title;
            sender.execute(() -> {
                try {
                    transfer.send(connection, snapshot.uri, snapshot.title, snapshot.artist, token);
                    main.post(() -> { sentUri = snapshot.uri.toString(); uploadBusy = false; status = "Arquivo enviado · aguardando confirmação do amigo"; });
                } catch (Exception error) { main.post(() -> uploadBusy = false); if (!closed) fail("Falha no envio: " + message(error)); }
            });
        } else if (!uploadBusy && sentTrack.equals(readyTrack)) {
            sendAsync(new JSONObjectBuilder().put("type", "state").put("track", sentTrack).put("position", snapshot.position)
                .put("paused", snapshot.paused).put("speed", snapshot.speed).put("at", SystemClock.elapsedRealtime()).value);
            status = "Jam ativa · arquivo original · " + snapshot.title;
        }
    }

    private void sendAsync(JSONObject json) {
        if (closed || sender.isShutdown()) return;
        sender.execute(() -> { try { JamWire connection = wire; if (connection != null) connection.send(json.toString(), null); }
            catch (Exception error) { if (!closed) fail("Conexão interrompida: " + message(error)); } });
    }

    private void syncGuest() {
        if (closed || failed || host || remoteState == null || received == null || !receivedTrack.equals(remoteState.optString("track"))) return;
        FfmpegPcmPlayer player = PlaybackService.getPlayer(context);
        if (player.isRouteMuted()) { status = "Fone desconectado · saída silenciada. Toque em Reproduzir para autorizar."; return; }
        boolean paused = remoteState.optBoolean("paused", true) || localPaused;
        float speed = (float) Math.max(0.5, Math.min(1.5, remoteState.optDouble("speed", 1)));
        long position = clock.position(remoteState.optLong("position"), remoteState.optLong("at"), speed, paused, SystemClock.elapsedRealtime());
        long now = SystemClock.elapsedRealtime();
        if (!PlaybackService.isJamTrack(receivedUri)) {
            PlaybackService.playJamGuest(context, received, position, paused);
            if (received.previous != null) received.previous.delete();
            lastSeekAt = now;
        } else {
            long drift = Math.abs(PlaybackService.jamPosition() - position);
            if (drift > 900 && now - lastSeekAt > 5000) { player.seekTo(position); lastSeekAt = now; }
            if (paused) player.pause(); else player.resumeAutomatic();
        }
        player.setSpeed(speed, true);
        PlaybackService.refresh();
        PlaybackService.refreshJamUi();
        status = localPaused ? "Jam pausada neste celular" : paused ? "Anfitrião pausou a música" : "Jam ativa · arquivo original · " + received.title;
    }

    void toggleGuest() {
        FfmpegPcmPlayer player = PlaybackService.getPlayer(context);
        if (player.isRouteMuted()) { player.allowOutput(); localPaused = false; }
        else localPaused = !localPaused;
        if (localPaused) PlaybackService.pauseJamGuest();
        syncGuest();
    }
    void setGuestPaused(boolean paused) {
        localPaused = paused;
        if (!paused) PlaybackService.getPlayer(context).allowOutput();
        if (paused) PlaybackService.pauseJamGuest(); else syncGuest();
    }

    private void fail(String reason) { main.post(() -> { if (!closed && !failed) { failed = true; status = reason + " Encerre a Jam para tentar de novo."; disconnect(); } }); }
    private void disconnect() {
        wire = null; connecting = false;
        if (!host) PlaybackService.pauseJamGuest();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        synchronized (sockets) { for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { } sockets.clear(); }
    }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    public void close() {
        closed = true; disconnect(); sender.shutdownNow();
        main.removeCallbacksAndMessages(null);
        context.unregisterReceiver(receiver);
        if (manager != null && channel != null) {
            try { manager.stopPeerDiscovery(channel, null); manager.cancelConnect(channel, null); if (selected) manager.removeGroup(channel, null); }
            catch (SecurityException ignored) { }
            if (Build.VERSION.SDK_INT >= 27) channel.close();
        }
        if (!host) PlaybackService.endJamGuest();
        // Cleanup follows the worker's completion so a file cannot be deleted during transfer.
        new Thread(() -> { try { sender.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } transfer.close(); }, "jam-cleanup").start();
        if (wakeLock.isHeld()) wakeLock.release();
    }
    private static final class JSONObjectBuilder {
        final JSONObject value = new JSONObject();
        JSONObjectBuilder put(String key, Object item) { try { value.put(key, item); } catch (org.json.JSONException ignored) { } return this; }
    }
}
