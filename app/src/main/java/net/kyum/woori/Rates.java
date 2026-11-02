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

/** 환율: 자동(fawazahmed0 API) + 예비값 폴백. 저장소 SharedPreferences "woori". */
public final class Rates {
    public static final String TAG = "Woori";
    public static final String PREFS = "woori";

    // code, symbol, emoji flag, 오프라인 예비값(X→KRW), 한국어 명칭
    public static final String[][] CURRENCIES = {
        {"IDR", "Rp", "🇮🇩", "0.0738", "루피아"},
        {"USD", "$",  "🇺🇸", "1470", "달러"},
        {"JPY", "¥",  "🇯🇵", "9.5", "엔"},
        {"VND", "₫",  "🇻🇳", "0.0059", "동"},
    };

    private static final String[] ENDPOINTS = {
        "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/krw.json",
        "https://latest.currency-api.pages.dev/v1/currencies/krw.json",
    };

    private static final ExecutorService net = Executors.newSingleThreadExecutor();
    private static volatile boolean fetching = false;

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS, 0); }

    public static String cur(Context c) { return p(c).getString("cur", "IDR"); }
    public static void setCur(Context c, String code) { p(c).edit().putString("cur", code).apply(); }

    /** X→KRW 배율. 마지막 자동값, 없으면 예비값. */
    public static double rate(Context c, String code) {
        float v = p(c).getFloat("rate_" + code, 0f);
        if (v > 0) return v;
        for (String[] row : CURRENCIES) if (row[0].equals(code)) return Double.parseDouble(row[3]);
        return 1.0;
    }

    public static long rateAt(Context c, String code) { return p(c).getLong("at_" + code, 0L); }

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
                        krw = new JSONObject(httpGet(ep)).getJSONObject("krw");
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
