package br.com.padrao7imoveis;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;

public class MainActivity extends Activity {

    private WebView     webView;
    private ProgressBar progressBar;
    private Button      btnLogout;

    private static final String PORTAL_URL = "https://portal.padraosete.com.br/contrato/";
    private static final String LOGOUT_URL = "https://portal.padraosete.com.br/contrato/logout.php";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView     = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress_bar);
        btnLogout   = findViewById(R.id.btn_logout);

        // Botão de sair no canto superior direito
        btnLogout.setOnClickListener(v -> mostrarDialogoSair());

        configurarWebView();

        // Verificar atualizações em background (após 3 segundos)
        new Handler(Looper.getMainLooper()).postDelayed(() ->
            new UpdateChecker(this).verificar(), 3000);

        if (temInternet()) {
            webView.loadUrl(PORTAL_URL);
        } else {
            mostrarSemInternet();
        }
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
        s.setUserAgentString(s.getUserAgentString() + " Padrao7ImoveisApp/2.1");

        // Modo escuro no WebView (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.setForceDark(isDarkModeOn()
                ? WebSettings.FORCE_DARK_ON
                : WebSettings.FORCE_DARK_OFF);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);

                // Esconder apenas a topbar do portal (temos nossa barra nativa)
                // Manter bottom-nav visivel para navegacao
                // Manter sidebar escondida (nao faz sentido no mobile)
                String js = "javascript:(function(){" +
                    "if(document.getElementById('app-injected')) return;" +
                    "var style = document.createElement('style');" +
                    "style.id = 'app-injected';" +
                    "style.innerHTML = " +
                        "'.topbar { display: none !important; }' +" +
                        "'.sidebar { display: none !important; }' +" +
                        "'.main { margin-left: 0 !important; padding-top: 10px !important; padding-bottom: 80px !important; }' +" +
                        "'.bottom-nav { display: block !important; }';" +
                    "document.head && document.head.appendChild(style);" +
                    "})();";
                view.loadUrl(js);

                CookieManager.getInstance().flush();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("padraosete.com.br")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) { /* ignorar */ }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                if (!temInternet()) mostrarSemInternet();
            }
        });
    }

    // ── Diálogo de saída ─────────────────────────────────────────────────────
    private void mostrarDialogoSair() {
        new AlertDialog.Builder(this)
            .setTitle("Padrão 7 Imóveis")
            .setMessage("O que deseja fazer?")
            .setPositiveButton("Fechar app", (d, w) -> finish())
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Deslogar", (d, w) -> {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                webView.clearHistory();
                webView.loadUrl(LOGOUT_URL);
            })
            .show();
    }

    // ── Botão voltar ─────────────────────────────────────────────────────────
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── Modo escuro ──────────────────────────────────────────────────────────
    private boolean isDarkModeOn() {
        int mode = getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ── Sem internet ─────────────────────────────────────────────────────────
    private boolean temInternet() {
        ConnectivityManager cm = (ConnectivityManager)
            getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void mostrarSemInternet() {
        boolean dark = isDarkModeOn();
        String bg   = dark ? "#1a1a1a" : "#f4f5f7";
        String card = dark ? "#2d2d2d" : "#ffffff";
        String txt  = dark ? "#e5e7eb" : "#111111";
        String sub  = dark ? "#9ca3af" : "#6b7280";

        webView.loadData(
            "<!DOCTYPE html><html><head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>*{box-sizing:border-box;margin:0;padding:0;font-family:-apple-system,sans-serif}" +
            "body{display:flex;align-items:center;justify-content:center;" +
            "min-height:100vh;background:" + bg + "}" +
            ".card{background:" + card + ";border-radius:20px;padding:40px 28px;" +
            "text-align:center;max-width:320px;width:90%}" +
            "h2{font-size:20px;font-weight:700;color:" + txt + ";margin:16px 0 8px}" +
            "p{font-size:14px;color:" + sub + ";line-height:1.6;margin-bottom:24px}" +
            "button{background:#1D9E75;color:#fff;border:none;border-radius:12px;" +
            "padding:14px 32px;font-size:15px;width:100%;font-weight:600}" +
            "</style></head><body>" +
            "<div class='card'>" +
            "<div style='font-size:48px'>📡</div>" +
            "<h2>Sem conexão</h2>" +
            "<p>Verifique sua internet e tente novamente.</p>" +
            "<button onclick='location.reload()'>Tentar novamente</button>" +
            "</div></body></html>",
            "text/html; charset=utf-8", "UTF-8"
        );
    }

    // ── Ciclo de vida ────────────────────────────────────────────────────────
    @Override protected void onResume()  { super.onResume();  webView.onResume();  }
    @Override protected void onPause()   { super.onPause();   webView.onPause();
        CookieManager.getInstance().flush(); }
    @Override protected void onDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }
}
