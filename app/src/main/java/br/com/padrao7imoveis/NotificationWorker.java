package br.com.padrao7imoveis;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class NotificationWorker extends Worker {

    private static final String NOTIF_URL  = "https://portal.padraosete.com.br/padraoseteimoveis/api/notificacoes-push.php";
    private static final String CHANNEL_ID = "padrao7_boletos";
    private static final String PREFS_NAME = "Padrao7Prefs";

    public NotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
        criarCanal();
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String token = prefs.getString("push_token", "");
            String tipo  = token.isEmpty() ? "admin" : "inquilino";
            String url   = NOTIF_URL + "?tipo=" + tipo +
                           (token.isEmpty() ? "" : "&token=" + token);

            String json = buscar(url);
            if (json == null) return Result.success();

            JSONObject d = new JSONObject(json);
            if (!d.optBoolean("notificar", false)) return Result.success();

            if (tipo.equals("inquilino")) {
                JSONArray lista = d.optJSONArray("notificacoes");
                if (lista == null || lista.length() == 0) return Result.success();

                JSONObject primeiro = lista.getJSONObject(0);
                boolean urgente = primeiro.optBoolean("urgente", false);
                String titulo   = primeiro.optString("titulo", "Padrão 7 Imóveis");
                String msg      = primeiro.optString("mensagem", "");

                // Para boletos vencidos: notificar com alta prioridade
                int prioridade = urgente
                    ? NotificationCompat.PRIORITY_HIGH
                    : NotificationCompat.PRIORITY_DEFAULT;

                enviarNotificacao(titulo, msg, prioridade,
                    urgente ? 100 : 200 + (int)(System.currentTimeMillis() % 100));

            } else {
                // Admin: novas ocorrências
                String titulo = d.optString("titulo", "");
                String msg    = d.optString("mensagem", "");
                if (!titulo.isEmpty()) {
                    enviarNotificacao(titulo, msg,
                        NotificationCompat.PRIORITY_DEFAULT, 300);
                }
            }

            return Result.success();
        } catch (Exception e) {
            return Result.success(); // Não falhar — tentar de novo na próxima vez
        }
    }

    private void enviarNotificacao(String titulo, String mensagem, int prioridade, int id) {
        Context ctx = getApplicationContext();
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(ctx, id, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(titulo)
            .setContentText(mensagem)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(mensagem))
            .setPriority(prioridade)
            .setContentIntent(pi)
            .setAutoCancel(true);

        if (prioridade == NotificationCompat.PRIORITY_HIGH) {
            builder.setVibrate(new long[]{0, 500, 200, 500});
        }

        try {
            NotificationManagerCompat.from(ctx).notify(id, builder.build());
        } catch (SecurityException e) { /* permissão não concedida */ }
    }

    private void criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context ctx = getApplicationContext();
            NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Boletos e Ocorrências",
                    NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Avisos de boletos a vencer, vencidos e ocorrências");
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private String buscar(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
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
}
