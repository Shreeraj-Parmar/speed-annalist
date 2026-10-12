package com.speedanalyst.commute;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings: home/office locations, target arrival time, breaker sensitivity. */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public boolean hasHome() {
        return sp.contains("home_lat");
    }

    public boolean hasOffice() {
        return sp.contains("office_lat");
    }

    public double homeLat() {
        return Double.longBitsToDouble(sp.getLong("home_lat", 0));
    }

    public double homeLon() {
        return Double.longBitsToDouble(sp.getLong("home_lon", 0));
    }

    public double officeLat() {
        return Double.longBitsToDouble(sp.getLong("office_lat", 0));
    }

    public double officeLon() {
        return Double.longBitsToDouble(sp.getLong("office_lon", 0));
    }

    public void setHome(double lat, double lon, boolean fromDemo) {
        sp.edit().putLong("home_lat", Double.doubleToRawLongBits(lat))
                .putLong("home_lon", Double.doubleToRawLongBits(lon))
                .putBoolean("home_demo", fromDemo).apply();
    }

    public void setOffice(double lat, double lon, boolean fromDemo) {
        sp.edit().putLong("office_lat", Double.doubleToRawLongBits(lat))
                .putLong("office_lon", Double.doubleToRawLongBits(lon))
                .putBoolean("office_demo", fromDemo).apply();
    }

    /** Removes home/office if they were only filled in by demo data. */
    public void clearDemoPlaces() {
        SharedPreferences.Editor e = sp.edit();
        if (sp.getBoolean("home_demo", false)) e.remove("home_lat").remove("home_lon").remove("home_demo");
        if (sp.getBoolean("office_demo", false)) e.remove("office_lat").remove("office_lon").remove("office_demo");
        e.apply();
    }

    /** Desired arrival time at office, minutes after midnight (default 09:30). */
    public int arriveOfficeMin() {
        return sp.getInt("arrive_office", 9 * 60 + 30);
    }

    public void setArriveOfficeMin(int m) {
        sp.edit().putInt("arrive_office", m).apply();
    }

    /** Desired arrival time at home, minutes after midnight (default 19:00). */
    public int arriveHomeMin() {
        return sp.getInt("arrive_home", 19 * 60);
    }

    public void setArriveHomeMin(int m) {
        sp.edit().putInt("arrive_home", m).apply();
    }

    /** 0 = low, 1 = medium, 2 = high. */
    public int bumpSensitivity() {
        return sp.getInt("bump_sens", 1);
    }

    public void setBumpSensitivity(int s) {
        sp.edit().putInt("bump_sens", s).apply();
    }

    public boolean autoStop() {
        return sp.getBoolean("auto_stop", true);
    }

    public void setAutoStop(boolean b) {
        sp.edit().putBoolean("auto_stop", b).apply();
    }
}
