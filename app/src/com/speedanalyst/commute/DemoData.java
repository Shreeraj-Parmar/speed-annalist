package com.speedanalyst.commute;

import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * Generates 30 days of realistic sample commutes so the full analysis can be previewed
 * before real data exists. Demo trips are flagged and can be removed in Settings.
 */
public final class DemoData {
    private DemoData() {
    }

    static final double HOME_LAT = 23.0225, HOME_LON = 72.5714;
    static final double OFFICE_LAT = 23.0720, OFFICE_LON = 72.5180;

    // Two different roads between home and office
    private static final double[][] ROUTE_A = {
            {HOME_LAT, HOME_LON}, {23.0400, 72.5714}, {23.0560, 72.5600}, {23.0720, 72.5450}, {OFFICE_LAT, OFFICE_LON}};
    private static final double[][] ROUTE_B = {
            {HOME_LAT, HOME_LON}, {23.0225, 72.5400}, {23.0450, 72.5250}, {23.0600, 72.5180}, {OFFICE_LAT, OFFICE_LON}};

    // Traffic signals / jams as fraction of route length + base wait (s)
    private static final double[][] JAMS_A = {{0.25, 60}, {0.55, 170}, {0.8, 50}};
    private static final double[][] JAMS_B = {{0.3, 45}, {0.7, 55}};
    private static final double[] BREAKERS_A = {0.1, 0.18, 0.4, 0.62, 0.9};
    private static final double[] BREAKERS_B = {0.2, 0.5};

    public static void generate(Db db, Prefs prefs) {
        Random rnd = new Random(7);
        if (!prefs.hasHome()) prefs.setHome(HOME_LAT, HOME_LON, true);
        if (!prefs.hasOffice()) prefs.setOffice(OFFICE_LAT, OFFICE_LON, true);

        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            Calendar day = Calendar.getInstance();
            day.add(Calendar.DAY_OF_MONTH, -1);
            int made = 0;
            while (made < Analyzer.FULL_REPORT_DAYS) {
                int dow = day.get(Calendar.DAY_OF_WEEK);
                if (dow != Calendar.SUNDAY) {
                    boolean useB = rnd.nextDouble() < 0.35;
                    int depOffice = 8 * 60 + rnd.nextInt(90);
                    simulate(db, rnd, day, depOffice, Db.DIR_TO_OFFICE, useB, dow);
                    boolean useB2 = rnd.nextDouble() < 0.4;
                    int depHome = 18 * 60 + rnd.nextInt(90);
                    simulate(db, rnd, day, depHome, Db.DIR_TO_HOME, useB2, dow);
                    made++;
                }
                day.add(Calendar.DAY_OF_MONTH, -1);
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    private static void simulate(Db db, Random rnd, Calendar day, int depMin, int dir, boolean routeB, int dow) {
        double[][] wp = routeB ? ROUTE_B : ROUTE_A;
        double[][] jams = routeB ? JAMS_B : JAMS_A;
        double[] breakers = routeB ? BREAKERS_B : BREAKERS_A;
        if (dir == Db.DIR_TO_HOME) {
            double[][] rev = new double[wp.length][];
            for (int i = 0; i < wp.length; i++) rev[i] = wp[wp.length - 1 - i];
            wp = rev;
            double[][] rj = new double[jams.length][];
            for (int i = 0; i < jams.length; i++) rj[i] = new double[]{1 - jams[jams.length - 1 - i][0], jams[jams.length - 1 - i][1]};
            jams = rj;
            double[] rb = new double[breakers.length];
            for (int i = 0; i < breakers.length; i++) rb[i] = 1 - breakers[breakers.length - 1 - i];
            breakers = rb;
        }
        // cumulative segment lengths
        double[] cum = new double[wp.length];
        for (int i = 1; i < wp.length; i++) cum[i] = cum[i - 1] + Geo.distM(wp[i - 1][0], wp[i - 1][1], wp[i][0], wp[i][1]);
        double total = cum[wp.length - 1];

        // Rush hour peaks at 9:00 and 18:45; Mondays and Saturdays differ
        int peak = dir == Db.DIR_TO_OFFICE ? 9 * 60 : 18 * 60 + 45;
        double x = (depMin - peak) / 35.0;
        double traffic = 1 + 0.7 * Math.exp(-x * x);
        if (dow == Calendar.MONDAY) traffic *= 1.18;
        if (dow == Calendar.SATURDAY) traffic *= 0.8;

        double cruise = (routeB ? 44 : 38) / 3.6 / (0.75 + 0.25 * traffic) * (0.9 + rnd.nextDouble() * 0.2);

        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, depMin / 60);
        c.set(Calendar.MINUTE, depMin % 60);
        c.set(Calendar.SECOND, rnd.nextInt(60));
        long t0 = c.getTimeInMillis();

        Db.Trip trip = new Db.Trip();
        trip.dir = dir;
        trip.startTs = t0;
        trip.demo = true;
        db.insertTrip(trip);

        List<Db.Point> pts = new ArrayList<Db.Point>();
        LinkedHashSet<String> cells = new LinkedHashSet<String>();
        double pos = 0, spd = 0, maxSpd = 0;
        long t = t0;
        int nextJam = 0, nextBreaker = 0;
        long waitUntil = 0;
        long moving = 0, stopped = 0;
        int stops = 0, bumps = 0;
        double[] ll = new double[2];
        long lastPoint = 0;

        while (pos < total) {
            double frac = pos / total;
            if (waitUntil > t) {
                spd = 0;
                stopped++;
            } else {
                if (nextJam < jams.length && frac >= jams[nextJam][0]) {
                    // Arrived at a signal/jam: maybe wait
                    double p = 0.55 + 0.3 * Math.min(1, traffic - 1) + 0.15;
                    if (rnd.nextDouble() < p) {
                        long wait = (long) (jams[nextJam][1] * traffic * (0.5 + rnd.nextDouble()));
                        if (wait >= 15) {
                            locate(wp, cum, pos, ll);
                            stops++;
                            db.insertEvent(new Db.Event(trip.id, t, Db.EV_STOP, ll[0], ll[1], wait));
                        }
                        waitUntil = t + wait * 1000;
                    }
                    nextJam++;
                    continue;
                }
                if (nextBreaker < breakers.length && frac >= breakers[nextBreaker]) {
                    spd = 4; // slow down for the breaker
                    if (rnd.nextDouble() < 0.85) {
                        locate(wp, cum, pos, ll);
                        bumps++;
                        db.insertEvent(new Db.Event(trip.id, t, Db.EV_BUMP, ll[0] + (rnd.nextDouble() - 0.5) * 0.0001,
                                ll[1] + (rnd.nextDouble() - 0.5) * 0.0001, 0));
                    }
                    nextBreaker++;
                }
                // accelerate towards cruise with noise
                double target = cruise * (0.8 + rnd.nextDouble() * 0.45);
                spd += Math.max(-2.5, Math.min(1.6, target - spd));
                if (spd < 0) spd = 0;
                pos += spd;
                if (spd < 1) stopped++;
                else moving++;
            }
            maxSpd = Math.max(maxSpd, spd);
            t += 1000;
            locate(wp, cum, Math.min(pos, total), ll);
            cells.add(Geo.cell(ll[0], ll[1], Geo.ROUTE_CELL));
            if (t - lastPoint >= 5000) {
                pts.add(new Db.Point(t, ll[0], ll[1], spd));
                lastPoint = t;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String s : cells) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        trip.endTs = t;
        trip.distM = total;
        trip.maxSpeed = maxSpd;
        trip.movingS = moving;
        trip.stoppedS = stopped;
        trip.stops = stops;
        trip.bumps = bumps;
        trip.cells = sb.toString();
        db.updateTrip(trip);
        db.insertPoints(trip.id, pts);
    }

    private static void locate(double[][] wp, double[] cum, double pos, double[] out) {
        for (int i = 1; i < wp.length; i++) {
            if (pos <= cum[i] || i == wp.length - 1) {
                double seg = cum[i] - cum[i - 1];
                double f = seg > 0 ? Math.min(1, (pos - cum[i - 1]) / seg) : 0;
                out[0] = wp[i - 1][0] + (wp[i][0] - wp[i - 1][0]) * f;
                out[1] = wp[i - 1][1] + (wp[i][1] - wp[i - 1][1]) * f;
                return;
            }
        }
    }
}
