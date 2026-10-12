package com.speedanalyst.commute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns recorded trips into insights: routes, best departure time, speed vs time,
 * traffic hotspots, speed breakers, weekday patterns and plain-language suggestions.
 */
public class Analyzer {

    public static final int FULL_REPORT_DAYS = 30;

    private static final double HOTSPOT_CELL = 0.001;  // ~110 m
    private static final double BREAKER_CELL = 0.0005; // ~55 m
    private static final int SLOT_MIN = 15;

    // ---------------------------------------------------------------- result types

    public static class Route {
        public String name;
        public final List<Db.Trip> trips = new ArrayList<Db.Trip>();
        public Set<String> cells;
        public double avgMin, sdMin, bestMin, avgKm, avgStops, avgBumps, avgStoppedMin;
        public Db.Trip sample;
    }

    public static class Slot {
        public int startMin; // minute of day
        public int count;
        public double avgMin, p85Min;
    }

    public static class Hotspot {
        public double lat, lon;
        public int tripsSeen;
        public double avgDelayMin; // per trip in which it occurred
        public double totalMin;
    }

    public static class Report {
        public int dir;
        public List<Db.Trip> trips = new ArrayList<Db.Trip>(); // oldest first
        public int daysTracked;
        public boolean full;
        public double avgMin, medianMin, bestMin, worstMin, sdMin, p90Min;
        public double avgKm, avgKmh, avgMovingKmh, avgStops, avgBumps, avgStoppedMin;
        public double trafficHoursMonth;
        public List<Route> routes = new ArrayList<Route>();
        public List<Slot> slots = new ArrayList<Slot>();
        public Slot bestSlot;
        public Slot recommendedSlot;
        public int targetArrivalMin;
        public List<Hotspot> hotspots = new ArrayList<Hotspot>();
        public int knownBreakers;
        public List<Hotspot> breakers = new ArrayList<Hotspot>();
        public double[] dowAvg = new double[8]; // index 1..7, 0 if none
        public int[] dowCount = new int[8];
        public double fastQuartileMovingKmh, slowQuartileMovingKmh;
        public double fastQuartileMaxKmh, slowQuartileMaxKmh;
        public double minutesPer10Kmh; // time saved per +10 km/h moving speed (regression)
        public int steadyLowKmh, steadyHighKmh;
        public List<String> suggestions = new ArrayList<String>();
    }

    // ---------------------------------------------------------------- entry point

    public static int daysTracked(List<Db.Trip> all) {
        Set<Integer> days = new HashSet<Integer>();
        for (Db.Trip t : all) days.add(Geo.dayKey(t.startTs));
        return days.size();
    }

    public static Report analyze(Db db, Prefs prefs, int dir) {
        Report r = new Report();
        r.dir = dir;
        List<Db.Trip> all = db.trips(-1);
        r.daysTracked = daysTracked(all);
        r.full = r.daysTracked >= FULL_REPORT_DAYS;
        List<Db.Trip> trips = db.trips(dir);
        Collections.reverse(trips); // oldest first
        r.trips = trips;
        r.targetArrivalMin = dir == Db.DIR_TO_OFFICE ? prefs.arriveOfficeMin() : prefs.arriveHomeMin();
        if (trips.isEmpty()) return r;

        // ---- basic stats
        List<Double> mins = new ArrayList<Double>();
        double sumKm = 0, sumKmh = 0, sumMov = 0, sumStops = 0, sumBumps = 0, sumStopped = 0;
        for (Db.Trip t : trips) {
            mins.add(t.durationMin());
            sumKm += t.distM / 1000;
            sumKmh += t.avgKmh();
            sumMov += t.movingKmh();
            sumStops += t.stops;
            sumBumps += t.bumps;
            sumStopped += t.stoppedS / 60.0;
        }
        int n = trips.size();
        r.avgMin = mean(mins);
        r.sdMin = sd(mins);
        r.medianMin = percentile(mins, 50);
        r.p90Min = percentile(mins, 90);
        r.bestMin = Collections.min(mins);
        r.worstMin = Collections.max(mins);
        r.avgKm = sumKm / n;
        r.avgKmh = sumKmh / n;
        r.avgMovingKmh = sumMov / n;
        r.avgStops = sumStops / n;
        r.avgBumps = sumBumps / n;
        r.avgStoppedMin = sumStopped / n;
        r.trafficHoursMonth = r.avgStoppedMin * 22 / 60.0; // ~22 working days

        // ---- routes, slots, speed, hotspots, weekdays
        r.routes = clusterRoutes(trips);
        r.slots = slots(trips);
        pickSlots(r);
        speedAnalysis(r);
        List<Db.Event> events = db.eventsForDir(dir);
        r.hotspots = hotspots(events, n);
        r.breakers = breakers(events, n);
        r.knownBreakers = r.breakers.size();
        for (Db.Trip t : trips) {
            int d = Geo.dayOfWeek(t.startTs);
            r.dowAvg[d] += t.durationMin();
            r.dowCount[d]++;
        }
        for (int d = 1; d <= 7; d++) if (r.dowCount[d] > 0) r.dowAvg[d] /= r.dowCount[d];

        buildSuggestions(r);
        return r;
    }

    // ---------------------------------------------------------------- routes

    static Set<String> cellSet(Db.Trip t) {
        Set<String> s = new HashSet<String>();
        if (t.cells != null && t.cells.length() > 0) Collections.addAll(s, t.cells.split(","));
        return s;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String x : a) if (b.contains(x)) inter++;
        return inter / (double) (a.size() + b.size() - inter);
    }

    public static List<Route> clusterRoutes(List<Db.Trip> trips) {
        List<Route> routes = new ArrayList<Route>();
        for (Db.Trip t : trips) {
            Set<String> s = cellSet(t);
            Route best = null;
            double bestSim = 0;
            for (Route rt : routes) {
                double sim = jaccard(s, rt.cells);
                if (sim > bestSim) {
                    bestSim = sim;
                    best = rt;
                }
            }
            if (best != null && (bestSim >= 0.45 || s.isEmpty())) {
                best.trips.add(t);
            } else {
                Route rt = new Route();
                rt.cells = s;
                rt.sample = t;
                rt.trips.add(t);
                routes.add(rt);
            }
        }
        // Most used first, then name A, B, C...
        Collections.sort(routes, new Comparator<Route>() {
            @Override
            public int compare(Route a, Route b) {
                return b.trips.size() - a.trips.size();
            }
        });
        for (int i = 0; i < routes.size(); i++) {
            Route rt = routes.get(i);
            rt.name = "Route " + (char) ('A' + Math.min(i, 25));
            List<Double> m = new ArrayList<Double>();
            double km = 0, st = 0, bu = 0, sm = 0;
            for (Db.Trip t : rt.trips) {
                m.add(t.durationMin());
                km += t.distM / 1000;
                st += t.stops;
                bu += t.bumps;
                sm += t.stoppedS / 60.0;
            }
            int c = rt.trips.size();
            rt.avgMin = mean(m);
            rt.sdMin = sd(m);
            rt.bestMin = Collections.min(m);
            rt.avgKm = km / c;
            rt.avgStops = st / c;
            rt.avgBumps = bu / c;
            rt.avgStoppedMin = sm / c;
            rt.sample = rt.trips.get(rt.trips.size() - 1);
        }
        return routes;
    }

    /** Route the trip belongs to, by name, for display in history. */
    public static String routeNameOf(List<Route> routes, Db.Trip t) {
        for (Route r : routes) for (Db.Trip x : r.trips) if (x.id == t.id) return r.name;
        return "";
    }

    // ---------------------------------------------------------------- departure time

    static List<Slot> slots(List<Db.Trip> trips) {
        Map<Integer, List<Double>> m = new HashMap<Integer, List<Double>>();
        for (Db.Trip t : trips) {
            int s = Geo.minuteOfDay(t.startTs) / SLOT_MIN * SLOT_MIN;
            List<Double> l = m.get(s);
            if (l == null) m.put(s, l = new ArrayList<Double>());
            l.add(t.durationMin());
        }
        List<Slot> out = new ArrayList<Slot>();
        for (Map.Entry<Integer, List<Double>> e : m.entrySet()) {
            Slot s = new Slot();
            s.startMin = e.getKey();
            s.count = e.getValue().size();
            s.avgMin = mean(e.getValue());
            // Pessimistic estimate for a relaxed plan: 85th percentile, or mean + 1 sd when samples are few
            s.p85Min = s.count >= 5 ? percentile(e.getValue(), 85) : s.avgMin + Math.max(sd(e.getValue()), s.avgMin * 0.1);
            out.add(s);
        }
        Collections.sort(out, new Comparator<Slot>() {
            @Override
            public int compare(Slot a, Slot b) {
                return a.startMin - b.startMin;
            }
        });
        return out;
    }

    static void pickSlots(Report r) {
        int minCount = r.trips.size() >= 10 ? 2 : 1;
        Slot best = null;
        for (Slot s : r.slots) {
            if (s.count < minCount) continue;
            if (best == null || s.avgMin < best.avgMin) best = s;
        }
        r.bestSlot = best;
        // Latest departure that still arrives on time on a bad (85th percentile) day,
        // only considering departures earlier than the target arrival.
        Slot rec = null;
        for (Slot s : r.slots) {
            if (s.count < minCount) continue;
            int arrive = s.startMin + (int) Math.round(s.p85Min);
            if (s.startMin < r.targetArrivalMin && arrive <= r.targetArrivalMin && (rec == null || s.startMin > rec.startMin))
                rec = s;
        }
        r.recommendedSlot = rec;
    }

    // ---------------------------------------------------------------- speed vs time

    static void speedAnalysis(Report r) {
        List<Db.Trip> sorted = new ArrayList<Db.Trip>(r.trips);
        Collections.sort(sorted, new Comparator<Db.Trip>() {
            @Override
            public int compare(Db.Trip a, Db.Trip b) {
                return Double.compare(a.durationMin(), b.durationMin());
            }
        });
        int q = Math.max(1, sorted.size() / 4);
        double fm = 0, fx = 0, sm = 0, sx = 0;
        for (int i = 0; i < q; i++) {
            fm += sorted.get(i).movingKmh();
            fx += sorted.get(i).maxSpeed * 3.6;
            Db.Trip s = sorted.get(sorted.size() - 1 - i);
            sm += s.movingKmh();
            sx += s.maxSpeed * 3.6;
        }
        r.fastQuartileMovingKmh = fm / q;
        r.fastQuartileMaxKmh = fx / q;
        r.slowQuartileMovingKmh = sm / q;
        r.slowQuartileMaxKmh = sx / q;

        // Within the main route: how much does a higher moving speed really save?
        // Regress duration on moving speed, controlling for nothing else (simple & honest).
        List<Db.Trip> base = r.routes.isEmpty() ? r.trips : r.routes.get(0).trips;
        if (base.size() >= 4) {
            double mx = 0, my = 0;
            for (Db.Trip t : base) {
                mx += t.movingKmh();
                my += t.durationMin();
            }
            mx /= base.size();
            my /= base.size();
            double sxy = 0, sxx = 0;
            for (Db.Trip t : base) {
                sxy += (t.movingKmh() - mx) * (t.durationMin() - my);
                sxx += (t.movingKmh() - mx) * (t.movingKmh() - mx);
            }
            r.minutesPer10Kmh = sxx > 0 ? -sxy / sxx * 10 : 0;
        }

        // Steady cruising band: around the moving speed of the fastest trips, never above 60 km/h in city.
        int low = (int) (Math.floor(r.fastQuartileMovingKmh / 5) * 5);
        low = Math.max(20, Math.min(low, 50));
        r.steadyLowKmh = low;
        r.steadyHighKmh = Math.min(low + 10, 60);
    }

    // ---------------------------------------------------------------- hotspots

    static List<Hotspot> hotspots(List<Db.Event> events, int tripCount) {
        Map<String, Hotspot> m = new HashMap<String, Hotspot>();
        Map<String, Set<Long>> seen = new HashMap<String, Set<Long>>();
        for (Db.Event e : events) {
            if (e.type != Db.EV_STOP) continue;
            String k = Geo.cell(e.lat, e.lon, HOTSPOT_CELL);
            Hotspot h = m.get(k);
            if (h == null) {
                h = new Hotspot();
                double[] c = Geo.cellCenter(k, HOTSPOT_CELL);
                h.lat = c[0];
                h.lon = c[1];
                m.put(k, h);
                seen.put(k, new HashSet<Long>());
            }
            h.totalMin += e.durS / 60.0;
            seen.get(k).add(e.tripId);
        }
        List<Hotspot> out = new ArrayList<Hotspot>();
        int minSeen = tripCount >= 6 ? 2 : 1;
        for (Map.Entry<String, Hotspot> en : m.entrySet()) {
            Hotspot h = en.getValue();
            h.tripsSeen = seen.get(en.getKey()).size();
            h.avgDelayMin = h.totalMin / h.tripsSeen;
            if (h.tripsSeen >= minSeen) out.add(h);
        }
        Collections.sort(out, new Comparator<Hotspot>() {
            @Override
            public int compare(Hotspot a, Hotspot b) {
                return Double.compare(b.totalMin, a.totalMin);
            }
        });
        return out.size() > 5 ? new ArrayList<Hotspot>(out.subList(0, 5)) : out;
    }

    static List<Hotspot> breakers(List<Db.Event> events, int tripCount) {
        Map<String, Hotspot> m = new HashMap<String, Hotspot>();
        Map<String, Set<Long>> seen = new HashMap<String, Set<Long>>();
        for (Db.Event e : events) {
            if (e.type != Db.EV_BUMP) continue;
            String k = Geo.cell(e.lat, e.lon, BREAKER_CELL);
            Hotspot h = m.get(k);
            if (h == null) {
                h = new Hotspot();
                double[] c = Geo.cellCenter(k, BREAKER_CELL);
                h.lat = c[0];
                h.lon = c[1];
                m.put(k, h);
                seen.put(k, new HashSet<Long>());
            }
            seen.get(k).add(e.tripId);
        }
        List<Hotspot> out = new ArrayList<Hotspot>();
        // A breaker is "known" when it shows up on at least 2 trips (or 25% of trips)
        int minSeen = Math.max(2, (int) Math.ceil(tripCount * 0.25));
        if (tripCount < 4) minSeen = 1;
        for (Map.Entry<String, Hotspot> en : m.entrySet()) {
            Hotspot h = en.getValue();
            h.tripsSeen = seen.get(en.getKey()).size();
            if (h.tripsSeen >= minSeen) out.add(h);
        }
        return out;
    }

    // ---------------------------------------------------------------- suggestions

    static void buildSuggestions(Report r) {
        List<String> s = r.suggestions;
        String dest = r.dir == Db.DIR_TO_OFFICE ? "office" : "home";

        // 1. Departure time
        if (r.recommendedSlot != null) {
            Slot rs = r.recommendedSlot;
            s.add(String.format(Locale.US,
                    "🕒 Leave at %s. That trip usually takes %s, and even on a bad day you reach %s by %s, before your %s target. No rushing needed.",
                    Geo.hhmm(rs.startMin), Geo.dur(rs.avgMin), dest, Geo.hhmm(rs.startMin + (int) Math.round(rs.p85Min)),
                    Geo.hhmm(r.targetArrivalMin)));
        } else if (r.bestSlot != null) {
            int leave = r.targetArrivalMin - (int) Math.round(r.p90Min) - 5;
            s.add(String.format(Locale.US,
                    "🕒 To reach %s by %s without stress, leave by %s (your slow-day trip time is %s, plus 5 min spare).",
                    dest, Geo.hhmm(r.targetArrivalMin), Geo.hhmm(leave), Geo.dur(r.p90Min)));
        }
        if (r.bestSlot != null && r.slots.size() >= 2) {
            Slot worst = null;
            for (Slot x : r.slots) if (x.count >= r.bestSlot.count / 2 && (worst == null || x.avgMin > worst.avgMin)) worst = x;
            if (worst != null && worst.avgMin - r.bestSlot.avgMin >= 3) {
                s.add(String.format(Locale.US,
                        "📉 Leaving at %s is your fastest time (%s). Leaving at %s takes %s, so you lose %s just by starting at a different time.",
                        Geo.hhmm(r.bestSlot.startMin), Geo.dur(r.bestSlot.avgMin), Geo.hhmm(worst.startMin),
                        Geo.dur(worst.avgMin), Geo.dur(worst.avgMin - r.bestSlot.avgMin)));
            }
        }

        // 2. Route
        Route fastest = null, calmest = null;
        for (Route rt : r.routes) {
            if (rt.trips.size() < 2) continue;
            if (fastest == null || rt.avgMin < fastest.avgMin) fastest = rt;
            double stress = rt.avgStops + rt.avgBumps + rt.sdMin;
            if (calmest == null || stress < calmest.avgStops + calmest.avgBumps + calmest.sdMin) calmest = rt;
        }
        if (fastest != null) {
            if (r.routes.size() > 1) {
                s.add(String.format(Locale.US, "🛣️ %s is your fastest way: %s on average (%s, about %.0f stops, %.0f breakers).",
                        fastest.name, Geo.dur(fastest.avgMin), String.format(Locale.US, "%.1f km", fastest.avgKm),
                        fastest.avgStops, fastest.avgBumps));
            } else {
                s.add(String.format(Locale.US, "🛣️ You always take %s. Try another road on a few days; after a couple of trips the app can compare the two.",
                        fastest.name));
            }
            if (calmest != null && calmest != fastest && calmest.avgMin - fastest.avgMin <= 6) {
                s.add(String.format(Locale.US,
                        "😌 For a calmer drive, take %s. It is only %s slower but has %.0f fewer stops and %.0f fewer breakers per trip, and its time changes less from day to day.",
                        calmest.name, Geo.dur(Math.max(1, calmest.avgMin - fastest.avgMin)),
                        Math.max(0, fastest.avgStops - calmest.avgStops), Math.max(0, fastest.avgBumps - calmest.avgBumps)));
            }
        }

        // 3. Speed
        if (r.trips.size() >= 4) {
            if (r.minutesPer10Kmh < 3) {
                s.add(String.format(Locale.US,
                        "🚗 Driving faster barely helps. On %s, driving 10 km/h faster saved only about %s. Cruise at a steady %d–%d km/h; traffic and signals decide your arrival time, not speed.",
                        r.routes.isEmpty() ? "your route" : r.routes.get(0).name,
                        Geo.dur(Math.max(0, r.minutesPer10Kmh)), r.steadyLowKmh, r.steadyHighKmh));
            } else {
                s.add(String.format(Locale.US,
                        "🚗 Keeping a steady %d–%d km/h on open stretches helps here: each extra 10 km/h of average moving speed saved about %s. Stay within limits and slow down for breakers.",
                        r.steadyLowKmh, r.steadyHighKmh, Geo.dur(r.minutesPer10Kmh)));
            }
            if (r.fastQuartileMaxKmh + 5 < r.slowQuartileMaxKmh) {
                s.add(String.format(Locale.US,
                        "⚡ Your fastest trips had a lower top speed (%.0f km/h) than your slowest ones (%.0f km/h). Speeding up between jams does not get you there sooner.",
                        r.fastQuartileMaxKmh, r.slowQuartileMaxKmh));
            }
        }

        // 4. Hotspots
        if (!r.hotspots.isEmpty()) {
            Hotspot h = r.hotspots.get(0);
            s.add(String.format(Locale.US,
                    "🚦 Your biggest jam is hotspot #1 (see the map link below). You lose about %s there on %d of %d trips. Leave earlier or skip it with another road.",
                    Geo.dur(Math.max(1, h.avgDelayMin)), h.tripsSeen, r.trips.size()));
        }
        if (r.avgStoppedMin >= 3) {
            s.add(String.format(Locale.US,
                    "⏱️ You spend about %s standing still on each trip, roughly %.1f hours a month in traffic.",
                    Geo.dur(r.avgStoppedMin), r.trafficHoursMonth));
        }

        // 5. Breakers
        if (r.knownBreakers > 0) {
            s.add(String.format(Locale.US,
                    "〰️ This route has %d speed breakers that come up again and again. Slowing to 15–20 km/h before them is easier on your back and your car, and costs only seconds.",
                    r.knownBreakers));
        }

        // 6. Weekday
        int worstDay = 0, bestDay = 0;
        for (int d = 1; d <= 7; d++) {
            if (r.dowCount[d] < 2) continue;
            if (worstDay == 0 || r.dowAvg[d] > r.dowAvg[worstDay]) worstDay = d;
            if (bestDay == 0 || r.dowAvg[d] < r.dowAvg[bestDay]) bestDay = d;
        }
        if (worstDay != 0 && bestDay != 0 && r.dowAvg[worstDay] - r.dowAvg[bestDay] >= 4) {
            s.add(String.format(Locale.US, "📅 %ss are your slowest day (%s, compared with %s on %ss). Leave about %d min earlier on %ss.",
                    Geo.DOW[worstDay], Geo.dur(r.dowAvg[worstDay]), Geo.dur(r.dowAvg[bestDay]), Geo.DOW[bestDay],
                    (int) Math.round(r.dowAvg[worstDay] - r.avgMin) + 2, Geo.DOW[worstDay]));
        }

        // 7. Buffer
        if (r.trips.size() >= 5) {
            int buffer = (int) Math.ceil(r.p90Min - r.medianMin);
            if (buffer >= 3) {
                s.add(String.format(Locale.US,
                        "🧘 Your trip time changes from day to day. Allow %d extra minutes on top of the usual %s and you will be on time on 9 days out of 10.",
                        buffer, Geo.dur(r.medianMin)));
            } else {
                s.add("🧘 Your commute takes about the same time every day. Leave at the same time and arrive without worrying.");
            }
        }
    }

    // ---------------------------------------------------------------- math

    static double mean(List<Double> v) {
        if (v.isEmpty()) return 0;
        double s = 0;
        for (double x : v) s += x;
        return s / v.size();
    }

    static double sd(List<Double> v) {
        if (v.size() < 2) return 0;
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / (v.size() - 1));
    }

    static double percentile(List<Double> v, double p) {
        if (v.isEmpty()) return 0;
        List<Double> c = new ArrayList<Double>(v);
        Collections.sort(c);
        double idx = p / 100.0 * (c.size() - 1);
        int lo = (int) Math.floor(idx), hi = (int) Math.ceil(idx);
        return c.get(lo) + (c.get(hi) - c.get(lo)) * (idx - lo);
    }
}
