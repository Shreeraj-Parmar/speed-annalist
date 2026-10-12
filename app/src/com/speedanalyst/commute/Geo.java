package com.speedanalyst.commute;

import java.util.Calendar;
import java.util.Locale;

/** Small geo + time helpers. */
public final class Geo {
    private Geo() {
    }

    /** Grid size used to fingerprint a route (~220 m). */
    public static final double ROUTE_CELL = 0.002;

    public static double distM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static String cell(double lat, double lon, double size) {
        return (long) Math.floor(lat / size) + ":" + (long) Math.floor(lon / size);
    }

    /** Centre lat/lon of a cell key produced by {@link #cell}. */
    public static double[] cellCenter(String key, double size) {
        String[] p = key.split(":");
        return new double[]{(Long.parseLong(p[0]) + 0.5) * size, (Long.parseLong(p[1]) + 0.5) * size};
    }

    public static int minuteOfDay(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** Calendar day key, e.g. 20261012. */
    public static int dayKey(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    public static int dayOfWeek(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.DAY_OF_WEEK); // 1 = Sunday
    }

    public static final String[] DOW = {"", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    public static final String[] DOW_SHORT = {"", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** 08:45 AM style. */
    public static String hhmm(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        int h = m / 60;
        String ap = h < 12 ? "AM" : "PM";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.US, "%d:%02d %s", h12, m % 60, ap);
    }

    public static String time(long ts) {
        return hhmm(minuteOfDay(ts));
    }

    public static String date(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return DOW_SHORT[c.get(Calendar.DAY_OF_WEEK)] + ", " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
    }

    public static String shortDate(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.DAY_OF_MONTH) + "/" + (c.get(Calendar.MONTH) + 1);
    }

    /** "1 h 05 min" / "24 min". */
    public static String dur(double minutes) {
        int m = (int) Math.round(minutes);
        if (m >= 60) return String.format(Locale.US, "%d h %02d min", m / 60, m % 60);
        return m + " min";
    }

    /** Live timer "12:05" or "1:02:05". */
    public static String clock(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String km(double meters) {
        return String.format(Locale.US, "%.1f km", meters / 1000.0);
    }
}
