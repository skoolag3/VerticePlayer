package com.oneshot.surround;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.*;
import android.view.ViewGroup;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.List;

/** UI only: permissions, room invitation, peer selection, and live connection status. */
final class JamScreen {
    private static final int PERMISSION_REQUEST = 73;
    private final MainActivity host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BottomSheetDialog dialog;
    private LinearLayout devices;
    private TextView status, invitation;
    private EditText code;
    private Button create, search;
    private String renderedPeers = "";
    private final Runnable refresh = new Runnable() {
        public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            JamSession session = JamService.current();
            if (session != null) {
                status.setText(session.status());
                invitation.setText(session.isHost() ? "Código da sala\n" + session.code() : "");
                create.setEnabled(!session.started() && session.supported());
                search.setEnabled(!session.started() && session.supported());
                code.setEnabled(!session.started());
                if (!session.supported()) status.setText("Este aparelho não oferece Wi-Fi Direct.");
                renderPeers(session);
            }
            main.postDelayed(this, 700);
        }
    };

    JamScreen(MainActivity host) { this.host = host; }
    void open() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        if (host.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            String[] permissions = Build.VERSION.SDK_INT >= 33
                ? new String[]{permission, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{permission, Manifest.permission.ACCESS_COARSE_LOCATION};
            host.requestPermissions(permissions, PERMISSION_REQUEST); return;
        }
        if (!host.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            Toast.makeText(host, "Wi-Fi Direct não é suportado neste aparelho", Toast.LENGTH_LONG).show(); return;
        }
        try { host.startForegroundService(new Intent(host, JamService.class)); }
        catch (RuntimeException error) { Toast.makeText(host, "Não foi possível iniciar a Jam. Abra o app e tente novamente.", Toast.LENGTH_LONG).show(); return; }
        show();
    }

    void permissionsResult(int request, int[] results) {
        if (request != PERMISSION_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) open();
        else Toast.makeText(host, "A Jam precisa da permissão de dispositivos próximos para conectar os celulares.", Toast.LENGTH_LONG).show();
    }

    private void show() {
        if (dialog != null) dialog.dismiss();
        dialog = new BottomSheetDialog(host);
        LinearLayout content = host.column();
        content.setPadding(host.dp(20), host.dp(20), host.dp(20), host.dp(24));
        content.setBackground(host.borderedBackground(MainActivity.SURFACE, MainActivity.BORDER, 24));
        host.label(content, "JAM · WI-FI DIRECT", 20, MainActivity.TEXT, true);
        host.label(content, "Perto um do outro, sem internet. Cada celular usa seu próprio fone. Ative o Wi-Fi; em Android antigo, ative também a localização.", 13, MainActivity.MUTED, false);
        host.space(content, 12);
        status = host.label(content, "Preparando Jam…", 14, MainActivity.TEXT, false);
        invitation = host.label(content, "", 22, MainActivity.ACCENT, true);
        invitation.setTextIsSelectable(true);
        create = button(content, "Criar sala", () -> {
            JamSession session = JamService.current();
            if (session != null) try { session.create(); } catch (RuntimeException error) { status.setText("Não foi possível criar a sala. Verifique as permissões e o Wi-Fi."); }
        });
        code = new EditText(host);
        code.setSingleLine(true); code.setHint("Código do amigo · XXXX-XXXX-XXXX");
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(20)});
        code.setTextSize(14); content.addView(code);
        search = button(content, "Entrar · procurar celulares", () -> {
            JamSession session = JamService.current();
            if (session != null) try { session.discover(code.getText().toString().trim()); }
            catch (RuntimeException error) { status.setText("Não foi possível procurar. Verifique as permissões, Wi-Fi e localização."); }
        });
        devices = host.column(); content.addView(devices);
        host.space(content, 8);
        host.label(content, "Quem cria escolhe as músicas e controla a reprodução. O arquivo original é enviado antes de tocar no outro aparelho (até 512 MB). Efeitos e volume são individuais. A sincronização é aproximada; AutoMix com sobreposição não é transmitido.", 12, MainActivity.MUTED, false);
        button(content, "Copiar código da sala", () -> {
            JamSession session = JamService.current();
            if (session == null || !session.isHost()) return;
            ((ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Código Jam", session.code()));
            Toast.makeText(host, "Código copiado", Toast.LENGTH_SHORT).show();
        });
        button(content, "Encerrar Jam", () -> { host.stopService(new Intent(host, JamService.class)); dialog.dismiss(); });
        ScrollView scroll = new ScrollView(host); scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.setOnDismissListener(ignored -> main.removeCallbacks(refresh));
        renderedPeers = "";
        dialog.show(); main.post(refresh);
    }

    private void renderPeers(JamSession session) {
        List<WifiP2pDevice> peers = session.peers();
        StringBuilder signature = new StringBuilder();
        for (WifiP2pDevice peer : peers) signature.append(peer.deviceAddress).append(peer.deviceName).append(peer.status);
        if (renderedPeers.equals(signature.toString())) return;
        renderedPeers = signature.toString(); devices.removeAllViews();
        if (session.isHost()) return;
        for (WifiP2pDevice peer : peers) button(devices, peer.deviceName.isEmpty() ? "Celular próximo" : peer.deviceName, () -> {
            try { session.connect(peer); } catch (RuntimeException error) { status.setText("Falha ao conectar. Confira a permissão de dispositivos próximos."); }
        });
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = host.pill(text, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48));
        params.topMargin = host.dp(8); parent.addView(button, params);
        button.setOnClickListener(ignored -> action.run()); return button;
    }
    void close() { main.removeCallbacksAndMessages(null); if (dialog != null) dialog.dismiss(); }
}
