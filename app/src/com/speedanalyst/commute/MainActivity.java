package com.speedanalyst.commute;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_START = 1;
    private static final int REQ_PLACE = 2;

    private static final int TAB_DRIVE = 0, TAB_HISTORY = 1, TAB_ANALYSIS = 2, TAB_SETTINGS = 3;

    private Db db;
    private Prefs prefs;
    private FrameLayout content;
    private LinearLayout nav;
    private int tab = TAB_DRIVE;
    private int driveDir = -1;
    private int analysisDir = Db.DIR_TO_OFFICE;
    private int pendingPlace = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wasTracking;

    // live tracking views
    private TextView liveSpeed, liveGps, liveTime, liveDist, liveAvg, liveStops, liveBumps, liveStand, liveDest;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 1000);
        }
    };

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = Db.get(this);
        prefs = new Prefs(this);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        nav = Ui.hbox(this);
        nav.setBackgroundColor(0xFF0B1120);
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (TripRecorder.current() == null) db.closeOrphans();
        wasTracking = TripRecorder.current() != null;
        show(tab);
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    public void onBackPressed() {
        if (tab != TAB_DRIVE) show(TAB_DRIVE);
        else super.onBackPressed();
    }

    private void tick() {
        boolean tracking = TripRecorder.current() != null;
        if (tracking != wasTracking) {
            wasTracking = tracking;
            if (!tracking && TrackingService.lastResult != null) {
                Toast.makeText(this, TrackingService.lastResult, Toast.LENGTH_LONG).show();
            }
            if (tab == TAB_DRIVE) show(TAB_DRIVE);
            return;
        }
        if (tracking && tab == TAB_DRIVE) updateLive();
    }

    // ================================================================ navigation

    private void buildNav() {
        nav.removeAllViews();
        String[] icons = {"🚗", "📋", "📊", "⚙️"};
        String[] names = {"Drive", "History", "Analysis", "Settings"};
        for (int i = 0; i < icons.length; i++) {
            final int idx = i;
            LinearLayout b = Ui.vbox(this);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            TextView ic = Ui.text(this, icons[i], 20, Ui.TEXT, false);
            ic.setGravity(Gravity.CENTER);
            if (i != tab) ic.setAlpha(0.45f);
            TextView lb = Ui.text(this, names[i], 11, i == tab ? Ui.GREEN : Ui.MUTED, i == tab);
            lb.setGravity(Gravity.CENTER);
            b.addView(ic);
            b.addView(lb);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(idx);
                }
            });
            nav.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
    }

    private void show(int t) {
        tab = t;
        buildNav();
        content.removeAllViews();
        liveSpeed = null;
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout page = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        page.setPadding(p, p, p, p);
        sv.addView(page);
        content.addView(sv);
        switch (t) {
            case TAB_DRIVE:
                buildDrive(page);
                break;
            case TAB_HISTORY:
                buildHistory(page);
                break;
            case TAB_ANALYSIS:
                buildAnalysis(page);
                break;
            default:
                buildSettings(page);
        }
    }

    private void header(LinearLayout page, String title, String sub) {
        page.addView(Ui.text(this, title, 26, Ui.TEXT, true));
        if (sub != null) {
            TextView s = Ui.muted(this, sub);
            s.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 14));
            page.addView(s);
        }
    }

    // ================================================================ DRIVE

    private int defaultDir() {
        if (prefs.hasHome() && prefs.hasOffice()) {
            Location l = lastKnown();
            if (l != null) {
                double dh = Geo.distM(l.getLatitude(), l.getLongitude(), prefs.homeLat(), prefs.homeLon());
                double dof = Geo.distM(l.getLatitude(), l.getLongitude(), prefs.officeLat(), prefs.officeLon());
                if (Math.min(dh, dof) < 1500) return dh < dof ? Db.DIR_TO_OFFICE : Db.DIR_TO_HOME;
            }
        }
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 14 ? Db.DIR_TO_OFFICE : Db.DIR_TO_HOME;
    }

    private void buildDrive(final LinearLayout page) {
        TripRecorder rec = TripRecorder.current();
        if (rec != null) {
            buildLive(page, rec);
            return;
        }
        if (driveDir < 0) driveDir = defaultDir();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greet = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
        header(page, greet + " 👋", Geo.date(System.currentTimeMillis()) + " · tension-free commute coach");

        // 30-day progress
        List<Db.Trip> all = db.trips(-1);
        int days = Analyzer.daysTracked(all);
        LinearLayout prog = Ui.card(this);
        LinearLayout pr = Ui.hbox(this);
        TextView pt = Ui.text(this, days >= Analyzer.FULL_REPORT_DAYS ? "30-day analysis ready ✅" : "Day " + days + " of " + Analyzer.FULL_REPORT_DAYS, 16, Ui.TEXT, true);
        pr.addView(pt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pr.addView(Ui.text(this, all.size() + " trips", 13, Ui.MUTED, false));
        prog.addView(pr);
        Charts.Progress bar = new Charts.Progress(this, days / (float) Analyzer.FULL_REPORT_DAYS, Ui.GREEN);
        bar.setLayoutParams(Ui.matchWrap(this, 10));
        prog.addView(bar);
        TextView pm = Ui.muted(this, days >= Analyzer.FULL_REPORT_DAYS
                ? "Open the Analysis tab for your full report: best route, departure time and speed advice."
                : "Record every trip to and from the office. After " + (Analyzer.FULL_REPORT_DAYS - days)
                + " more day" + (Analyzer.FULL_REPORT_DAYS - days == 1 ? "" : "s") + " you get the full analysis. Early insights appear after 3 trips.");
        pm.setPadding(0, Ui.dp(this, 10), 0, 0);
        prog.addView(pm);
        page.addView(prog);

        // places setup
        if (!prefs.hasHome() || !prefs.hasOffice()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.title(this, "📍 Save your Home and Office"));
            c.addView(Ui.muted(this, "The app uses these to tell your two directions apart and to finish a trip automatically when you arrive. Stand at the place and tap the button."));
            LinearLayout row = Ui.hbox(this);
            row.setLayoutParams(Ui.matchWrap(this, 12));
            if (!prefs.hasHome()) row.addView(Ui.button(this, "I'm at Home", Ui.CARD2, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    capturePlace(0);
                }
            }), Ui.weight(this, 1, 3));
            if (!prefs.hasOffice()) row.addView(Ui.button(this, "I'm at Office", Ui.CARD2, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    capturePlace(1);
                }
            }), Ui.weight(this, 1, 3));
            c.addView(row);
            page.addView(c);
        }

        // direction
        LinearLayout dc = Ui.card(this);
        dc.addView(Ui.title(this, "Where are you going?"));
        dc.addView(Ui.segmented(this, new String[]{"🏠 → 🏢  Office", "🏢 → 🏠  Home"}, driveDir, new Ui.OnPick() {
            @Override
            public void pick(int index) {
                driveDir = index;
                show(TAB_DRIVE);
            }
        }));

        // today's plan
        Analyzer.Report r = Analyzer.analyze(db, prefs, driveDir);
        TextView plan;
        if (r.trips.size() >= 3) {
            String leave;
            if (r.recommendedSlot != null) {
                leave = "Leave at " + Geo.hhmm(r.recommendedSlot.startMin) + " to arrive relaxed by "
                        + Geo.hhmm(r.recommendedSlot.startMin + (int) Math.round(r.recommendedSlot.p85Min)) + ".";
            } else {
                leave = "To reach by " + Geo.hhmm(r.targetArrivalMin) + ", leave by "
                        + Geo.hhmm(r.targetArrivalMin - (int) Math.round(r.p90Min) - 5) + ".";
            }
            String route = "";
            if (r.routes.size() > 1) {
                Analyzer.Route f = null;
                for (Analyzer.Route rt : r.routes) if (rt.trips.size() >= 2 && (f == null || rt.avgMin < f.avgMin)) f = rt;
                if (f != null) route = "\nTake " + f.name + " (" + Geo.dur(f.avgMin) + " on average).";
            }
            plan = Ui.body(this, "🧭 " + leave + route + "\nUsually " + Geo.dur(r.medianMin) + ". Cruise steadily at "
                    + r.steadyLowKmh + "–" + r.steadyHighKmh + " km/h.");
        } else {
            plan = Ui.muted(this, "🧭 Just drive the way you normally do. After 3 trips in this direction, your personal plan will show up here.");
        }
        plan.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        dc.addView(plan);

        TextView start = Ui.button(this, "▶  START DRIVE", Ui.GREEN, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDrive();
            }
        });
        start.setTextSize(20);
        start.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 20));
        dc.addView(start, Ui.matchWrap(this, 0));
        TextView hint = Ui.muted(this, "Tap Start when you leave and keep the phone in a holder. Speed, stops and speed breakers are recorded automatically.");
        hint.setPadding(0, Ui.dp(this, 10), 0, 0);
        dc.addView(hint);
        page.addView(dc);

        if (TrackingService.lastResult != null) {
            LinearLayout lc = Ui.card(this);
            lc.addView(Ui.title(this, "Last trip"));
            lc.addView(Ui.body(this, TrackingService.lastResult));
            page.addView(lc);
        }

        if (all.isEmpty()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.title(this, "👀 Want to see what you'll get?"));
            c.addView(Ui.muted(this, "Load 30 days of sample commutes and look at the full Analysis report. You can remove the sample data in Settings at any time."));
            c.addView(Ui.button(this, "Load demo data", Ui.CARD2, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadDemo();
                }
            }), Ui.matchWrap(this, 12));
            page.addView(c);
        }
    }

    private void buildLive(LinearLayout page, final TripRecorder rec) {
        header(page, rec.trip.dir == Db.DIR_TO_OFFICE ? "🏠 → 🏢 To Office" : "🏢 → 🏠 To Home",
                "Started " + Geo.time(rec.trip.startTs) + " · drive safely, don't touch the phone");

        LinearLayout sc = Ui.card(this);
        sc.setGravity(Gravity.CENTER_HORIZONTAL);
        liveSpeed = Ui.text(this, "0", 84, Ui.TEXT, true);
        liveSpeed.setGravity(Gravity.CENTER);
        sc.addView(liveSpeed);
        TextView unit = Ui.muted(this, "km/h");
        unit.setGravity(Gravity.CENTER);
        sc.addView(unit);
        liveGps = Ui.muted(this, "");
        liveGps.setGravity(Gravity.CENTER);
        liveGps.setPadding(0, Ui.dp(this, 8), 0, 0);
        sc.addView(liveGps);
        page.addView(sc);

        LinearLayout st = Ui.card(this);
        LinearLayout t1 = Ui.stat(this, "00:00", "time", Ui.TEXT);
        LinearLayout t2 = Ui.stat(this, "0.0 km", "distance", Ui.TEXT);
        LinearLayout t3 = Ui.stat(this, "0", "avg km/h", Ui.BLUE);
        LinearLayout t4 = Ui.stat(this, "0", "traffic stops", Ui.RED);
        LinearLayout t5 = Ui.stat(this, "0", "breakers", Ui.AMBER);
        LinearLayout t6 = Ui.stat(this, "0 min", "standing", Ui.PURPLE);
        liveTime = (TextView) t1.getChildAt(0);
        liveDist = (TextView) t2.getChildAt(0);
        liveAvg = (TextView) t3.getChildAt(0);
        liveStops = (TextView) t4.getChildAt(0);
        liveBumps = (TextView) t5.getChildAt(0);
        liveStand = (TextView) t6.getChildAt(0);
        st.addView(Ui.statRow(this, t1, t2, t3));
        st.addView(Ui.statRow(this, t4, t5, t6));
        liveDest = Ui.muted(this, "");
        liveDest.setPadding(0, Ui.dp(this, 12), 0, 0);
        st.addView(liveDest);
        page.addView(st);

        LinearLayout row = Ui.hbox(this);
        row.addView(Ui.button(this, "+ Breaker", Ui.AMBER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rec.addBump(System.currentTimeMillis());
                updateLive();
            }
        }), Ui.weight(this, 1, 3));
        row.addView(Ui.button(this, "■ Finish trip", Ui.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TrackingService.stop(MainActivity.this);
            }
        }), Ui.weight(this, 1.4f, 3));
        page.addView(row);
        TextView hint = Ui.muted(this, "The trip finishes by itself about 45 seconds after you park at your destination. Breakers are detected from the phone's motion sensor; tap “+ Breaker” only if one was missed.");
        hint.setPadding(0, Ui.dp(this, 12), 0, 0);
        page.addView(hint);
        updateLive();
    }

    private void updateLive() {
        TripRecorder rec = TripRecorder.current();
        if (rec == null || liveSpeed == null) return;
        Db.Trip t = rec.trip;
        long kmh = Math.round(rec.speedMs * 3.6);
        liveSpeed.setText(String.valueOf(kmh));
        liveSpeed.setTextColor(kmh > 70 ? Ui.RED : kmh > 50 ? Ui.AMBER : kmh < 4 || !rec.gpsFix ? Ui.MUTED : Ui.GREEN);
        if (!rec.gpsFix) liveGps.setText("📡 Waiting for GPS signal…");
        else liveGps.setText(rec.isStopped() ? "🚦 Standing — counting traffic time" : String.format(Locale.US, "📡 GPS ±%.0f m", rec.accuracy));
        long el = rec.elapsedS();
        liveTime.setText(Geo.clock(el));
        liveDist.setText(Geo.km(t.distM));
        liveAvg.setText(el > 0 ? String.valueOf(Math.round(t.distM / el * 3.6)) : "0");
        liveStops.setText(String.valueOf(t.stops));
        liveBumps.setText(String.valueOf(t.bumps));
        liveStand.setText(Geo.dur(t.stoppedS / 60.0));
        double d = rec.distanceToDestM();
        liveDest.setText(d >= 0 ? String.format(Locale.US, "🏁 %.1f km to destination (straight line)", d / 1000) : "Tip: set Home/Office in Settings to finish trips automatically.");
    }

    private void startDrive() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            List<String> perms = new ArrayList<String>();
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(perms.toArray(new String[0]), REQ_START);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            new AlertDialog.Builder(this)
                    .setTitle("Turn on GPS")
                    .setMessage("Location is switched off. Turn it on so your drive can be recorded.")
                    .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        TrackingService.lastResult = null;
        TrackingService.start(this, driveDir);
        // The service creates the recorder synchronously on the main thread; refresh shortly after.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                wasTracking = TripRecorder.current() != null;
                show(TAB_DRIVE);
            }
        }, 400);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, "Precise location is needed to record your drive", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == REQ_START) startDrive();
        else if (requestCode == REQ_PLACE && pendingPlace >= 0) capturePlace(pendingPlace);
    }

    private Location lastKnown() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }

    /** which: 0 = home, 1 = office. Waits for a decent GPS fix then saves it. */
    private void capturePlace(final int which) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingPlace = which;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PLACE);
            return;
        }
        pendingPlace = -1;
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(which == 0 ? "Saving Home" : "Saving Office")
                .setMessage("Getting your exact location… (stand outside or near a window for a faster fix)")
                .setNegativeButton("Cancel", null)
                .create();
        final Location[] best = new Location[1];
        final LocationListener ll = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                if (best[0] == null || l.getAccuracy() < best[0].getAccuracy()) best[0] = l;
                if (l.getAccuracy() <= 30) finishCapture(which, lm, this, dlg, best[0]);
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle b) {
            }

            @Override
            public void onProviderEnabled(String p) {
            }

            @Override
            public void onProviderDisabled(String p) {
            }
        };
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                lm.removeUpdates(ll);
            }
        });
        dlg.show();
        try {
            for (String p : lm.getProviders(true)) {
                if (LocationManager.GPS_PROVIDER.equals(p) || LocationManager.NETWORK_PROVIDER.equals(p))
                    lm.requestLocationUpdates(p, 1000, 0, ll, Looper.getMainLooper());
            }
        } catch (SecurityException ignored) {
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!dlg.isShowing()) return;
                Location l = best[0] != null ? best[0] : lastKnown();
                if (l != null) finishCapture(which, lm, ll, dlg, l);
                else {
                    dlg.dismiss();
                    Toast.makeText(MainActivity.this, "Couldn't get a location. Is GPS on?", Toast.LENGTH_LONG).show();
                }
            }
        }, 25000);
    }

    private void finishCapture(int which, LocationManager lm, LocationListener ll, AlertDialog dlg, Location l) {
        lm.removeUpdates(ll);
        if (!dlg.isShowing()) return;
        dlg.dismiss();
        if (which == 0) prefs.setHome(l.getLatitude(), l.getLongitude(), false);
        else prefs.setOffice(l.getLatitude(), l.getLongitude(), false);
        Toast.makeText(this, (which == 0 ? "Home" : "Office") + " saved ✓", Toast.LENGTH_SHORT).show();
        show(tab);
    }

    // ================================================================ HISTORY

    private void buildHistory(LinearLayout page) {
        List<Db.Trip> trips = db.trips(-1);
        header(page, "History", trips.size() + " trips over " + Analyzer.daysTracked(trips) + " days");
        if (trips.isEmpty()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.muted(this, "No trips yet. Start one from the Drive tab."));
            page.addView(c);
            return;
        }
        List<Analyzer.Route> toOffice = Analyzer.clusterRoutes(reversed(db.trips(Db.DIR_TO_OFFICE)));
        List<Analyzer.Route> toHome = Analyzer.clusterRoutes(reversed(db.trips(Db.DIR_TO_HOME)));
        int shown = 0;
        for (final Db.Trip t : trips) {
            if (++shown > 200) break;
            LinearLayout c = Ui.card(this);
            LinearLayout top = Ui.hbox(this);
            TextView d = Ui.text(this, Geo.date(t.startTs) + " · " + Geo.time(t.startTs), 13, Ui.MUTED, false);
            top.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            String rn = Analyzer.routeNameOf(t.dir == Db.DIR_TO_OFFICE ? toOffice : toHome, t);
            TextView badge = Ui.text(this, rn + (t.demo ? " · demo" : ""), 11, Ui.BLUE, true);
            top.addView(badge);
            c.addView(top);
            TextView main = Ui.text(this, (t.dir == Db.DIR_TO_OFFICE ? "🏠→🏢  " : "🏢→🏠  ") + Geo.dur(t.durationMin())
                    + "  ·  " + Geo.km(t.distM), 18, Ui.TEXT, true);
            main.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            c.addView(main);
            c.addView(Ui.muted(this, String.format(Locale.US, "%.0f km/h avg · top %.0f km/h · 🚦 %d stops (%s) · 〰️ %d breakers",
                    t.avgKmh(), t.maxSpeed * 3.6, t.stops, Geo.dur(t.stoppedS / 60.0), t.bumps)));
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTrip(t);
                }
            });
            c.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(t);
                    return true;
                }
            });
            page.addView(c);
        }
    }

    private static List<Db.Trip> reversed(List<Db.Trip> l) {
        List<Db.Trip> r = new ArrayList<Db.Trip>(l);
        java.util.Collections.reverse(r);
        return r;
    }

    private void confirmDelete(final Db.Trip t) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this trip?")
                .setMessage(Geo.date(t.startTs) + " · " + Geo.dur(t.durationMin()))
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        db.deleteTrip(t.id);
                        show(tab);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTrip(final Db.Trip t) {
        List<Db.Point> pts = db.points(t.id);
        List<Db.Event> evs = db.events(t.id);
        ScrollView sv = new ScrollView(this);
        LinearLayout l = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        l.setPadding(p, p, p, p);
        l.setBackgroundColor(Ui.BG);
        sv.addView(l);

        l.addView(Ui.text(this, (t.dir == Db.DIR_TO_OFFICE ? "🏠 → 🏢 To Office" : "🏢 → 🏠 To Home"), 20, Ui.TEXT, true));
        l.addView(Ui.muted(this, Geo.date(t.startTs) + " · " + Geo.time(t.startTs) + " → " + Geo.time(t.endTs)));
        l.addView(Ui.statRow(this,
                Ui.stat(this, Geo.dur(t.durationMin()), "time", Ui.TEXT),
                Ui.stat(this, Geo.km(t.distM), "distance", Ui.TEXT),
                Ui.stat(this, String.format(Locale.US, "%.0f", t.avgKmh()), "avg km/h", Ui.BLUE)));
        l.addView(Ui.statRow(this,
                Ui.stat(this, String.valueOf(t.stops), "stops", Ui.RED),
                Ui.stat(this, String.valueOf(t.bumps), "breakers", Ui.AMBER),
                Ui.stat(this, String.format(Locale.US, "%.0f", t.maxSpeed * 3.6), "top km/h", Ui.GREEN)));
        l.addView(Ui.muted(this, String.format(Locale.US, "Moving average %.0f km/h · standing in traffic %s", t.movingKmh(), Geo.dur(t.stoppedS / 60.0))), Ui.matchWrap(this, 10));

        TextView rt = Ui.title(this, "Route");
        rt.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
        l.addView(rt);
        l.addView(Ui.muted(this, "🟢 start  ⚪ end  🔴 traffic stop  🟠 speed breaker"));
        Charts.RouteShape shape = new Charts.RouteShape(this, pts, evs);
        shape.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 12)));
        l.addView(shape, Ui.matchWrap(this, 6));

        TextView spt = Ui.title(this, "Speed during the trip");
        spt.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
        l.addView(spt);
        l.addView(new Charts.SpeedLine(this, pts), Ui.matchWrap(this, 4));

        int stopsListed = 0;
        for (final Db.Event e : evs) {
            if (e.type != Db.EV_STOP) continue;
            if (stopsListed++ == 0) {
                TextView st = Ui.title(this, "Traffic stops");
                st.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
                l.addView(st);
            }
            TextView row = Ui.body(this, "🚦 " + Geo.time(e.ts) + " — waited " + (e.durS >= 60 ? Geo.dur(e.durS / 60.0) : e.durS + " s") + "  ›");
            row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openMap(e.lat, e.lon, "Traffic stop");
                }
            });
            l.addView(row);
        }

        new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        confirmDelete(t);
                    }
                })
                .show();
    }

    private void openMap(double lat, double lon, String label) {
        Uri u = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)", lat, lon, lat, lon, Uri.encode(label)));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                    "https://www.google.com/maps/search/?api=1&query=%f,%f", lat, lon))));
        }
    }

    // ================================================================ ANALYSIS

    private void buildAnalysis(LinearLayout page) {
        Analyzer.Report r = Analyzer.analyze(db, prefs, analysisDir);
        header(page, r.full ? "30-Day Report" : "Analysis",
                r.full ? "Based on " + r.daysTracked + " days of driving" : "Early insights · day " + r.daysTracked + " of " + Analyzer.FULL_REPORT_DAYS);

        page.addView(Ui.segmented(this, new String[]{"🏠 → 🏢  To Office", "🏢 → 🏠  To Home"}, analysisDir, new Ui.OnPick() {
            @Override
            public void pick(int index) {
                analysisDir = index;
                show(TAB_ANALYSIS);
            }
        }), Ui.matchWrap(this, 0));
        View gap = new View(this);
        page.addView(gap, new LinearLayout.LayoutParams(1, Ui.dp(this, 12)));

        if (!r.full) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.title(this, "⏳ Full report in " + (Analyzer.FULL_REPORT_DAYS - r.daysTracked) + " days"));
            Charts.Progress bar = new Charts.Progress(this, r.daysTracked / (float) Analyzer.FULL_REPORT_DAYS, Ui.GREEN);
            c.addView(bar);
            TextView m = Ui.muted(this, "The numbers below already use your trips, but they get more reliable every day. With 30 days of data the app can compare routes, departure times and weekdays properly.");
            m.setPadding(0, Ui.dp(this, 10), 0, 0);
            c.addView(m);
            page.addView(c);
        }

        if (r.trips.size() < 3) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.title(this, "Not enough trips yet"));
            c.addView(Ui.muted(this, "Record at least 3 trips " + (analysisDir == Db.DIR_TO_OFFICE ? "to the office" : "back home")
                    + " to see insights. You have " + r.trips.size() + " so far."));
            if (!db.hasDemo()) c.addView(Ui.button(this, "Preview with demo data", Ui.CARD2, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadDemo();
                }
            }), Ui.matchWrap(this, 12));
            page.addView(c);
            return;
        }

        // Suggestions
        LinearLayout sc = Ui.card(this);
        sc.setBackground(Ui.round(0xFF14342A, Ui.dp(this, 16)));
        sc.addView(Ui.title(this, "✨ Your plan for a tension-free commute"));
        for (String s : r.suggestions) {
            TextView t = Ui.body(this, s);
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            sc.addView(t);
        }
        page.addView(sc);

        // Summary
        LinearLayout sm = Ui.card(this);
        sm.addView(Ui.title(this, "📌 Summary · " + r.trips.size() + " trips"));
        sm.addView(Ui.statRow(this,
                Ui.stat(this, Geo.dur(r.avgMin), "average", Ui.TEXT),
                Ui.stat(this, Geo.dur(r.bestMin), "best", Ui.GREEN),
                Ui.stat(this, Geo.dur(r.worstMin), "worst", Ui.RED)));
        sm.addView(Ui.statRow(this,
                Ui.stat(this, String.format(Locale.US, "%.1f km", r.avgKm), "distance", Ui.TEXT),
                Ui.stat(this, String.format(Locale.US, "%.0f", r.avgKmh), "avg km/h", Ui.BLUE),
                Ui.stat(this, String.format(Locale.US, "%.0f", r.avgMovingKmh), "moving km/h", Ui.BLUE)));
        sm.addView(Ui.statRow(this,
                Ui.stat(this, String.format(Locale.US, "%.1f", r.avgStops), "stops/trip", Ui.RED),
                Ui.stat(this, String.format(Locale.US, "%.1f", r.avgBumps), "breakers/trip", Ui.AMBER),
                Ui.stat(this, String.format(Locale.US, "%.1f h", r.trafficHoursMonth), "traffic/month", Ui.PURPLE)));
        page.addView(sm);

        // Daily trend
        LinearLayout tc = Ui.card(this);
        tc.addView(Ui.title(this, "📈 Trip time, day by day (min)"));
        List<Db.Trip> last = r.trips.size() > 30 ? r.trips.subList(r.trips.size() - 30, r.trips.size()) : r.trips;
        double[] vals = new double[last.size()];
        String[] labels = new String[last.size()];
        int bestIdx = 0;
        for (int i = 0; i < last.size(); i++) {
            vals[i] = last.get(i).durationMin();
            labels[i] = Geo.shortDate(last.get(i).startTs);
            if (vals[i] < vals[bestIdx]) bestIdx = i;
        }
        tc.addView(new Charts.BarChart(this, vals, labels, bestIdx, r.avgMin, "min"));
        tc.addView(Ui.muted(this, "Green = your fastest day. Dashed line = average."));
        page.addView(tc);

        // Departure time
        if (!r.slots.isEmpty()) {
            LinearLayout dc = Ui.card(this);
            dc.addView(Ui.title(this, "🕒 Departure time vs trip time"));
            double[] sv = new double[r.slots.size()];
            String[] sl = new String[r.slots.size()];
            int hi = -1;
            for (int i = 0; i < r.slots.size(); i++) {
                Analyzer.Slot s = r.slots.get(i);
                sv[i] = s.avgMin;
                sl[i] = Geo.hhmm(s.startMin).replace(" AM", "").replace(" PM", "");
                if (s == (r.recommendedSlot != null ? r.recommendedSlot : r.bestSlot)) hi = i;
            }
            dc.addView(new Charts.BarChart(this, sv, sl, hi, 0, "min"));
            for (Analyzer.Slot s : r.slots) {
                dc.addView(Ui.kv(this, "Leave " + Geo.hhmm(s.startMin) + "  (" + s.count + "×)",
                        Geo.dur(s.avgMin) + " → arrive ~" + Geo.hhmm(s.startMin + (int) Math.round(s.avgMin))));
            }
            TextView tg = Ui.muted(this, "Target arrival: " + Geo.hhmm(r.targetArrivalMin) + " (change in Settings). Green bar = recommended departure.");
            tg.setPadding(0, Ui.dp(this, 8), 0, 0);
            dc.addView(tg);
            page.addView(dc);
        }

        // Routes
        LinearLayout rc = Ui.card(this);
        rc.addView(Ui.title(this, "🛣️ Your routes"));
        Analyzer.Route fastest = null;
        for (Analyzer.Route rt : r.routes) if (rt.trips.size() >= 2 && (fastest == null || rt.avgMin < fastest.avgMin)) fastest = rt;
        boolean first = true;
        for (final Analyzer.Route rt : r.routes) {
            if (!first) rc.addView(Ui.divider(this));
            first = false;
            LinearLayout h = Ui.hbox(this);
            h.addView(Ui.text(this, rt.name, 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (rt == fastest && r.routes.size() > 1) h.addView(Ui.text(this, "FASTEST", 11, Ui.GREEN, true));
            rc.addView(h);
            rc.addView(Ui.muted(this, String.format(Locale.US, "%d trips · %.1f km · %s avg (±%.0f) · best %s",
                    rt.trips.size(), rt.avgKm, Geo.dur(rt.avgMin), rt.sdMin, Geo.dur(rt.bestMin))));
            rc.addView(Ui.muted(this, String.format(Locale.US, "🚦 %.1f stops · 〰️ %.1f breakers · standing %s per trip",
                    rt.avgStops, rt.avgBumps, Geo.dur(rt.avgStoppedMin))));
            TextView view = Ui.text(this, "View route shape ›", 13, Ui.BLUE, true);
            view.setPadding(0, Ui.dp(this, 6), 0, 0);
            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTrip(rt.sample);
                }
            });
            rc.addView(view);
        }
        page.addView(rc);

        // Speed
        LinearLayout spc = Ui.card(this);
        spc.addView(Ui.title(this, "🚗 Speed vs arrival time"));
        spc.addView(Ui.kv(this, "Fastest 25% of trips · moving speed", String.format(Locale.US, "%.0f km/h", r.fastQuartileMovingKmh)));
        spc.addView(Ui.kv(this, "Slowest 25% of trips · moving speed", String.format(Locale.US, "%.0f km/h", r.slowQuartileMovingKmh)));
        spc.addView(Ui.kv(this, "Top speed · fast trips vs slow trips", String.format(Locale.US, "%.0f vs %.0f km/h", r.fastQuartileMaxKmh, r.slowQuartileMaxKmh)));
        if (r.trips.size() >= 4)
            spc.addView(Ui.kv(this, "Time saved per +10 km/h", Geo.dur(Math.max(0, r.minutesPer10Kmh))));
        spc.addView(Ui.kv(this, "Recommended steady speed", r.steadyLowKmh + "–" + r.steadyHighKmh + " km/h"));
        page.addView(spc);

        // Hotspots
        LinearLayout hc = Ui.card(this);
        hc.addView(Ui.title(this, "🚦 Traffic hotspots"));
        if (r.hotspots.isEmpty()) hc.addView(Ui.muted(this, "No place where you get stuck again and again. 🎉"));
        int i = 1;
        for (final Analyzer.Hotspot h : r.hotspots) {
            final int num = i++;
            LinearLayout row = Ui.hbox(this);
            row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            TextView t = Ui.body(this, String.format(Locale.US, "#%d  ~%s lost · seen in %d trips", num, Geo.dur(Math.max(1, h.avgDelayMin)), h.tripsSeen));
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView m = Ui.text(this, "Map ›", 13, Ui.BLUE, true);
            m.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openMap(h.lat, h.lon, "Traffic hotspot #" + num);
                }
            });
            row.addView(m);
            hc.addView(row);
        }
        page.addView(hc);

        // Breakers
        LinearLayout bc = Ui.card(this);
        bc.addView(Ui.title(this, "〰️ Speed breakers"));
        bc.addView(Ui.body(this, r.knownBreakers + " speed breakers found on this commute (seen on several trips)."));
        int bi = 1;
        for (final Analyzer.Hotspot h : r.breakers) {
            if (bi > 8) break;
            final int num = bi++;
            TextView m = Ui.text(this, "Breaker " + num + " · seen " + h.tripsSeen + "×  ›", 13, Ui.BLUE, false);
            m.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            m.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openMap(h.lat, h.lon, "Speed breaker " + num);
                }
            });
            bc.addView(m);
        }
        page.addView(bc);

        // Weekdays
        int daysWithData = 0;
        for (int d = 1; d <= 7; d++) if (r.dowCount[d] > 0) daysWithData++;
        if (daysWithData >= 2) {
            LinearLayout wc = Ui.card(this);
            wc.addView(Ui.title(this, "📅 Average by weekday (min)"));
            int[] order = {2, 3, 4, 5, 6, 7, 1};
            double[] wv = new double[7];
            String[] wl = new String[7];
            int best = -1;
            for (int k = 0; k < 7; k++) {
                wv[k] = r.dowAvg[order[k]];
                wl[k] = Geo.DOW_SHORT[order[k]];
                if (wv[k] > 0 && (best < 0 || wv[k] < wv[best])) best = k;
            }
            wc.addView(new Charts.BarChart(this, wv, wl, best, r.avgMin, "min"));
            page.addView(wc);
        }
    }

    // ================================================================ SETTINGS

    private void buildSettings(LinearLayout page) {
        header(page, "Settings", null);
        View gap = new View(this);
        page.addView(gap, new LinearLayout.LayoutParams(1, Ui.dp(this, 10)));

        LinearLayout pc = Ui.card(this);
        pc.addView(Ui.title(this, "📍 Places"));
        placeRow(pc, 0, "🏠 Home", prefs.hasHome(), prefs.homeLat(), prefs.homeLon());
        pc.addView(Ui.divider(this));
        placeRow(pc, 1, "🏢 Office", prefs.hasOffice(), prefs.officeLat(), prefs.officeLon());
        page.addView(pc);

        LinearLayout tc = Ui.card(this);
        tc.addView(Ui.title(this, "⏰ When do you want to arrive?"));
        timeRow(tc, "At the office by", prefs.arriveOfficeMin(), 0);
        timeRow(tc, "At home by", prefs.arriveHomeMin(), 1);
        tc.addView(Ui.muted(this, "Used to work out the latest relaxed departure time."));
        page.addView(tc);

        LinearLayout dc = Ui.card(this);
        dc.addView(Ui.title(this, "〰️ Speed breaker detection"));
        dc.addView(Ui.segmented(this, new String[]{"Low", "Medium", "High"}, prefs.bumpSensitivity(), new Ui.OnPick() {
            @Override
            public void pick(int index) {
                prefs.setBumpSensitivity(index);
                show(TAB_SETTINGS);
            }
        }));
        TextView bm = Ui.muted(this, "Pick High if breakers get missed, or Low if potholes count as breakers. Works best with the phone in a holder.");
        bm.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 14));
        dc.addView(bm);
        dc.addView(Ui.title(this, "🏁 Auto-finish on arrival"));
        dc.addView(Ui.segmented(this, new String[]{"Off", "On"}, prefs.autoStop() ? 1 : 0, new Ui.OnPick() {
            @Override
            public void pick(int index) {
                prefs.setAutoStop(index == 1);
                show(TAB_SETTINGS);
            }
        }));
        page.addView(dc);

        LinearLayout data = Ui.card(this);
        data.addView(Ui.title(this, "🗂️ Data"));
        if (db.hasDemo()) {
            data.addView(Ui.button(this, "Remove demo data", Ui.CARD2, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    db.deleteAll(true);
                    prefs.clearDemoPlaces();
                    Toast.makeText(MainActivity.this, "Demo data removed", Toast.LENGTH_SHORT).show();
                    show(TAB_SETTINGS);
                }
            }), Ui.matchWrap(this, 4));
        } else {
            data.addView(Ui.button(this, "Load 30 days of demo data", Ui.CARD2, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadDemo();
                }
            }), Ui.matchWrap(this, 4));
        }
        data.addView(Ui.button(this, "Delete ALL trips", 0xFF7F1D1D, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete all trips?")
                        .setMessage("This removes every recorded trip and cannot be undone.")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                db.deleteAll(false);
                                prefs.clearDemoPlaces();
                                show(TAB_SETTINGS);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }), Ui.matchWrap(this, 10));
        page.addView(data);

        LinearLayout how = Ui.card(this);
        how.addView(Ui.title(this, "ℹ️ How it works"));
        how.addView(Ui.muted(this,
                "1. Save Home and Office once.\n"
                        + "2. Tap START DRIVE every time you leave, and keep the phone in a holder.\n"
                        + "3. GPS records your route and speed. A halt of 15 seconds or more counts as a traffic stop, and the motion sensor detects speed breakers.\n"
                        + "4. Trips are grouped into routes automatically by the roads you take.\n"
                        + "5. After 30 days, the Analysis tab shows your best route, the best time to leave, a steady speed to drive at, your traffic hotspots and your slowest weekdays.\n\n"
                        + "All data stays on your phone. Nothing is uploaded."));
        page.addView(how);
        TextView v = Ui.muted(this, "Speed Analyst v1.0");
        v.setGravity(Gravity.CENTER);
        page.addView(v);
    }

    private void placeRow(LinearLayout parent, final int which, String name, boolean set, final double lat, final double lon) {
        LinearLayout row = Ui.hbox(this);
        LinearLayout txt = Ui.vbox(this);
        txt.addView(Ui.text(this, name, 15, Ui.TEXT, true));
        txt.addView(Ui.muted(this, set ? String.format(Locale.US, "%.5f, %.5f", lat, lon) : "Not set"));
        row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (set) {
            TextView m = Ui.text(this, "Map", 13, Ui.BLUE, true);
            m.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 12), 0);
            m.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openMap(lat, lon, which == 0 ? "Home" : "Office");
                }
            });
            row.addView(m);
        }
        TextView b = Ui.button(this, set ? "Update" : "Set here", Ui.CARD2, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                capturePlace(which);
            }
        });
        b.setTextSize(13);
        row.addView(b);
        parent.addView(row);
    }

    private void timeRow(LinearLayout parent, String label, final int minutes, final int which) {
        LinearLayout row = Ui.hbox(this);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        row.addView(Ui.body(this, label), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView b = Ui.button(this, Geo.hhmm(minutes), Ui.CARD2, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int h, int m) {
                        if (which == 0) prefs.setArriveOfficeMin(h * 60 + m);
                        else prefs.setArriveHomeMin(h * 60 + m);
                        show(TAB_SETTINGS);
                    }
                }, minutes / 60, minutes % 60, false).show();
            }
        });
        row.addView(b);
        parent.addView(row);
    }

    private void loadDemo() {
        if (db.hasDemo()) {
            show(TAB_ANALYSIS);
            return;
        }
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setMessage("Creating 30 days of sample commutes…")
                .setCancelable(false)
                .show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                DemoData.generate(db, prefs);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) dlg.dismiss();
                        Toast.makeText(MainActivity.this, "Demo data loaded. Here's your 30-day report.", Toast.LENGTH_LONG).show();
                        show(TAB_ANALYSIS);
                    }
                });
            }
        }).start();
    }
}
