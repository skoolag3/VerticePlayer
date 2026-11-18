package com.oneshot.surround;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * UI & WebView Controller for the Spotify & Spotron 64-Bit DSP Bridge:
 * - Zero-duplication Hardware Multiband + Ad-lib Unmasker + Pultec + AutoEQ on Spotify
 * - Android 10+ AudioPlaybackCapture feeding Vértice's 64-bit engine & 60 FPS 3D Cover Visualizer
 * - Embedded Widevine-enabled Spotify Web / SDK Player (with RESOURCE_PROTECTED_MEDIA_ID unlocked)
 * - 1-click CamillaDSP 4.1.3 (`vbcable.yml`) & `preset.json` generator for `dmrangel/spotron`
 */
final class SpotifyBridgeScreen {
    static final int REQUEST_MEDIA_PROJECTION = 77;
    private static boolean pendingMonitorAudioTrack = false;

    private SpotifyBridgeScreen() { }

    static void open(MainActivity host) {
        SpotifyLiveBridge.attachHost(host);

        BottomSheetDialog dialog = new BottomSheetDialog(host);
        LinearLayout root = host.column();
        root.setPadding(host.dp(18), host.dp(16), host.dp(18), host.dp(18));
        root.setBackground(host.borderedBackground(MainActivity.SURFACE, MainActivity.BORDER, 22));

        LinearLayout header = host.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header);
        host.label(header, "Spotify", 22, MainActivity.TEXT, true);
        String nowPlaying = SpotifyLiveBridge.getLastSpotifyTrack().isEmpty()
            ? "Toque qualquer música no Spotify para sincronizar capa 3D, título e DSP."
            : ("Tocando no Spotify: " + SpotifyLiveBridge.getLastSpotifyTrack() + " — " + SpotifyLiveBridge.getLastSpotifyArtist());
        host.label(root, nowPlaying, 12, MainActivity.TECH_CYAN, false);

        host.space(root, 10);

        ScrollView scroll = new ScrollView(host);
        LinearLayout body = host.column();
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 1. Direct Zero-Duplication Hardware DSP Card
        LinearLayout directCard = host.card();
        body.addView(directCard);
        TextView dTitle = host.label(directCard, "Efeitos no celular", 11, 0xFF1DB954, true);
        dTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        MaterialSwitch directSwitch = new MaterialSwitch(host);
        directSwitch.setText("Conectar efeitos");
        directSwitch.setTextColor(MainActivity.TEXT);
        directSwitch.setChecked(SpotifyLiveBridge.isDirectDspEnabled());
        directCard.addView(directSwitch);
        host.label(directCard,
            "Disponibilidade depende da sessão de áudio liberada pelo Spotify e do suporte do aparelho.",
            11.5f, MainActivity.MUTED, false);

        TextView connectionStatus = host.label(directCard, SpotifyLiveBridge.getDirectStatus(), 12, MainActivity.MUTED, false);
        boolean[] updatingSwitch = {false};
        directSwitch.setOnCheckedChangeListener((btn, on) -> {
            if (updatingSwitch[0]) return;
            SpotifyLiveBridge.setDirectSpotifyDsp(host, on);
            connectionStatus.setText(SpotifyLiveBridge.getDirectStatus());
            updatingSwitch[0] = true;
            directSwitch.setChecked(SpotifyLiveBridge.isDirectDspEnabled());
            updatingSwitch[0] = false;
        });

        MaterialSwitch shieldSwitch = new MaterialSwitch(host);
        shieldSwitch.setText("🛡️ Não deixar outros aplicativos pausarem a música");
        shieldSwitch.setTextColor(MainActivity.TEXT);
        shieldSwitch.setChecked(PlaybackService.isIgnoreAudioFocusLoss());
        directCard.addView(shieldSwitch);
        shieldSwitch.setOnCheckedChangeListener((btn, on) -> {
            PlaybackService.setIgnoreAudioFocusLoss(on);
            if (on) PlaybackService.releaseAudioFocusForExternalPlayer();
        });

        host.space(directCard, 6);
        LinearLayout quickNavRow = host.row();
        directCard.addView(quickNavRow);
        Button open3dBtn = host.pill("🎧 3D / Ad-libs", false);
        Button openEqBtn = host.pill("🎚 EQ / AutoEQ", false);
        Button openCludesBtn = host.pill("🎛 CludesAmp", false);
        open3dBtn.setTextSize(10.5f);
        openEqBtn.setTextSize(10.5f);
        openCludesBtn.setTextSize(10.5f);
        quickNavRow.addView(open3dBtn, host.weighted());
        quickNavRow.addView(openEqBtn, host.weighted());
        quickNavRow.addView(openCludesBtn, host.weighted());
        open3dBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (host.studioTabSelectors != null && host.studioTabSelectors.length > 0) {
                host.studioTabSelectors[0].run();
            }
        });
        openEqBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (host.studioTabSelectors != null && host.studioTabSelectors.length > 1) {
                host.studioTabSelectors[1].run();
            }
        });
        openCludesBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (host.studioTabSelectors != null && host.studioTabSelectors.length > 2) {
                host.studioTabSelectors[2].run();
            }
        });

        // 2. Android 10+ AudioPlaybackCapture (64-Bit PCM + Live 3D Visualizer)
        LinearLayout captureCard = host.card();
        body.addView(captureCard);
        TextView cTitle = host.label(captureCard, "Visualizador", 11, MainActivity.TECH_CYAN, true);
        cTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        host.label(captureCard,
            "O Android solicitará autorização. O Spotify pode bloquear a captura de áudio.",
            11.5f, MainActivity.MUTED, false);

        MaterialSwitch monitorSwitch = new MaterialSwitch(host);
        monitorSwitch.setText("Reproduzir saída dupla AudioTrack 64-bit (Use só se mutar a origem)");
        monitorSwitch.setTextColor(MainActivity.TEXT);
        monitorSwitch.setChecked(pendingMonitorAudioTrack);
        captureCard.addView(monitorSwitch);
        monitorSwitch.setOnCheckedChangeListener((b, on) -> {
            pendingMonitorAudioTrack = on;
            if (host.player != null) host.player.setLiveMonitorAudioTrack(on);
        });

        boolean isCapturing = host.player != null && host.player.isLiveCaptureActive();
        Button captureBtn = host.pill(
            isCapturing ? "⏹ Parar Captura PCM do Spotify" : "⚡ Iniciar Captura PCM + Visualizer 3D",
            !isCapturing
        );
        captureCard.addView(captureBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(42)));
        captureBtn.setOnClickListener(v -> {
            if (host.player != null && host.player.isLiveCaptureActive()) {
                host.player.stop();
                captureBtn.setText("⚡ Iniciar Captura PCM + Visualizer 3D");
                host.status.setText("Captura PCM do Spotify encerrada");
                return;
            }
            if (Build.VERSION.SDK_INT < 29) {
                host.status.setText("AudioPlaybackCapture requer Android 10+");
                return;
            }
            MediaProjectionManager mpm = (MediaProjectionManager) host.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm != null) {
                dialog.dismiss();
                host.startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
            }
        });

        // 3. Open Spotify App or Embedded Widevine Web Playback SDK Player
        LinearLayout playerCard = host.card();
        body.addView(playerCard, 0);
        host.label(playerCard, "Use sua conta no app oficial ou no navegador.", 12, MainActivity.MUTED, false);
        TextView pTitle = host.label(playerCard, "Entrar no Spotify", 11, MainActivity.ACCENT, true);
        pTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        LinearLayout launchRow = host.row();
        playerCard.addView(launchRow);
        Button openAppBtn = host.pill("Abrir Spotify", true);
        Button openWebBtn = host.pill("Abrir no navegador", false);
        openAppBtn.setTextSize(11f);
        openWebBtn.setTextSize(11f);
        launchRow.addView(openAppBtn, host.weighted());
        launchRow.addView(openWebBtn, host.weighted());

        openAppBtn.setOnClickListener(v -> {
            Intent launch = host.getPackageManager().getLaunchIntentForPackage("com.spotify.music");
            if (launch != null) {
                host.startActivity(launch);
            } else {
                host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com")));
            }
        });

        openWebBtn.setOnClickListener(v -> {
            dialog.dismiss();
            host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com")));
        });

        host.space(playerCard, 6);
        LinearLayout transportRow = host.row();
        playerCard.addView(transportRow);
        Button prevSpotBtn = host.pill("❮❮ Anterior", false);
        Button playSpotBtn = host.pill(SpotifyLiveBridge.isSpotifyPlaying() ? "❚❚ Pausar" : "▶ Tocar", true);
        Button nextSpotBtn = host.pill("Próxima ❯❯", false);
        prevSpotBtn.setTextSize(11f);
        playSpotBtn.setTextSize(11f);
        nextSpotBtn.setTextSize(11f);
        transportRow.addView(prevSpotBtn, host.weighted());
        transportRow.addView(playSpotBtn, host.weighted());
        transportRow.addView(nextSpotBtn, host.weighted());

        prevSpotBtn.setOnClickListener(v -> {
            SpotifyLiveBridge.previousSpotifyTrack(host);
            playSpotBtn.setText("❚❚ Pausar");
        });
        playSpotBtn.setOnClickListener(v -> {
            SpotifyLiveBridge.toggleSpotifyPlayPause(host);
            playSpotBtn.setText(SpotifyLiveBridge.isSpotifyPlaying() ? "❚❚ Pausar" : "▶ Tocar");
        });
        nextSpotBtn.setOnClickListener(v -> {
            SpotifyLiveBridge.nextSpotifyTrack(host);
            playSpotBtn.setText("❚❚ Pausar");
        });

        host.space(playerCard, 6);
        Button webButton = host.pill("Abrir player web", false);
        playerCard.addView(webButton, new LinearLayout.LayoutParams(-1, host.dp(48)));
        webButton.setOnClickListener(v -> {
            android.app.Dialog webDialog = new android.app.Dialog(host, android.R.style.Theme_Material_NoActionBar);
            LinearLayout page = host.column();
            page.setBackgroundColor(MainActivity.BACKGROUND);
            LinearLayout toolbar = host.row();
            Button back = host.pill("Voltar", false);
            Button browser = host.pill("Abrir no navegador", false);
            toolbar.addView(back, host.weighted());
            toolbar.addView(browser, host.weighted());
            page.addView(toolbar);
            WebView web = createWidevineSpotifyWebView(host);
            page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            back.setOnClickListener(button -> {
                if (web.canGoBack()) web.goBack(); else webDialog.dismiss();
            });
            browser.setOnClickListener(button -> host.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"))));
            webDialog.setContentView(page);
            webDialog.setOnDismissListener(ignored -> {
                page.removeView(web);
                web.stopLoading();
                web.destroy();
            });
            webDialog.show();
            webDialog.getWindow().setLayout(-1, -1);
            web.loadUrl("https://open.spotify.com");
        });

        // 3B. Dedicated Spotify Catalog Search Card
        LinearLayout searchCard = host.card();
        body.addView(searchCard, 1);
        TextView sTitle = host.label(searchCard, "Pesquisar no Spotify", 11, 0xFF1DB954, true);
        sTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        host.label(searchCard,
            "Busque uma música ou um artista para abrir no Spotify.",
            11.5f, MainActivity.MUTED, false);

        LinearLayout searchInputRow = host.row();
        searchInputRow.setGravity(Gravity.CENTER_VERTICAL);
        searchCard.addView(searchInputRow);
        EditText spotQueryInput = new EditText(host);
        spotQueryInput.setHint("Nome da música ou artista...");
        spotQueryInput.setSingleLine(true);
        spotQueryInput.setTextSize(12.5f);
        spotQueryInput.setTextColor(MainActivity.TEXT);
        spotQueryInput.setHintTextColor(MainActivity.MUTED);
        searchInputRow.addView(spotQueryInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button spotSearchBtn = host.pill("Buscar", true);
        spotSearchBtn.setTextSize(11f);
        searchInputRow.addView(spotSearchBtn, new LinearLayout.LayoutParams(host.dp(86), host.dp(38)));

        LinearLayout searchResultsCol = host.column();
        searchCard.addView(searchResultsCol);

        spotSearchBtn.setOnClickListener(v -> {
            String q = spotQueryInput.getText().toString().trim();
            if (q.isEmpty()) return;
            spotSearchBtn.setText("...");
            searchResultsCol.removeAllViews();
            SpotifyLiveBridge.searchSpotifyCatalogAsync(host, q, items -> {
                spotSearchBtn.setText("Buscar");
                searchResultsCol.removeAllViews();
                if (items.isEmpty()) {
                    host.label(searchResultsCol, "Nenhum resultado encontrado.", 11.5f, MainActivity.MUTED, false);
                    return;
                }
                int count = Math.min(8, items.size());
                for (int i = 0; i < count; i++) {
                    SpotifyLiveBridge.SpotifySearchItem item = items.get(i);
                    LinearLayout itemRow = host.row();
                    itemRow.setGravity(Gravity.CENTER_VERTICAL);
                    itemRow.setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6));
                    LinearLayout infoCol = host.column();
                    itemRow.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    host.label(infoCol, item.title, 12.5f, MainActivity.TEXT, true);
                    String sub = item.artist + (item.durationMs > 0 ? " • " + host.formatTime(item.durationMs) : "");
                    host.label(infoCol, sub, 11f, MainActivity.MUTED, false);

                    Button playInSpot = host.pill("🟢 Tocar", true);
                    playInSpot.setTextSize(10.5f);
                    itemRow.addView(playInSpot, new LinearLayout.LayoutParams(host.dp(82), host.dp(34)));
                    playInSpot.setOnClickListener(btn -> {
                        dialog.dismiss();
                        SpotifyLiveBridge.playSearchItemInSpotify(host, item);
                    });
                    searchResultsCol.addView(itemRow);
                }
            });
        });

        // 4. Spotron Windows Live WebSocket Remote & Exporter (CamillaDSP 4.1.3 YAML + Phase 2 JSON)
        LinearLayout exportCard = host.card();
        body.addView(exportCard);
        TextView eTitle = host.label(exportCard, "Controle do PC", 11, MainActivity.TECH_CYAN, true);
        eTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        host.label(exportCard,
            "Conecte no `camilladsp.exe -p 1234` do PC (via Wi-Fi) para injetar ao vivo todos os efeitos 3D do Vértice (incluindo o AmbioRace FIR de 4 canais, Crossfeed ITD/ILD, Pinna HRTF, Pultec e AutoEQ) ou copie o `vbcable.yml` / `preset.json`.",
            11.5f, MainActivity.MUTED, false);

        host.space(exportCard, 6);
        EditText pcIpInput = new EditText(host);
        pcIpInput.setHint("IP do PC com Spotron (ex: 192.168.1.15:1234)");
        pcIpInput.setSingleLine(true);
        pcIpInput.setTextSize(12.5f);
        pcIpInput.setTextColor(MainActivity.TEXT);
        pcIpInput.setHintTextColor(MainActivity.MUTED);
        pcIpInput.setText(SpotifyLiveBridge.getCamillaHostIp());
        exportCard.addView(pcIpInput);

        MaterialSwitch liveWsSwitch = new MaterialSwitch(host);
        liveWsSwitch.setText("Sincronizar ajustes");
        liveWsSwitch.setTextColor(MainActivity.TEXT);
        liveWsSwitch.setChecked(SpotifyLiveBridge.isLiveCamillaSyncEnabled());
        exportCard.addView(liveWsSwitch);
        liveWsSwitch.setOnCheckedChangeListener((b, on) -> {
            SpotifyLiveBridge.setCamillaHostIp(host, pcIpInput.getText().toString(), on);
        });

        Button pushWsBtn = host.pill("Enviar ajustes", true);
        pushWsBtn.setTextSize(11.5f);
        exportCard.addView(pushWsBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(40)));
        pushWsBtn.setOnClickListener(v -> {
            SpotifyLiveBridge.setCamillaHostIp(host, pcIpInput.getText().toString(), liveWsSwitch.isChecked());
            SpotifyLiveBridge.pushConfigToSpotronPc(host, () -> pushWsBtn.setText("✓ Injetado no CamillaDSP 4.1.3!"));
        });

        host.space(exportCard, 6);
        LinearLayout expRow = host.row();
        exportCard.addView(expRow);
        Button exportYmlBtn = host.pill("📄 vbcable.yml (CamillaDSP)", false);
        Button exportJsonBtn = host.pill("🔧 preset.json (Spotron)", false);
        exportYmlBtn.setTextSize(11f);
        exportJsonBtn.setTextSize(11f);
        expRow.addView(exportYmlBtn, host.weighted());
        expRow.addView(exportJsonBtn, host.weighted());

        exportYmlBtn.setOnClickListener(v -> showExportPreviewDialog(
            host,
            "vbcable.yml · CamillaDSP 4.1.3 (Spotron)",
            SpotifyLiveBridge.generateSpotronCamillaYaml(host)
        ));
        exportJsonBtn.setOnClickListener(v -> showExportPreviewDialog(
            host,
            "preset.json · Fase 2 (dmrangel/spotron)",
            SpotifyLiveBridge.generateSpotronPresetJson(host)
        ));

        host.space(root, 8);
        Button close = host.pill("Fechar", false);
        root.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(40)));
        close.setOnClickListener(v -> dialog.dismiss());

        int height = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.86f);
        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        dialog.show();
        dialog.getBehavior().setPeekHeight(height);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    static void handleProjectionResult(MainActivity host, int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            host.status.setText("Permissão de captura do Spotify cancelada");
            return;
        }
        Intent start = new Intent(host, PlaybackService.class);
        start.setAction("START_PROJECTION");
        start.putExtra("resultCode", resultCode);
        start.putExtra("data", data);
        start.putExtra("monitor", pendingMonitorAudioTrack);
        if (Build.VERSION.SDK_INT >= 26) {
            host.startForegroundService(start);
        } else {
            host.startService(start);
        }
        host.playing = true;
        host.playButton.setText("❚❚");
        host.artwork.setPlaying(true);
        host.title.setText(SpotifyLiveBridge.getLastSpotifyTrack().isEmpty()
            ? "Spotify · Live 64-Bit DSP" : SpotifyLiveBridge.getLastSpotifyTrack());
        host.artist.setText(SpotifyLiveBridge.getLastSpotifyArtist().isEmpty()
            ? "Toque uma música no Spotify para ver a capa 3D reagir" : SpotifyLiveBridge.getLastSpotifyArtist());
        host.status.setText("Captura solicitada. O visualizador depende do áudio liberado pelo Android.");
    }

    private static void showExportPreviewDialog(MainActivity host, String title, String content) {
        EditText codeView = new EditText(host);
        codeView.setText(content);
        codeView.setTypeface(Typeface.MONOSPACE);
        codeView.setTextSize(10.5f);
        codeView.setTextColor(MainActivity.TEXT);
        codeView.setPadding(host.dp(14), host.dp(10), host.dp(14), host.dp(10));

        ScrollView scroll = new ScrollView(host);
        scroll.addView(codeView);

        new MaterialAlertDialogBuilder(host)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Compartilhar", (d, w) -> {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, title);
                share.putExtra(Intent.EXTRA_TEXT, content);
                host.startActivity(Intent.createChooser(share, "Enviar configuração para o Spotron"));
            })
            .setPositiveButton("Copiar Código", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText(title, content));
                    host.status.setText("Configuração copiada para a área de transferência!");
                }
            })
            .show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView createWidevineSpotifyWebView(MainActivity host) {
        WebView webView = new WebView(host);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(webView, true);
        } catch (Throwable ignored) { }

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                host.runOnUiThread(() -> {
                    try {
                        if ("https://open.spotify.com".equals(request.getOrigin().toString().replaceAll("/$", ""))) {
                            for (String resource : request.getResources()) {
                                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                                    request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                                    return;
                                }
                            }
                        }
                        request.deny();
                    } catch (Throwable ignored) { }
                });
            }
        });
        return webView;
    }

}
