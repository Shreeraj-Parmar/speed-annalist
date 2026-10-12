package com.speedanalyst.commute;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** SQLite storage for trips, GPS points and detected events (traffic stops, speed breakers). */
public class Db extends SQLiteOpenHelper {

    public static final int DIR_TO_OFFICE = 0;
    public static final int DIR_TO_HOME = 1;

    public static final int EV_STOP = 0;
    public static final int EV_BUMP = 1;

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "commute.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER, end_ts INTEGER,"
                + " dir INTEGER, dist_m REAL, max_speed REAL, moving_s INTEGER, stopped_s INTEGER,"
                + " stops INTEGER, bumps INTEGER, cells TEXT, demo INTEGER DEFAULT 0, active INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE points (trip_id INTEGER, ts INTEGER, lat REAL, lon REAL, spd REAL)");
        db.execSQL("CREATE INDEX idx_points_trip ON points(trip_id)");
        db.execSQL("CREATE TABLE events (trip_id INTEGER, ts INTEGER, type INTEGER, lat REAL, lon REAL, dur_s INTEGER)");
        db.execSQL("CREATE INDEX idx_events_trip ON events(trip_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    }

    // ---------------------------------------------------------------- models

    public static class Trip {
        public long id;
        public long startTs, endTs;
        public int dir;
        public double distM;
        public double maxSpeed; // m/s
        public long movingS, stoppedS;
        public int stops, bumps;
        public String cells = "";
        public boolean demo, active;

        public long durationS() {
            return Math.max(0, (endTs - startTs) / 1000);
        }

        public double durationMin() {
            return durationS() / 60.0;
        }

        /** Average speed over the whole trip in km/h. */
        public double avgKmh() {
            long d = durationS();
            return d > 0 ? distM / d * 3.6 : 0;
        }

        /** Average speed while actually moving in km/h. */
        public double movingKmh() {
            return movingS > 0 ? distM / movingS * 3.6 : avgKmh();
        }
    }

    public static class Point {
        public long ts;
        public double lat, lon, spd;

        public Point(long ts, double lat, double lon, double spd) {
            this.ts = ts;
            this.lat = lat;
            this.lon = lon;
            this.spd = spd;
        }
    }

    public static class Event {
        public long tripId, ts;
        public int type;
        public double lat, lon;
        public long durS;

        public Event(long tripId, long ts, int type, double lat, double lon, long durS) {
            this.tripId = tripId;
            this.ts = ts;
            this.type = type;
            this.lat = lat;
            this.lon = lon;
            this.durS = durS;
        }
    }

    // ---------------------------------------------------------------- trips

    private ContentValues tripValues(Trip t) {
        ContentValues v = new ContentValues();
        v.put("start_ts", t.startTs);
        v.put("end_ts", t.endTs);
        v.put("dir", t.dir);
        v.put("dist_m", t.distM);
        v.put("max_speed", t.maxSpeed);
        v.put("moving_s", t.movingS);
        v.put("stopped_s", t.stoppedS);
        v.put("stops", t.stops);
        v.put("bumps", t.bumps);
        v.put("cells", t.cells);
        v.put("demo", t.demo ? 1 : 0);
        v.put("active", t.active ? 1 : 0);
        return v;
    }

    public long insertTrip(Trip t) {
        t.id = getWritableDatabase().insert("trips", null, tripValues(t));
        return t.id;
    }

    public void updateTrip(Trip t) {
        getWritableDatabase().update("trips", tripValues(t), "id=?", new String[]{String.valueOf(t.id)});
    }

    public void deleteTrip(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String[] a = {String.valueOf(id)};
        db.delete("points", "trip_id=?", a);
        db.delete("events", "trip_id=?", a);
        db.delete("trips", "id=?", a);
    }

    public void deleteAll(boolean demoOnly) {
        SQLiteDatabase db = getWritableDatabase();
        if (demoOnly) {
            db.execSQL("DELETE FROM points WHERE trip_id IN (SELECT id FROM trips WHERE demo=1)");
            db.execSQL("DELETE FROM events WHERE trip_id IN (SELECT id FROM trips WHERE demo=1)");
            db.execSQL("DELETE FROM trips WHERE demo=1");
        } else {
            db.delete("points", null, null);
            db.delete("events", null, null);
            db.delete("trips", null, null);
        }
    }

    private static Trip readTrip(Cursor c) {
        Trip t = new Trip();
        t.id = c.getLong(0);
        t.startTs = c.getLong(1);
        t.endTs = c.getLong(2);
        t.dir = c.getInt(3);
        t.distM = c.getDouble(4);
        t.maxSpeed = c.getDouble(5);
        t.movingS = c.getLong(6);
        t.stoppedS = c.getLong(7);
        t.stops = c.getInt(8);
        t.bumps = c.getInt(9);
        t.cells = c.getString(10);
        if (t.cells == null) t.cells = "";
        t.demo = c.getInt(11) == 1;
        t.active = c.getInt(12) == 1;
        return t;
    }

    private static final String TRIP_COLS = "id,start_ts,end_ts,dir,dist_m,max_speed,moving_s,stopped_s,stops,bumps,cells,demo,active";

    /** All finished trips, newest first. dir = -1 for both directions. */
    public List<Trip> trips(int dir) {
        String where = "active=0" + (dir >= 0 ? " AND dir=" + dir : "");
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + TRIP_COLS + " FROM trips WHERE " + where + " ORDER BY start_ts DESC", null);
        List<Trip> out = new ArrayList<Trip>();
        try {
            while (c.moveToNext()) out.add(readTrip(c));
        } finally {
            c.close();
        }
        return out;
    }

    public boolean hasDemo() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM trips WHERE demo=1", null);
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    /** Called on app start: trips left "active" by a killed service are closed (or dropped if tiny). */
    public void closeOrphans() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM points WHERE trip_id IN (SELECT id FROM trips WHERE active=1 AND dist_m<300)");
        db.execSQL("DELETE FROM events WHERE trip_id IN (SELECT id FROM trips WHERE active=1 AND dist_m<300)");
        db.execSQL("DELETE FROM trips WHERE active=1 AND dist_m<300");
        db.execSQL("UPDATE trips SET active=0 WHERE active=1");
    }

    // ---------------------------------------------------------------- points / events

    public void insertPoints(long tripId, List<Point> pts) {
        if (pts.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            for (Point p : pts) {
                v.clear();
                v.put("trip_id", tripId);
                v.put("ts", p.ts);
                v.put("lat", p.lat);
                v.put("lon", p.lon);
                v.put("spd", p.spd);
                db.insert("points", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void insertEvent(Event e) {
        ContentValues v = new ContentValues();
        v.put("trip_id", e.tripId);
        v.put("ts", e.ts);
        v.put("type", e.type);
        v.put("lat", e.lat);
        v.put("lon", e.lon);
        v.put("dur_s", e.durS);
        getWritableDatabase().insert("events", null, v);
    }

    public List<Point> points(long tripId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,lat,lon,spd FROM points WHERE trip_id=? ORDER BY ts", new String[]{String.valueOf(tripId)});
        List<Point> out = new ArrayList<Point>();
        try {
            while (c.moveToNext()) out.add(new Point(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)));
        } finally {
            c.close();
        }
        return out;
    }

    public List<Event> events(long tripId) {
        return queryEvents("SELECT trip_id,ts,type,lat,lon,dur_s FROM events WHERE trip_id=? ORDER BY ts",
                new String[]{String.valueOf(tripId)});
    }

    /** All events of finished trips in one direction. */
    public List<Event> eventsForDir(int dir) {
        return queryEvents("SELECT e.trip_id,e.ts,e.type,e.lat,e.lon,e.dur_s FROM events e JOIN trips t ON t.id=e.trip_id"
                + " WHERE t.active=0 AND t.dir=?", new String[]{String.valueOf(dir)});
    }

    private List<Event> queryEvents(String sql, String[] args) {
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        List<Event> out = new ArrayList<Event>();
        try {
            while (c.moveToNext())
                out.add(new Event(c.getLong(0), c.getLong(1), c.getInt(2), c.getDouble(3), c.getDouble(4), c.getLong(5)));
        } finally {
            c.close();
        }
        return out;
    }
}
