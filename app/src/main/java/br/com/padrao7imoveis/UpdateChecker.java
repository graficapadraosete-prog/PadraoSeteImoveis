package br.com.padrao7imoveis;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private static final String VERSION_URL =
        "https://portal.padraosete.com.br/padraoseteimoveis/api/versao-app.php";
    private static final String CHANNEL_ID = "padrao7_updates";

    private final Activity activity;
    private final Handler  uiHandler = new Handler(Looper.getMainLooper());

    public UpdateChecker(Activity activity) {
        this.activity = activity;
        criarCanalNotificacao();
    }

    // ── Chamado no onCreate após 3s ──────────────────────────────────────────
    public void verificar() {
        new Thread(() -> {
            try {
                String versaoLocal  = obterVersaoAtual();
                String json         = buscar(VERSION_URL + "?t=" + System.currentTimeMillis());
                if (json == null || json.isEmpty()) return;

                JSONObject d        = new JSONObject(json);
                String versaoRemota = d.optString("versao", "0.0.0").trim();
                String apkUrl       = d.optString("apk_url", "").trim();
                String notas        = d.optString("notas", "").trim();
                boolean obrig       = d.optBoolean("obrigatorio", false);

                // ── Comparação estrita: só notifica se remota for MAIOR ──────
                // Versão igual ou local maior → silêncio total
                if (!estaDesatualizado(versaoLocal, versaoRemota)) return;
                if (apkUrl.isEmpty()) return;

                // Há versão nova — mostrar diálogo na UI thread
                uiHandler.post(() ->
                    mostrarDialogo(versaoLocal, versaoRemota, notas, apkUrl, obrig));

            } catch (Exception e) {
                // Falha silenciosa — nunca interromper o usuário
            }
        }).start();
    }

    // ── true somente se versaoRemota > versaoLocal ───────────────────────────
    private boolean estaDesatualizado(String local, String remota) {
        int[] l = parseVersao(local);
        int[] r = parseVersao(remota);
        for (int i = 0; i < 3; i++) {
            if (r[i] > l[i]) return true;   // remota maior → atualizar
            if (r[i] < l[i]) return false;  // local maior → não perguntar
        }
        return false; // versões idênticas → silêncio
    }

    // ── Diálogo de atualização ───────────────────────────────────────────────
    private void mostrarDialogo(String local, String remota, String notas,
                                 String apkUrl, boolean obrig) {
        if (activity == null || activity.isFinishing()) return;

        StringBuilder msg = new StringBuilder();
        msg.append("Versão instalada: ").append(local).append("\n");
        msg.append("Versão disponível: ").append(remota).append("\n");
        if (!notas.isEmpty()) {
            msg.append("\nNovidades:\n").append(notas);
        }
        if (obrig) {
            msg.append("\n\n⚠ Esta atualização é obrigatória.");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
            .setTitle("Atualização disponível")
            .setMessage(msg.toString())
            .setCancelable(!obrig)
            .setPositiveButton("Atualizar agora", (d, w) ->
                iniciarDownload(apkUrl, remota));

        if (!obrig) {
            b.setNegativeButton("Agora não", null);
        }
        b.show();
    }

    // ── Download via DownloadManager ─────────────────────────────────────────
    public void iniciarDownload(String url, String versao) {
        try {
            limparApksAntigos();

            String nome   = "Padrao7-" + versao + ".apk";
            File   destino = new File(
                activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), nome);

            // Se já existe um APK válido, instala diretamente
            if (destino.exists() && destino.length() > 500_000) {
                instalar(destino);
                return;
            }

            Toast.makeText(activity,
                "Baixando versão " + versao + "...", Toast.LENGTH_LONG).show();

            DownloadManager.Request req =
                new DownloadManager.Request(Uri.parse(url))
                    .setTitle("Padrão 7 Imóveis v" + versao)
                    .setDescription("Baixando atualização...")
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationUri(Uri.fromFile(destino))
                    .setMimeType("application/vnd.android.package-archive")
                    .addRequestHeader("Cache-Control", "no-cache");

            DownloadManager dm =
                (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            long dlId = dm.enqueue(req);

            // Receiver que aguarda conclusão
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id != dlId) return;

                    DownloadManager.Query q =
                        new DownloadManager.Query().setFilterById(dlId);
                    try (android.database.Cursor cur = dm.query(q)) {
                        if (cur.moveToFirst()) {
                            int col = cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
                            if (cur.getInt(col) == DownloadManager.STATUS_SUCCESSFUL) {
                                uiHandler.post(() -> instalar(destino));
                            } else {
                                uiHandler.post(() -> Toast.makeText(activity,
                                    "Falha no download. Verifique sua conexão.",
                                    Toast.LENGTH_LONG).show());
                            }
                        }
                    } catch (Exception e) { /* ignore */ }
                    try { activity.unregisterReceiver(this); } catch (Exception e) { /* ignore */ }
                }
            };

            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            // DOWNLOAD_COMPLETE é broadcast do sistema — requer RECEIVER_EXPORTED no Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(receiver, filter,
                    Context.RECEIVER_EXPORTED);
            } else {
                // Android < 13: sem flag obrigatória
                activity.registerReceiver(receiver, filter);
            }

        } catch (Exception e) {
            // Fallback: abrir no browser
            try { activity.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ex) { /* ignore */ }
        }
    }

    // ── Instalar APK ─────────────────────────────────────────────────────────
    private void instalar(File apkFile) {
        if (apkFile == null || !apkFile.exists()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".provider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity,
                "Abra o arquivo em Downloads para instalar.",
                Toast.LENGTH_LONG).show();
        }
    }

    // ── Limpar APKs antigos desta app ────────────────────────────────────────
    private void limparApksAntigos() {
        try {
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || !dir.exists()) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.getName().startsWith("Padrao7-") && f.getName().endsWith(".apk")) {
                    boolean deleted = f.delete();
                    if (!deleted) f.deleteOnExit(); // fallback
                }
            }
        } catch (Exception e) { /* ignore */ }
    }

    // ── Canal de notificação (Android 8+) ────────────────────────────────────
    private void criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Atualizações", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Novas versões do app");
            NotificationManager nm = activity.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ── Versão instalada no celular (ex: "3.0.0") ────────────────────────────
    private String obterVersaoAtual() {
        try {
            PackageInfo pi = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName.trim() : "0.0.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    // ── Parsing de versão "3.0.0" → [3, 0, 0] ───────────────────────────────
    private int[] parseVersao(String v) {
        String[] partes = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] nums = new int[3];
        for (int i = 0; i < Math.min(partes.length, 3); i++) {
            try { nums[i] = Integer.parseInt(partes[i]); }
            catch (Exception e) { nums[i] = 0; }
        }
        return nums;
    }

    // ── Buscar texto de URL ───────────────────────────────────────────────────
    private String buscar(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("User-Agent",
                "Padrao7ImoveisApp/" + obterVersaoAtual());
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br =
                new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception e) { /* ignore */ }
        }
    }
}
