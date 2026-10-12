package com.speedanalyst.commute;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

/** Foreground service that records GPS + accelerometer while a drive is in progress. */
public class TrackingService extends Service implements LocationListener, SensorEventListener {

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String EXTRA_DIR = "dir";
    private static final String CHANNEL = "tracking";
    private static final int NOTIF_ID = 42;

    /** Set by the service after a trip ends so the UI can show a summary. */
    public static volatile String lastResult;

    private TripRecorder rec;
    private LocationManager lm;
    private SensorManager sm;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (rec == null) return;
            if (rec.autoFinished) {
                stopTrip();
                return;
            }
            updateNotification();
            handler.postDelayed(this, 5000);
        }
    };

    public static void start(Context c, int dir) {
        Intent i = new Intent(c, TrackingService.class).setAction(ACTION_START).putExtra(EXTRA_DIR, dir);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, TrackingService.class).setAction(ACTION_STOP));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            goForeground("Starting GPS…");
            if (rec == null) begin(intent.getIntExtra(EXTRA_DIR, Db.DIR_TO_OFFICE));
        } else if (ACTION_STOP.equals(action)) {
            if (rec == null) goForeground("Stopping…");
            stopTrip();
        } else {
            // Restarted by the system without a trip: nothing to do.
            goForeground("Speed Analyst");
            if (rec == null) stopSelfCompat();
        }
        return START_NOT_STICKY;
    }

    private void begin(int dir) {
        rec = new TripRecorder(this, dir);
        TripRecorder.setCurrent(rec);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            Toast.makeText(this, "Location permission missing", Toast.LENGTH_LONG).show();
            TripRecorder.setCurrent(null);
            rec = null;
            stopSelfCompat();
            return;
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "GPS not available on this device", Toast.LENGTH_LONG).show();
        }
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) {
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "speedanalyst:drive");
        wakeLock.acquire(4 * 60 * 60 * 1000L);
        handler.postDelayed(ticker, 5000);
    }

    private void stopTrip() {
        handler.removeCallbacks(ticker);
        if (rec != null) {
            if (lm != null) lm.removeUpdates(this);
            if (sm != null) sm.unregisterListener(this);
            boolean auto = rec.autoFinished;
            boolean kept = rec.finish();
            if (kept) {
                Db.Trip t = rec.trip;
                lastResult = (auto ? "Arrived! " : "Trip saved: ") + Geo.dur(t.durationMin()) + " · " + Geo.km(t.distM)
                        + " · " + t.stops + " stops · " + t.bumps + " breakers";
            } else {
                lastResult = "Trip too short — not saved";
            }
            rec = null;
            TripRecorder.setCurrent(null);
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopSelfCompat();
    }

    private void stopSelfCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        if (rec != null) {
            if (lm != null) lm.removeUpdates(this);
            if (sm != null) sm.unregisterListener(this);
            rec.finish();
            rec = null;
            TripRecorder.setCurrent(null);
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- notification

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Drive tracking", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, TrackingService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setContentTitle("Recording your drive")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Finish trip", ps).build());
        return b.build();
    }

    private void goForeground(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTIF_ID, n);
    }

    private void updateNotification() {
        if (rec == null) return;
        Db.Trip t = rec.trip;
        String text = Geo.clock(rec.elapsedS()) + " · " + Geo.km(t.distM) + " · "
                + Math.round(rec.speedMs * 3.6) + " km/h · " + t.stops + " stops";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ---------------------------------------------------------------- listeners

    @Override
    public void onLocationChanged(Location location) {
        if (rec != null) rec.onLocation(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (rec != null) rec.onAccel(event.values, System.currentTimeMillis());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
