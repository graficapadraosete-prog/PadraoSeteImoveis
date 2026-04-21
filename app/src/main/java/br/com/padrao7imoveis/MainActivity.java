package br.com.padrao7imoveis;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView     webView;
    private ProgressBar progressBar;

    private static final String PORTAL_URL  = "https://portal.padraosete.com.br/padraoseteimoveis/sistema/sistema.php";
    private static final String LOGOUT_URL  = "https://portal.padraosete.com.br/padraoseteimoveis/sistema/logout.php";
    private static final String NOTIF_URL   = "https://portal.padraosete.com.br/padraoseteimoveis/api/notificacoes-push.php";
    private static final String CHANNEL_ID  = "padrao7_boletos";
    private static final String PREFS_NAME  = "Padrao7Prefs";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private String currentUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView     = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress_bar);

        criarCanalNotificacao();
        configurarWebView();

        if (temInternet()) {
            webView.loadUrl(PORTAL_URL);
        } else {
            mostrarSemInternet();
        }

        // Update checker após 3s
        uiHandler.postDelayed(() -> new UpdateChecker(this).verificar(), 3000);

        // Verificar notificações pendentes após 5s (primeira vez)
        uiHandler.postDelayed(this::verificarNotificacoes, 5000);
    }

    private void configurarWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " Padrao7ImoveisApp/3.5");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.setForceDark(isDarkModeOn()
                ? WebSettings.FORCE_DARK_ON
                : WebSettings.FORCE_DARK_OFF);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progressBar.setProgress(p);
                progressBar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap f) {
                progressBar.setVisibility(View.VISIBLE);
                currentUrl = url;
            }

            @Override public void onPageFinished(WebView v, String url) {
                progressBar.setVisibility(View.GONE);
                currentUrl = url;

                // Injetar CSS: mostrar topbar do portal, esconder sidebar, manter bottom-nav
                String js = "javascript:(function(){" +
                    "if(document.getElementById('app-injected')) return;" +
                    "var style=document.createElement('style');" +
                    "style.id='app-injected';" +
                    "style.innerHTML=" +
                        "'.sidebar{display:none!important}'" +
                        "+'.topbar{display:flex!important}'" +
                        "+'.main{margin-left:0!important;padding-top:4px!important;padding-bottom:80px!important}'" +
                        "+'.bottom-nav{display:block!important}';" +
                    "document.head&&document.head.appendChild(style);" +
                    // Extrair push token da sessão se disponível
                    "var meta=document.querySelector('meta[name=push-token]');" +
                    "if(meta)window.PushToken=meta.content;" +
                    "})();";
                v.loadUrl(js);
                CookieManager.getInstance().flush();

                // Salvar token de push se detectado na página
                v.evaluateJavascript(
                    "(function(){var m=document.querySelector('meta[name=push-token]');return m?m.content:'';})()",
                    value -> {
                        if (value != null && !value.equals("null") && !value.equals("\"\"")) {
                            String token = value.replace("\"","");
                            if (!token.isEmpty()) {
                                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                prefs.edit().putString("push_token", token).apply();
                            }
                        }
                    }
                );
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                if (url.contains("padraosete.com.br") || url.contains("padraoseteimoveis")) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) {}
                return true;
            }

            @Override public void onReceivedError(WebView v, int code, String desc, String url) {
                if (!temInternet()) mostrarSemInternet();
            }
        });
    }

    // ── Verificar notificações em background ─────────────────────────────────
    private void verificarNotificacoes() {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String pushToken = prefs.getString("push_token", "");

                // Determinar tipo (inquilino tem token, admin não)
                String tipo = pushToken.isEmpty() ? "admin" : "inquilino";
                String url  = NOTIF_URL + "?tipo=" + tipo +
                              (pushToken.isEmpty() ? "" : "&token=" + pushToken);

                String json = buscar(url);
                if (json == null) return;

                JSONObject d = new JSONObject(json);
                if (!d.optBoolean("notificar", false)) return;

                JSONArray lista = d.optJSONArray("notificacoes");

                if (tipo.equals("inquilino") && lista != null && lista.length() > 0) {
                    // Verificar boletos vencidos para mostrar na abertura
                    JSONObject primeiro = lista.getJSONObject(0);
                    boolean urgente = primeiro.optBoolean("urgente", false);

                    // Enviar notificação push
                    enviarNotificacao(
                        primeiro.optString("titulo", "Padrão 7 Imóveis"),
                        primeiro.optString("mensagem", ""),
                        urgente ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT
                    );

                    // Se há boleto vencido, mostrar alerta na tela ao abrir
                    if (urgente) {
                        String boletosInfo = montarInfoBoletos(lista);
                        uiHandler.post(() -> mostrarAlertaBoletoVencido(boletosInfo));
                    }

                } else if (tipo.equals("admin")) {
                    String titulo   = d.optString("titulo", "");
                    String mensagem = d.optString("mensagem", "");
                    if (!titulo.isEmpty()) {
                        enviarNotificacao(titulo, mensagem, NotificationCompat.PRIORITY_DEFAULT);
                    }
                }

            } catch (Exception e) {
                // Silencioso
            }
        }).start();
    }

    private String montarInfoBoletos(JSONArray lista) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < Math.min(lista.length(), 3); i++) {
                JSONObject b = lista.getJSONObject(i);
                sb.append("• ").append(b.optString("titulo", "")).append("\n")
                  .append("  Venc. ").append(formatarData(b.optString("vencimento","")))
                  .append(" — R$ ").append(String.format("%.2f", b.optDouble("valor",0)))
                  .append("\n\n");
            }
        } catch (Exception e) {}
        return sb.toString().trim();
    }

    private String formatarData(String data) {
        try {
            String[] p = data.split("-");
            return p[2]+"/"+p[1]+"/"+p[0];
        } catch (Exception e) { return data; }
    }

    // ── Alerta de boleto vencido ao abrir o app ──────────────────────────────
    private void mostrarAlertaBoletoVencido(String info) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
            .setTitle("⚠ Boleto(s) vencido(s)")
            .setMessage(info + "\n\nAcesse a aba Boletos para visualizar e pagar.")
            .setCancelable(true)
            .setPositiveButton("Ver boletos", (d, w) ->
                webView.loadUrl("javascript:aba('pagamentos',document.querySelector('.tab'));"))
            .setNegativeButton("Fechar", null)
            .show();
    }

    // ── Enviar notificação push local ────────────────────────────────────────
    private void enviarNotificacao(String titulo, String mensagem, int prioridade) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(titulo)
            .setContentText(mensagem)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(mensagem))
            .setPriority(prioridade)
            .setContentIntent(pi)
            .setAutoCancel(true);

        try {
            NotificationManagerCompat.from(this).notify((int)(System.currentTimeMillis() % 10000), builder.build());
        } catch (SecurityException e) { /* permissão negada */ }
    }

    // ── Canal de notificação (Android 8+) ────────────────────────────────────
    private void criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Boletos e Ocorrências",
                    NotificationManager.IMPORTANCE_HIGH
                );
                ch.setDescription("Avisos de boletos e novidades");
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }
    }

    // ── Diálogo ao pressionar Sair / Voltar ──────────────────────────────────
    private void mostrarDialogoSair() {
        new AlertDialog.Builder(this)
            .setTitle("Padrão 7 Imóveis")
            .setMessage("O que deseja fazer?")
            .setCancelable(true)
            .setPositiveButton("Fechar app", (d, w) -> finish())
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Deslogar", (d, w) -> {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                webView.clearHistory();
                // No app: volta para login (não para o site público)
                webView.loadUrl(LOGOUT_URL);
            })
            .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) { webView.goBack(); return true; }
            mostrarDialogoSair();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── Ciclo de vida ────────────────────────────────────────────────────────
    @Override protected void onResume() {
        super.onResume();
        webView.onResume();
        // Verificar notificações ao voltar para o app
        uiHandler.postDelayed(this::verificarNotificacoes, 1500);
    }
    @Override protected void onPause()  {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }
    @Override protected void onDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }

    // ── Utilitários ──────────────────────────────────────────────────────────
    private boolean isDarkModeOn() {
        int mode = getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private boolean temInternet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private String buscar(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) { return null; }
        finally { if (conn != null) try { conn.disconnect(); } catch (Exception e) {} }
    }

    private void mostrarSemInternet() {
        boolean dark = isDarkModeOn();
        String bg = dark ? "#1a1a1a" : "#f4f5f7";
        String card = dark ? "#2d2d2d" : "#ffffff";
        String txt = dark ? "#e5e7eb" : "#111";
        String sub = dark ? "#9ca3af" : "#6b7280";
        webView.loadData(
            "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>*{box-sizing:border-box;margin:0;padding:0;font-family:-apple-system,sans-serif}" +
            "body{display:flex;align-items:center;justify-content:center;min-height:100vh;background:"+bg+"}" +
            ".card{background:"+card+";border-radius:20px;padding:40px 28px;text-align:center;max-width:320px;width:90%}" +
            "h2{font-size:20px;font-weight:700;color:"+txt+";margin:16px 0 8px}" +
            "p{font-size:14px;color:"+sub+";line-height:1.6;margin-bottom:24px}" +
            "button{background:#1D9E75;color:#fff;border:none;border-radius:12px;padding:14px 32px;font-size:15px;width:100%;font-weight:600}" +
            "</style></head><body>" +
            "<div class='card'><div style='font-size:48px'>📡</div>" +
            "<h2>Sem conexão</h2><p>Verifique sua internet e tente novamente.</p>" +
            "<button onclick='location.reload()'>Tentar novamente</button></div></body></html>",
            "text/html; charset=utf-8", "UTF-8"
        );
    }
}
