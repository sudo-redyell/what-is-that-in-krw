package net.kyum.woori;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 환율: 자동(API 6시간 캐시) + 통화별 수동 지정. 저장소는 SharedPreferences "woori". */
public final class Rates {
    public static final String TAG = "Woori";
    public static final String PREFS = "woori";

    // code, symbol, 한글 이름, 오프라인 예비값(X→KRW)
    public static final String[][] CURRENCIES = {
        {"IDR", "Rp", "루피아(인니)", "0.0738"},
        {"USD", "$",  "달러(미국)",    "1470"},
        {"JPY", "¥",  "엔(일본)",      "9.5"},
        {"VND", "₫",  "동(베트남)",    "0.0059"},
    };

    private static final String[] ENDPOINTS = {
        "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/krw.json",
        "https://latest.currency-api.pages.dev/v1/currencies/krw.json",
    };
    private static final long AUTO_MS = 6 * 3600_000L;

    private static final ExecutorService net = Executors.newSingleThreadExecutor();
    private static volatile boolean fetching = false;

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS, 0); }

    public static String cur(Context c) { return p(c).getString("cur", "IDR"); }
    public static void setCur(Context c, String code) { p(c).edit().putString("cur", code).apply(); }

    public static boolean autoOn(Context c) { return p(c).getBoolean("auto", true); }
    public static void setAuto(Context c, boolean on) { p(c).edit().putBoolean("auto", on).apply(); }

    /** X→KRW 배율. 수동 지정이 있으면 수동값, 없으면 마지막 자동값(없으면 예비값). */
    public static double rate(Context c, String code) {
        SharedPreferences sp = p(c);
        if (sp.getBoolean("manual_" + code, false)) return sp.getFloat("mrate_" + code, 0f);
        float v = sp.getFloat("rate_" + code, 0f);
        if (v > 0) return v;
        for (String[] row : CURRENCIES) if (row[0].equals(code)) return Double.parseDouble(row[3]);
        return 1.0;
    }

    public static long rateAt(Context c, String code) { return p(c).getLong("at_" + code, 0L); }
    public static boolean isManual(Context c, String code) { return p(c).getBoolean("manual_" + code, false); }

    public static void setManualRate(Context c, String code, double v) {
        p(c).edit().putBoolean("manual_" + code, true).putFloat("mrate_" + code, (float) v).apply();
    }
    public static void clearManual(Context c, String code) { p(c).edit().remove("manual_" + code).remove("mrate_" + code).apply(); }

    /** 6시간 자동 갱신 주기인지 확인 후 필요하면 갱신. */
    public static void refreshIfStale(Context c, Runnable uiDone) {
        long oldest = Long.MAX_VALUE;
        for (String[] row : CURRENCIES) oldest = Math.min(oldest, rateAt(c, row[0]));
        if (autoOn(c) && System.currentTimeMillis() - oldest > AUTO_MS) refresh(c, uiDone);
        else if (uiDone != null) new Handler(Looper.getMainLooper()).post(uiDone);
    }

    /** 강제로 자동 갱신 (백그라운드, 완료 시 uiDone을 메인에서 호출). */
    public static void refresh(Context c, final Runnable uiDone) {
        if (fetching) return;
        fetching = true;
        final Context app = c.getApplicationContext();
        net.execute(() -> {
            try {
                JSONObject krw = null;
                for (String ep : ENDPOINTS) {
                    try {
                        String body = httpGet(ep);
                        krw = new JSONObject(body).getJSONObject("krw");
                        break;
                    } catch (Exception ignored) { }
                }
                if (krw != null) {
                    try {
                        SharedPreferences.Editor e = p(app).edit();
                        long now = System.currentTimeMillis();
                        for (String[] row : CURRENCIES) {
                            double inv = krw.getDouble(row[0].toLowerCase());
                            if (inv > 0) {
                                e.putFloat("rate_" + row[0], (float) (1.0 / inv));
                                e.putLong("at_" + row[0], now);
                            }
                        }
                        e.apply();
                        Log.i(TAG, "rates updated");
                    } catch (Exception ex) {
                        Log.w(TAG, "rates parse failed: " + ex);
                    }
                } else {
                    Log.w(TAG, "rates fetch failed (all endpoints)");
                }
            } finally {
                fetching = false;
                if (uiDone != null) new Handler(Looper.getMainLooper()).post(uiDone);
            }
        });
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection cn = (HttpURLConnection) new URL(url).openConnection();
        cn.setConnectTimeout(8000);
        cn.setReadTimeout(8000);
        cn.setRequestProperty("User-Agent", "wooridun-android/1.0");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(cn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            cn.disconnect();
        }
    }

    private Rates() {}
}
