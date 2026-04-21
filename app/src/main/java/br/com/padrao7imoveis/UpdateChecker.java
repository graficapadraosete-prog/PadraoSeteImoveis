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

    // URL do version.json no seu servidor
    private static final String VERSION_URL =
        "https://portal.padraosete.com.br/contrato/update/version.json";

    private final Activity activity;
    private final Handler  handler = new Handler(Looper.getMainLooper());

    public UpdateChecker(Activity activity) {
        this.activity = activity;
    }

    // Verificar em background (chamado no onCreate com delay)
    public void verificar() {
        new Thread(() -> {
            try {
                String json = buscarTexto(VERSION_URL + "?t=" + System.currentTimeMillis());
                if (json == null || json.isEmpty()) return;

                JSONObject d = new JSONObject(json);

                String versaoRemota = d.optString("versao", "0.0.0");
                String apkUrl       = d.optString("apk_url", "");
                String notas        = d.optString("notas", "");
                boolean obrigatorio = d.optBoolean("obrigatorio", false);

                String versaoLocal = obterVersaoAtual();

                if (!apkUrl.isEmpty() && isNova(versaoRemota, versaoLocal)) {
                    final String url   = apkUrl;
                    final String versao = versaoRemota;
                    final String nota  = notas;
                    final boolean obrig = obrigatorio;

                    handler.post(() -> mostrarDialogo(versao, nota, url, obrig));
                }

            } catch (Exception e) {
                // Silencioso — nao interromper o usuario
            }
        }).start();
    }

    // Diálogo de atualização disponível
    private void mostrarDialogo(String versao, String notas,
                                String apkUrl, boolean obrigatorio) {
        if (activity.isFinishing()) return;

        String msg = "Versão " + versao + " disponível!";
        if (!notas.isEmpty()) {
            msg += "\n\nO que há de novo:\n" + notas;
        }
        if (obrigatorio) {
            msg += "\n\n⚠ Esta atualização é obrigatória.";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("Atualização disponível")
            .setMessage(msg)
            .setCancelable(!obrigatorio)
            .setPositiveButton("Atualizar agora", (d, w) -> baixar(apkUrl, versao));

        if (!obrigatorio) {
            builder.setNegativeButton("Agora não", null);
        }

        builder.show();
    }

    // Baixar o APK usando DownloadManager
    private void baixar(String url, String versao) {
        try {
            String nomeArquivo = "Padrao7-" + versao + ".apk";

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Padrão 7 Imóveis — v" + versao);
            req.setDescription("Baixando atualização...");
            req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, nomeArquivo);
            req.setMimeType("application/vnd.android.package-archive");

            DownloadManager dm = (DownloadManager)
                activity.getSystemService(Context.DOWNLOAD_SERVICE);
            long downloadId = dm.enqueue(req);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long id = intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        instalar(nomeArquivo);
                        activity.unregisterReceiver(this);
                    }
                }
            };
            activity.registerReceiver(receiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        } catch (Exception e) {
            // Fallback: abrir no browser
            activity.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    // Instalar o APK baixado
    private void instalar(String nomeArquivo) {
        File apk = new File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), nomeArquivo);
        if (!apk.exists()) return;

        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri uri = FileProvider.getUriForFile(
                activity, activity.getPackageName() + ".provider", apk);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(apk),
                "application/vnd.android.package-archive");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    // Buscar texto de uma URL
    private String buscarTexto(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("User-Agent", "Padrao7ImoveisApp");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close(); conn.disconnect();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    // Versão instalada no celular
    private String obterVersaoAtual() {
        try {
            return activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    // Comparar se versão remota é mais nova
    // Ex: "2.2.0" > "2.1.0" → true
    private boolean isNova(String remota, String local) {
        try {
            int[] r = parse(remota);
            int[] l = parse(local);
            for (int i = 0; i < 3; i++) {
                if (r[i] > l[i]) return true;
                if (r[i] < l[i]) return false;
            }
            return false;
        } catch (Exception e) { return false; }
    }

    private int[] parse(String v) {
        String[] p = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] n = new int[3];
        for (int i = 0; i < Math.min(p.length, 3); i++) {
            try { n[i] = Integer.parseInt(p[i]); } catch (Exception e) { n[i] = 0; }
        }
        return n;
    }
}
