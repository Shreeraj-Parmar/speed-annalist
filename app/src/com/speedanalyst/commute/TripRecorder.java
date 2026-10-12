package com.speedanalyst.commute;

import android.content.Context;
import android.location.Location;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Live state of the trip currently being recorded. Fed by {@link TrackingService}
 * (GPS fixes + accelerometer) and read by the UI once per second.
 */
public class TripRecorder {

    private static TripRecorder current;

    public static synchronized TripRecorder current() {
        return current;
    }

    static synchronized void setCurrent(TripRecorder r) {
        current = r;
    }

    // Detection thresholds
    private static final float MAX_ACCURACY_M = 40f;
    private static final double STOPPED_MS = 1.0;       // below 3.6 km/h = standing
    private static final double MOVING_MS = 2.0;        // above 7.2 km/h = moving again
    private static final long MIN_STOP_S = 15;          // a halt this long counts as a traffic stop
    private static final double MIN_BUMP_SPEED_MS = 2.5; // only count breakers while rolling
    private static final long BUMP_COOLDOWN_MS = 2000;

    private final Context ctx;
    private final Db db;
    public final Db.Trip trip = new Db.Trip();

    private final double destLat, destLon;
    private final boolean hasDest;
    private final boolean autoStop;
    private final float bumpThreshold;

    // live values
    public volatile double speedMs;
    public volatile boolean gpsFix;
    public volatile float accuracy;
    public volatile boolean autoFinished;

    private Location last;
    private long lastFixTs;
    private boolean stopped;
    private long stopStartTs;
    private double stopLat, stopLon;
    private long nearDestSince;

    private final List<Db.Point> pending = new ArrayList<Db.Point>();
    private long lastFlush;
    private long lastPointTs;
    private final LinkedHashSet<String> cells = new LinkedHashSet<String>();

    private final float[] gravity = new float[3];
    private boolean gravityInit;
    private long lastBumpTs;
    private double lastBumpLat, lastBumpLon;

    public TripRecorder(Context ctx, int dir) {
        this.ctx = ctx.getApplicationContext();
        this.db = Db.get(ctx);
        Prefs p = new Prefs(ctx);
        trip.dir = dir;
        trip.startTs = now();
        trip.endTs = trip.startTs;
        trip.active = true;
        if (dir == Db.DIR_TO_OFFICE && p.hasOffice()) {
            hasDest = true;
            destLat = p.officeLat();
            destLon = p.officeLon();
        } else if (dir == Db.DIR_TO_HOME && p.hasHome()) {
            hasDest = true;
            destLat = p.homeLat();
            destLon = p.homeLon();
        } else {
            hasDest = false;
            destLat = destLon = 0;
        }
        autoStop = p.autoStop();
        int s = p.bumpSensitivity();
        bumpThreshold = s == 0 ? 6.0f : (s == 2 ? 3.2f : 4.5f);
        db.insertTrip(trip);
    }

    /** Wall clock; overridable so trips can be replayed in tests. */
    protected long now() {
        return System.currentTimeMillis();
    }

    public long elapsedS() {
        return (now() - trip.startTs) / 1000;
    }

    public double distanceToDestM() {
        if (!hasDest || last == null) return -1;
        return Geo.distM(last.getLatitude(), last.getLongitude(), destLat, destLon);
    }

    // ---------------------------------------------------------------- GPS

    public synchronized void onLocation(Location loc) {
        // Receipt time, not GPS time: keeps every timestamp on the same clock as trip.startTs.
        long now = now();
        accuracy = loc.hasAccuracy() ? loc.getAccuracy() : 0;
        if (loc.hasAccuracy() && loc.getAccuracy() > MAX_ACCURACY_M) {
            gpsFix = false;
            return;
        }
        gpsFix = true;

        double spd;
        double step = 0;
        long dtMs = last != null ? now - lastFixTs : 0;
        if (last != null) step = Geo.distM(last.getLatitude(), last.getLongitude(), loc.getLatitude(), loc.getLongitude());
        if (loc.hasSpeed()) spd = loc.getSpeed();
        else spd = dtMs > 0 ? step / (dtMs / 1000.0) : 0;
        if (spd > 60) spd = speedMs; // ignore absurd jumps (>216 km/h)
        speedMs = spd;

        if (last != null && dtMs > 0 && dtMs < 120000) {
            // Only accumulate distance when really moving, to avoid GPS drift while standing.
            if (spd > 0.8 && step > 2) trip.distM += step;

            double dtS = dtMs / 1000.0;
            boolean started = trip.distM > 50;
            if (started) {
                if (spd < STOPPED_MS) trip.stoppedS += Math.round(dtS);
                else trip.movingS += Math.round(dtS);
            }
            if (spd > trip.maxSpeed) trip.maxSpeed = spd;

            // Traffic stop detection
            if (!stopped && spd < STOPPED_MS && started) {
                stopped = true;
                stopStartTs = now;
                stopLat = loc.getLatitude();
                stopLon = loc.getLongitude();
            } else if (stopped && spd > MOVING_MS) {
                stopped = false;
                long dur = (now - stopStartTs) / 1000;
                if (dur >= MIN_STOP_S) {
                    trip.stops++;
                    db.insertEvent(new Db.Event(trip.id, stopStartTs, Db.EV_STOP, stopLat, stopLon, dur));
                }
            }
        }

        cells.add(Geo.cell(loc.getLatitude(), loc.getLongitude(), Geo.ROUTE_CELL));

        if (now - lastPointTs >= 2000) {
            pending.add(new Db.Point(now, loc.getLatitude(), loc.getLongitude(), spd));
            lastPointTs = now;
        }

        last = loc;
        lastFixTs = now;
        trip.endTs = now;

        // Auto-finish when parked near the destination
        if (autoStop && hasDest && trip.distM > 500) {
            double d = distanceToDestM();
            if (d >= 0 && d < 150 && spd < MOVING_MS) {
                if (nearDestSince == 0) nearDestSince = now;
                else if (now - nearDestSince > 45000) autoFinished = true;
            } else {
                nearDestSince = 0;
            }
        }

        if (now - lastFlush > 10000) flush();
    }

    // ---------------------------------------------------------------- accelerometer

    public synchronized void onAccel(float[] v, long nowMs) {
        if (!gravityInit) {
            System.arraycopy(v, 0, gravity, 0, 3);
            gravityInit = true;
            return;
        }
        final float a = 0.92f;
        for (int i = 0; i < 3; i++) gravity[i] = a * gravity[i] + (1 - a) * v[i];
        double gNorm = Math.sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]);
        if (gNorm < 1) return;
        // Vertical component of the linear acceleration (projection on gravity direction)
        double vert = ((v[0] - gravity[0]) * gravity[0] + (v[1] - gravity[1]) * gravity[1] + (v[2] - gravity[2]) * gravity[2]) / gNorm;
        if (Math.abs(vert) > bumpThreshold && speedMs > MIN_BUMP_SPEED_MS && nowMs - lastBumpTs > BUMP_COOLDOWN_MS) {
            addBump(nowMs);
        }
    }

    /** Also used by the manual "+ Breaker" button. */
    public synchronized void addBump(long nowMs) {
        if (last == null) return;
        double lat = last.getLatitude(), lon = last.getLongitude();
        if (lastBumpTs > 0 && Geo.distM(lat, lon, lastBumpLat, lastBumpLon) < 20) return; // same breaker
        lastBumpTs = nowMs;
        lastBumpLat = lat;
        lastBumpLon = lon;
        trip.bumps++;
        db.insertEvent(new Db.Event(trip.id, nowMs, Db.EV_BUMP, lat, lon, 0));
    }

    public synchronized boolean isStopped() {
        return stopped;
    }

    // ---------------------------------------------------------------- persistence

    private void flush() {
        db.insertPoints(trip.id, pending);
        pending.clear();
        trip.cells = joinCells();
        db.updateTrip(trip);
        lastFlush = now();
    }

    private String joinCells() {
        StringBuilder sb = new StringBuilder();
        for (String c : cells) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        return sb.toString();
    }

    /** Ends the trip. Returns true if it was kept, false if discarded as too short. */
    public synchronized boolean finish() {
        if (stopped && last != null) {
            // still standing at the end (e.g. parking) - not a traffic stop
            stopped = false;
        }
        trip.endTs = Math.max(trip.endTs, trip.startTs);
        if (now() - trip.endTs < 120000 && !autoFinished) trip.endTs = now();
        if (autoFinished && nearDestSince > 0) {
            // arrival time, not when we noticed; the wait while parked is not traffic
            trip.stoppedS = Math.max(0, trip.stoppedS - (lastFixTs - nearDestSince) / 1000);
            trip.endTs = nearDestSince;
        }
        flush();
        if (trip.distM < 300 || trip.durationS() < 120) {
            db.deleteTrip(trip.id);
            return false;
        }
        trip.active = false;
        db.updateTrip(trip);
        return true;
    }
}
