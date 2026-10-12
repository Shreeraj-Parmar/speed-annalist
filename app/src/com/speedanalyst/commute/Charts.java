package com.speedanalyst.commute;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.List;
import java.util.Locale;

/** Lightweight canvas charts (no external libraries). */
public final class Charts {
    private Charts() {
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** Vertical bars with optional average line and highlighted bar. */
    public static class BarChart extends View {
        private final double[] values;
        private final String[] labels;
        private final int highlight;
        private final double avg;
        private final String unit;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        public BarChart(Context c, double[] values, String[] labels, int highlight, double avg, String unit) {
            super(c);
            this.values = values;
            this.labels = labels;
            this.highlight = highlight;
            this.avg = avg;
            this.unit = unit;
            text.setColor(Ui.MUTED);
            text.setTextSize(dp(c, 10));
            line.setColor(Ui.AMBER);
            line.setStrokeWidth(dp(c, 1.5f));
            line.setStyle(Paint.Style.STROKE);
            line.setPathEffect(new DashPathEffect(new float[]{dp(c, 5), dp(c, 4)}, 0));
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) dp(getContext(), 170), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (values.length == 0) return;
            float w = getWidth(), h = getHeight();
            float top = dp(getContext(), 16), bottom = h - dp(getContext(), 18), left = dp(getContext(), 4);
            double max = avg;
            for (double v : values) max = Math.max(max, v);
            if (max <= 0) max = 1;
            max *= 1.1;
            float slot = (w - left) / values.length;
            float bw = Math.max(dp(getContext(), 3), slot * 0.62f);
            float r = Math.min(bw / 2, dp(getContext(), 4));
            int labelEvery = Math.max(1, (int) Math.ceil(values.length / 8.0));
            for (int i = 0; i < values.length; i++) {
                float x = left + slot * i + (slot - bw) / 2;
                float y = bottom - (float) (values[i] / max) * (bottom - top);
                bar.setColor(i == highlight ? Ui.GREEN : Ui.BLUE);
                if (values[i] <= 0) bar.setColor(0x33FFFFFF);
                rect.set(x, Math.min(y, bottom - dp(getContext(), 2)), x + bw, bottom);
                cv.drawRoundRect(rect, r, r, bar);
                if (labels != null && i % labelEvery == 0 && labels[i] != null) {
                    float tw = text.measureText(labels[i]);
                    cv.drawText(labels[i], Math.max(0, Math.min(w - tw, x + bw / 2 - tw / 2)), h - dp(getContext(), 4), text);
                }
            }
            if (avg > 0) {
                float y = bottom - (float) (avg / max) * (bottom - top);
                Path p = new Path();
                p.moveTo(left, y);
                p.lineTo(w, y);
                cv.drawPath(p, line);
                text.setColor(Ui.AMBER);
                cv.drawText(String.format(Locale.US, "avg %.0f %s", avg, unit), left, y - dp(getContext(), 4), text);
                text.setColor(Ui.MUTED);
            }
        }
    }

    /** Speed (km/h) over time for one trip. */
    public static class SpeedLine extends View {
        private final List<Db.Point> pts;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        public SpeedLine(Context c, List<Db.Point> pts) {
            super(c);
            this.pts = pts;
            stroke.setColor(Ui.BLUE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(c, 2));
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setColor(0x3338BDF8);
            text.setColor(Ui.MUTED);
            text.setTextSize(dp(c, 10));
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) dp(getContext(), 140), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (pts.size() < 2) {
                cv.drawText("No speed data", dp(getContext(), 8), dp(getContext(), 20), text);
                return;
            }
            float w = getWidth(), h = getHeight();
            float top = dp(getContext(), 14), bottom = h - dp(getContext(), 4);
            long t0 = pts.get(0).ts, t1 = pts.get(pts.size() - 1).ts;
            double max = 10;
            for (Db.Point p : pts) max = Math.max(max, p.spd * 3.6);
            max = Math.ceil(max / 10) * 10;
            Path line = new Path(), area = new Path();
            for (int i = 0; i < pts.size(); i++) {
                Db.Point p = pts.get(i);
                float x = (float) (p.ts - t0) / Math.max(1, t1 - t0) * w;
                float y = bottom - (float) (p.spd * 3.6 / max) * (bottom - top);
                if (i == 0) {
                    line.moveTo(x, y);
                    area.moveTo(x, bottom);
                }
                line.lineTo(x, y);
                area.lineTo(x, y);
            }
            area.lineTo(w, bottom);
            area.close();
            cv.drawPath(area, fill);
            cv.drawPath(line, stroke);
            cv.drawText(String.format(Locale.US, "%.0f km/h", max), 0, top - dp(getContext(), 3), text);
        }
    }

    /** Draws the trip's path with start/end and detected stops/breakers. */
    public static class RouteShape extends View {
        private final List<Db.Point> pts;
        private final List<Db.Event> events;
        private final Paint path = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        public RouteShape(Context c, List<Db.Point> pts, List<Db.Event> events) {
            super(c);
            this.pts = pts;
            this.events = events;
            path.setColor(Ui.BLUE);
            path.setStyle(Paint.Style.STROKE);
            path.setStrokeWidth(dp(c, 3));
            path.setStrokeCap(Paint.Cap.ROUND);
            path.setStrokeJoin(Paint.Join.ROUND);
            text.setColor(Ui.MUTED);
            text.setTextSize(dp(c, 10));
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) dp(getContext(), 220), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (pts.size() < 2) {
                cv.drawText("No GPS path recorded", dp(getContext(), 8), dp(getContext(), 20), text);
                return;
            }
            double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
            for (Db.Point p : pts) {
                minLat = Math.min(minLat, p.lat);
                maxLat = Math.max(maxLat, p.lat);
                minLon = Math.min(minLon, p.lon);
                maxLon = Math.max(maxLon, p.lon);
            }
            double k = Math.cos(Math.toRadians((minLat + maxLat) / 2));
            double spanX = Math.max(1e-6, (maxLon - minLon) * k), spanY = Math.max(1e-6, maxLat - minLat);
            float pad = dp(getContext(), 14);
            float w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
            double scale = Math.min(w / spanX, h / spanY);
            float ox = pad + (float) (w - spanX * scale) / 2, oy = pad + (float) (h - spanY * scale) / 2;
            Path pa = new Path();
            for (int i = 0; i < pts.size(); i++) {
                Db.Point p = pts.get(i);
                float x = ox + (float) ((p.lon - minLon) * k * scale);
                float y = oy + (float) ((maxLat - p.lat) * scale);
                if (i == 0) pa.moveTo(x, y);
                else pa.lineTo(x, y);
            }
            cv.drawPath(pa, path);
            for (Db.Event e : events) {
                float x = ox + (float) ((e.lon - minLon) * k * scale);
                float y = oy + (float) ((maxLat - e.lat) * scale);
                dot.setColor(e.type == Db.EV_STOP ? Ui.RED : Ui.AMBER);
                cv.drawCircle(x, y, dp(getContext(), e.type == Db.EV_STOP ? 5 : 3.5f), dot);
            }
            Db.Point s = pts.get(0), en = pts.get(pts.size() - 1);
            dot.setColor(Ui.GREEN);
            cv.drawCircle(ox + (float) ((s.lon - minLon) * k * scale), oy + (float) ((maxLat - s.lat) * scale), dp(getContext(), 7), dot);
            dot.setColor(0xFFFFFFFF);
            cv.drawCircle(ox + (float) ((en.lon - minLon) * k * scale), oy + (float) ((maxLat - en.lat) * scale), dp(getContext(), 7), dot);
        }
    }

    /** Horizontal progress bar. */
    public static class Progress extends View {
        private final float frac;
        private final int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public Progress(Context c, float frac, int color) {
            super(c);
            this.frac = Math.max(0, Math.min(1, frac));
            this.color = color;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) dp(getContext(), 8), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float rad = getHeight() / 2f;
            r.set(0, 0, getWidth(), getHeight());
            p.setColor(0x22FFFFFF);
            cv.drawRoundRect(r, rad, rad, p);
            if (frac > 0) {
                r.set(0, 0, Math.max(getHeight(), getWidth() * frac), getHeight());
                p.setColor(color);
                cv.drawRoundRect(r, rad, rad, p);
            }
        }
    }
}
