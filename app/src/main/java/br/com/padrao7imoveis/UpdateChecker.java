package br.com.padrao7imoveis;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.File;

public class UpdateChecker {

    // URL da API do GitHub Releases — troque pelo seu usuario/repositorio
    private static final String GITHUB_API =
        "https://api.github.com/repos/SEU_USUARIO/padrao7imoveis-app/releases/latest";

    private final Activity activity;
    private final Handler  handler = new Handler(Looper.getMainLooper());

    public UpdateChecker(Activity activity) {
        this.activity = activity;
    }

    // Verificar atualizações em background
    public void verificar() {
        new Thread(() -> {
            try {
                String json = buscarJson(GITHUB_API);
                if (json == null) return;

                JSONObject release  = new JSONObject(json);
                String tagRemota    = release.getString("tag_name");   // ex: "v2.1"
                String notas        = release.optString("body", "");
                String apkUrl       = "";

                // Pegar URL do APK nos assets do release
                org.json.JSONArray assets = release.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String nome = asset.getString("name");
                    if (nome.endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url");
                        break;
                    }
                }

                // Versão instalada
                String versaoAtual = obterVersaoAtual();

                // Comparar versões
                if (!apkUrl.isEmpty() && !tagRemota.equals(versaoAtual)
                    && isNova(tagRemota, versaoAtual)) {

                    final String urlFinal  = apkUrl;
                    final String tag       = tagRemota;
                    final String notasFinal = notas;

                    handler.post(() ->
                        mostrarDialogo(tag, notasFinal, urlFinal)
                    );
                }

            } catch (Exception e) {
                // Silencioso — nao interromper o usuario se falhar
            }
        }).start();
    }

    // Mostrar diálogo de atualização disponível
    private void mostrarDialogo(String versao, String notas, String apkUrl) {
        if (activity.isFinishing()) return;

        String msg = "Nova versão disponível: " + versao;
        if (!notas.isEmpty()) {
            // Limitar notas a 200 chars
            String notasCurtas = notas.length() > 200
                ? notas.substring(0, 200) + "..."
                : notas;
            msg += "\n\nO que há de novo:\n" + notasCurtas;
        }

        new AlertDialog.Builder(activity)
            .setTitle("Atualização disponível")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Atualizar agora", (d, w) ->
                baixarEInstalar(apkUrl, versao)
            )
            .setNegativeButton("Agora não", null)
            .show();
    }

    // Baixar APK e instalar
    private void baixarEInstalar(String url, String versao) {
        try {
            String nomeArquivo = "padrao7-" + versao + ".apk";

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Atualizando Padrão 7 Imóveis");
            req.setDescription("Baixando versão " + versao + "...");
            req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, nomeArquivo);
            req.setMimeType("application/vnd.android.package-archive");

            DownloadManager dm = (DownloadManager)
                activity.getSystemService(Context.DOWNLOAD_SERVICE);
            long downloadId = dm.enqueue(req);

            // Listener para quando terminar o download
            BroadcastReceiver onComplete = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long id = intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        instalarApk(nomeArquivo);
                        activity.unregisterReceiver(this);
                    }
                }
            };

            activity.registerReceiver(onComplete,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        } catch (Exception e) {
            // Fallback: abrir no browser
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(i);
        }
    }

    // Instalar o APK baixado
    private void instalarApk(String nomeArquivo) {
        File apkFile = new File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), nomeArquivo);

        if (!apkFile.exists()) return;

        Intent install = new Intent(Intent.ACTION_VIEW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7+ usa FileProvider
            Uri uri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".provider",
                apkFile);
            install.setDataAndType(uri,
                "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            install.setDataAndType(Uri.fromFile(apkFile),
                "application/vnd.android.package-archive");
        }

        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
    }

    // Buscar JSON da API GitHub
    private String buscarJson(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "Padrao7ImoveisApp");

            if (conn.getResponseCode() != 200) return null;

            BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // Obter versão atual instalada
    private String obterVersaoAtual() {
        try {
            return "v" + activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0)
                .versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "v0.0.0";
        }
    }

    // Comparar se a versão remota é mais nova
    // Formato esperado: "v2.1.0" ou "2.1.0"
    private boolean isNova(String remota, String local) {
        try {
            int[] r = parseVersao(remota);
            int[] l = parseVersao(local);
            for (int i = 0; i < 3; i++) {
                if (r[i] > l[i]) return true;
                if (r[i] < l[i]) return false;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private int[] parseVersao(String v) {
        v = v.replaceAll("[^0-9.]", "");
        String[] partes = v.split("\\.");
        int[] nums = new int[3];
        for (int i = 0; i < Math.min(partes.length, 3); i++) {
            try { nums[i] = Integer.parseInt(partes[i]); }
            catch (Exception e) { nums[i] = 0; }
        }
        return nums;
    }
}
