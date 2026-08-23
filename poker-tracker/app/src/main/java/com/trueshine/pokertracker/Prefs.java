package com.trueshine.pokertracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Единственный источник правды о состоянии сессии. Хранится в SharedPreferences. */
public class Prefs {

    private static final String FILE = "poker_tracker";

    private static final String K_RUNNING = "running";
    private static final String K_START = "start";
    private static final String K_PAUSED_TOTAL = "paused_total";
    private static final String K_PAUSE_START = "pause_start";
    private static final String K_ENTRIES = "entries";
    private static final String K_NEXT_ALARM = "next_alarm";
    private static final String K_INTERVAL = "interval_min";
    private static final String K_BREAK = "break_min";
    private static final String K_BREAK_END = "break_end";
    private static final String K_LAST_ASK = "last_ask";
    private static final String K_HISTORY = "history";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static class Entry {
        public final long time;
        public final double bb;

        public Entry(long time, double bb) {
            this.time = time;
            this.bb = bb;
        }
    }

    public static class Session {
        public long start;
        public long durationMs;
        public double bb;
        public int checkins;
    }

    // ---- состояние сессии -------------------------------------------------

    public boolean isRunning() { return sp.getBoolean(K_RUNNING, false); }

    public boolean isPaused() { return sp.getLong(K_PAUSE_START, 0) > 0; }

    public long getStart() { return sp.getLong(K_START, 0); }

    public long getNextAlarm() { return sp.getLong(K_NEXT_ALARM, 0); }

    public void setNextAlarm(long at) { sp.edit().putLong(K_NEXT_ALARM, at).apply(); }

    public long getBreakEnd() { return sp.getLong(K_BREAK_END, 0); }

    public void setBreakEnd(long at) { sp.edit().putLong(K_BREAK_END, at).apply(); }

    public boolean isOnBreak() { return getBreakEnd() > System.currentTimeMillis(); }

    public int getIntervalMin() { return sp.getInt(K_INTERVAL, 15); }

    public void setIntervalMin(int m) { sp.edit().putInt(K_INTERVAL, m).apply(); }

    public int getBreakMin() { return sp.getInt(K_BREAK, 5); }

    public void setBreakMin(int m) { sp.edit().putInt(K_BREAK, m).apply(); }

    /** Момент, за который спрашиваем результат: конец прошлого интервала. */
    public long getLastAsk() { return sp.getLong(K_LAST_ASK, getStart()); }

    public void setLastAsk(long t) { sp.edit().putLong(K_LAST_ASK, t).apply(); }

    public void startSession() {
        long now = System.currentTimeMillis();
        sp.edit()
                .putBoolean(K_RUNNING, true)
                .putLong(K_START, now)
                .putLong(K_PAUSED_TOTAL, 0)
                .putLong(K_PAUSE_START, 0)
                .putLong(K_BREAK_END, 0)
                .putLong(K_LAST_ASK, now)
                .putString(K_ENTRIES, "[]")
                .apply();
    }

    public void pause() {
        if (!isRunning() || isPaused()) return;
        sp.edit().putLong(K_PAUSE_START, System.currentTimeMillis()).apply();
    }

    public void resume() {
        if (!isPaused()) return;
        long extra = System.currentTimeMillis() - sp.getLong(K_PAUSE_START, 0);
        sp.edit()
                .putLong(K_PAUSED_TOTAL, sp.getLong(K_PAUSED_TOTAL, 0) + Math.max(0, extra))
                .putLong(K_PAUSE_START, 0)
                .apply();
    }

    /** Чистое время за столом без пауз и перерывов. */
    public long elapsed() {
        long start = getStart();
        if (start == 0) return 0;
        long end = isPaused() ? sp.getLong(K_PAUSE_START, 0) : System.currentTimeMillis();
        return Math.max(0, end - start - sp.getLong(K_PAUSED_TOTAL, 0));
    }

    /** Завершает сессию, складывает её в историю и возвращает итог. */
    public Session finishSession() {
        Session s = new Session();
        s.start = getStart();
        s.durationMs = elapsed();
        s.bb = totalBb();
        s.checkins = getEntries().size();
        if (s.durationMs > 0 || s.checkins > 0) pushHistory(s);
        sp.edit()
                .putBoolean(K_RUNNING, false)
                .putLong(K_PAUSE_START, 0)
                .putLong(K_NEXT_ALARM, 0)
                .putLong(K_BREAK_END, 0)
                .apply();
        return s;
    }

    // ---- записи результатов ----------------------------------------------

    public List<Entry> getEntries() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(K_ENTRIES, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Entry(o.getLong("t"), o.getDouble("bb")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void addEntry(double bb) {
        try {
            JSONArray a = new JSONArray(sp.getString(K_ENTRIES, "[]"));
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("bb", bb);
            a.put(o);
            sp.edit().putString(K_ENTRIES, a.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public void removeEntryAt(int index) {
        try {
            JSONArray a = new JSONArray(sp.getString(K_ENTRIES, "[]"));
            if (index < 0 || index >= a.length()) return;
            JSONArray b = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (i != index) b.put(a.get(i));
            sp.edit().putString(K_ENTRIES, b.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public double totalBb() {
        double sum = 0;
        for (Entry e : getEntries()) sum += e.bb;
        return sum;
    }

    /** BB в час по чистому времени за столом. */
    public double bbPerHour() {
        long ms = elapsed();
        if (ms < 60_000L) return 0;
        return totalBb() / (ms / 3_600_000.0);
    }

    // ---- история сессий ---------------------------------------------------

    public List<Session> getHistory() {
        List<Session> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(K_HISTORY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Session s = new Session();
                s.start = o.getLong("start");
                s.durationMs = o.getLong("dur");
                s.bb = o.getDouble("bb");
                s.checkins = o.getInt("n");
                out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void pushHistory(Session s) {
        try {
            JSONArray a = new JSONArray(sp.getString(K_HISTORY, "[]"));
            JSONObject o = new JSONObject();
            o.put("start", s.start);
            o.put("dur", s.durationMs);
            o.put("bb", s.bb);
            o.put("n", s.checkins);
            JSONArray b = new JSONArray();
            b.put(o);
            for (int i = 0; i < a.length() && i < 49; i++) b.put(a.get(i));
            sp.edit().putString(K_HISTORY, b.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public void clearHistory() { sp.edit().putString(K_HISTORY, "[]").apply(); }
}
