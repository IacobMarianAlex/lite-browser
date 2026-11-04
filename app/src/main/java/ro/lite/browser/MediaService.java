package ro.lite.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Playerul media din notificari si de pe ecranul de blocare.
 * Tine sunetul pornit cu ecranul stins si trimite comenzile (pauza, reda, opreste)
 * catre pagina care reda.
 */
public class MediaService extends Service {

    static final String ACT_UPDATE = "ro.lite.browser.media.UPDATE";
    static final String ACT_TOGGLE = "ro.lite.browser.media.TOGGLE";
    static final String ACT_STOP = "ro.lite.browser.media.STOP";

    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private String title = "Media";
    private String site = "Lite Browser";
    private boolean playing = true;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                "media", "Player media", NotificationManager.IMPORTANCE_LOW));

        session = new MediaSession(this, "LiteBrowser");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                relay("play");
            }

            @Override
            public void onPause() {
                relay("pause");
            }

            @Override
            public void onStop() {
                relay("stop");
                stopSelf();
            }
        });
        session.setActive(true);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiteBrowser:media");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACT_TOGGLE.equals(a)) {
            relay("toggle");
        } else if (ACT_STOP.equals(a)) {
            relay("stop");
            showNotification();   // startForeground trebuie apelat inainte de oprire
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACT_UPDATE.equals(a)) {
            String t = intent.getStringExtra("title");
            if (t != null && !t.isEmpty()) title = t;
            String s = intent.getStringExtra("site");
            if (s != null && !s.isEmpty()) site = s;
            playing = intent.getBooleanExtra("playing", true);
        }
        showNotification();
        return START_NOT_STICKY;
    }

    private PendingIntent action(String act, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(act);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void showNotification() {
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, site)
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification.Action toggle = new Notification.Action.Builder(
                Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Pauză" : "Redă", action(ACT_TOGGLE, 1)).build();
        Notification.Action stop = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_close),
                "Oprește", action(ACT_STOP, 2)).build();

        Notification n = new Notification.Builder(this, "media")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(site)
                .setContentIntent(openPi)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(toggle)
                .addAction(stop)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(2, n);
        }

        // procesorul ramane treaz doar cat timp se reda ceva
        if (playing) wakeLock.acquire(6 * 60 * 60 * 1000L);
        else if (wakeLock.isHeld()) wakeLock.release();
    }

    private void relay(String cmd) {
        MainActivity a = MainActivity.ref != null ? MainActivity.ref.get() : null;
        if (a != null) a.runOnUiThread(() -> a.mediaCommand(cmd));
    }

    @Override
    public void onDestroy() {
        if (wakeLock.isHeld()) wakeLock.release();
        session.setActive(false);
        session.release();
        MainActivity a = MainActivity.ref != null ? MainActivity.ref.get() : null;
        if (a != null) a.runOnUiThread(a::onMediaServiceStopped);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
