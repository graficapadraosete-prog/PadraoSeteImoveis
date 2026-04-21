package br.com.padrao7imoveis;

import android.app.Application;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public class AppInit extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Agendar verificação de notificações a cada 6 horas
        // (funciona mesmo com o app fechado)
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
            NotificationWorker.class,
            6, TimeUnit.HOURS  // Verificar a cada 6h
        ).build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "padrao7_notif_check",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        );
    }
}
