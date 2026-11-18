package com.marketradar.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the existing on-device WebView search process foreground-priority while
 * the user uses another app. No cookies, search data or UI are moved into this
 * service. Notification exists only while a scan is active.
 */
public final class SearchProtectionService extends Service {
    private static final String CHANNEL="marketradar_active_search";
    private static final int NOTIFICATION_ID=117;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(
            CHANNEL,"Активный поиск MarketRadar",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Уведомление только во время поиска цен");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL)
            .setContentTitle("MarketRadar ищет предложения")
            .setContentText("Поиск продолжается при переключении приложений")
            .setSmallIcon(R.drawable.app_icon)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        try{
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFICATION_ID,notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID,notification);
        }catch(RuntimeException e){
            android.util.Log.w("MarketRadar","Unable to protect active scan",e);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override public void onTimeout(int startId,int fgsType){
        // Android 15+ limits the dataSync foreground-service budget.
        stopSelf(startId);
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
