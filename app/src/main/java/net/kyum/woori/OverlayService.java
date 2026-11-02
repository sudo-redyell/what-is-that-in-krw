package net.kyum.woori;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 플로팅 버튼(Apple blue 원형) → 탭 시 입력 패널(통화·금액·K·tax) + 원화 결과. */
public class OverlayService extends Service {

    /** build.gradle의 versionName과 동기화 유지 */
    static final String VERSION = "v1.3";

    public static volatile OverlayService inst;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DecimalFormat nf = new DecimalFormat("#,##0");
    private WindowManager wm;
    private float d;

    private View bubble;
    private WindowManager.LayoutParams bubbleLp;
    private GradientDrawable bubbleBg;
    private TextView bubbleText;
    private View panel;
    private WindowManager.LayoutParams panelLp;
    private boolean panelOpen;

    private TextView resultBig, resultCap, rateInfo, darkBtn;
    private EditText amountIn, taxIn;
    private TextView kBtn;
    private LinearLayout curRow, taxRow;
    private String cur = "IDR";
    private double tax = 0;
    private boolean kOn = false;
    private long lastTotal = -1;

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        d = getResources().getDisplayMetrics().density;
        Theme.load(this);
        cur = Rates.cur(this);
        startFore();
        makeBubble();
        Rates.refresh(this, refreshUi);   // 앱 시작 시 갱신
        ui.postDelayed(hourTick, msToNextHour()); // 매 정각 갱신
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        ui.removeCallbacks(hourTick);
        if (bubble != null) wm.removeView(bubble);
        closePanel();
        inst = null;
    }

    private final Runnable hourTick = new Runnable() {
        @Override public void run() {
            Rates.refresh(OverlayService.this, refreshUi);
            ui.postDelayed(this, msToNextHour());
        }
    };
    private long msToNextHour() {
        long now = System.currentTimeMillis();
        return 3600_000L - (now % 3600_000L) + 1500;
    }
    private final Runnable refreshUi = () -> { recalc(); if (panelOpen) updateRateInfo(); };

    // ── foreground notification ────────────────────────────────
    private void startFore() {
        String ch = "woori";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel c = new NotificationChannel(ch, "우리돈으로 얼마지?", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        Notification n = b.setContentTitle("우리돈으로 얼마지?")
                .setContentText("플로팅 버튼 실행 중")
                .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                .setContentIntent(pi).setOngoing(true).build();
        startForeground(41, n);
    }

    // ── helpers ────────────────────────────────────────────────
    private int dp(float v) { return (int) (v * d + 0.5f); }

    private GradientDrawable pill(int fill, int stroke, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(Math.min(rDp * d, 10000));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView mkText(String s, int sizeSp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(t.getTypeface(), weight);
        return t;
    }

    // ── bubble ─────────────────────────────────────────────────
    private void makeBubble() {
        bubbleText = mkText("₩", 20, Theme.ON_PRIMARY, Typeface.BOLD);
        bubbleText.setGravity(Gravity.CENTER);
        int size = dp(56);
        bubbleBg = new GradientDrawable();
        bubbleBg.setColor(Theme.PRIMARY);
        bubbleBg.setCornerRadius(size);
        bubbleText.setBackground(bubbleBg);
        bubble = bubbleText;

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        bubbleLp = new WindowManager.LayoutParams(size, size,
                overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = screenW - size - dp(8);
        bubbleLp.y = screenH / 3;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float dy0, dx0; int sx, sy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx0 = e.getRawX() - bubbleLp.x; dy0 = e.getRawY() - bubbleLp.y;
                        sx = (int) e.getRawX(); sy = (int) e.getRawY(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        bubbleLp.x = (int) (e.getRawX() - dx0);
                        bubbleLp.y = (int) (e.getRawY() - dy0);
                        if (Math.abs(e.getRawX() - sx) > dp(6) || Math.abs(e.getRawY() - sy) > dp(6)) moved = true;
                        wm.updateViewLayout(bubble, bubbleLp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) togglePanel();
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bubbleLp);
        updateBubble();
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void updateBubble() {
        bubbleBg.setColor(Theme.PRIMARY);
        String s = lastTotal >= 0 ? "₩" + nf.format(lastTotal) : "₩";
        float sp = s.length() <= 2 ? 20 : s.length() <= 6 ? 15 : s.length() <= 8 ? 12 : 10;
        bubbleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        bubbleText.setText(s);
    }

    // ── session persistence (마지막 세션 기억) ────────────────
    private void saveSession() {
        String digits = amountIn == null ? "" : amountIn.getText().toString().replaceAll("[^0-9]", "");
        getSharedPreferences(Rates.PREFS, 0).edit()
                .putString("amount", digits)
                .putString("tax", String.valueOf(tax))
                .putBoolean("kOn", kOn)
                .apply();
    }

    // ── panel ──────────────────────────────────────────────────
    private void togglePanel() { if (panelOpen) closePanel(); else openPanel(); }

    private void closePanel() {
        if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) { } panel = null; }
        panelOpen = false;
    }

    private void openPanel() {
        buildPanel();
        int w = Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(24));
        panelLp = new WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = Math.max(dp(12), Math.min(bubbleLp.x - w + dp(56),
                getResources().getDisplayMetrics().widthPixels - w - dp(12)));
        panelLp.y = dp(48);
        panel.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) closePanel();
            return false;
        });
        wm.addView(panel, panelLp);
        panelOpen = true;
        updateRateInfo();
        recalc();
    }

    private void buildPanel() {
        if (panel != null) closePanel();
        Theme.load(this);
        // restore last session
        String amt0 = getSharedPreferences(Rates.PREFS, 0).getString("amount", "");
        kOn = getSharedPreferences(Rates.PREFS, 0).getBoolean("kOn", false);
        try { tax = Double.parseDouble(getSharedPreferences(Rates.PREFS, 0).getString("tax", "0")); }
        catch (Exception e) { tax = 0; }
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(Theme.S_CONTENT), dp(Theme.S_CONTENT), dp(Theme.S_CONTENT), dp(Theme.S_CONTENT));
        card.setBackground(pill(Theme.CANVAS, 0, Theme.R_CARD));
        sv.addView(card);

        // header: title / dark toggle / close
        LinearLayout head = row();
        head.addView(weight(mkText("우리돈으로 얼마지?", 17, Theme.FOREGROUND, Typeface.BOLD), 1));
        darkBtn = mkText(Theme.dark ? "☀" : "☾", 18, Theme.FOREGROUND, 0);
        darkBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        darkBtn.setOnClickListener(v -> {
            Theme.saveDark(this, !Theme.dark);
            Theme.load(this);
            updateBubble();
            boolean was = panelOpen;
            closePanel();
            if (was) openPanel();
        });
        head.addView(darkBtn);
        TextView close = mkText("✕", 16, Theme.MUTED, 0);
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> closePanel());
        head.addView(close);
        card.addView(head);

        // currency pills: flag + symbol
        curRow = row();
        curRow.setPadding(0, dp(12), 0, dp(4));
        for (String[] c : Rates.CURRENCIES) curRow.addView(curPill(c));
        card.addView(wrapH(curRow));

        // amount + K
        LinearLayout arow = row();
        arow.setPadding(0, dp(8), 0, 0);
        amountIn = new EditText(this);
        amountIn.setHint("금액 입력 (예: 100.000)");
        amountIn.setHintTextColor(Theme.MUTED);
        amountIn.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amountIn.setTextColor(Theme.FOREGROUND);
        amountIn.setTextSize(17);
        amountIn.setSingleLine(true);
        amountIn.setBackground(pill(Theme.SURFACE, Theme.HAIRLINE, Theme.R_CONTROL));
        amountIn.setPadding(dp(14), dp(12), dp(14), dp(12));
        amountIn.addTextChangedListener(new TextWatcher() {
            boolean self;
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                if (self) return;
                String digits = e.toString().replaceAll("[^0-9]", "");
                if (digits.length() > 15) digits = digits.substring(0, 15);
                String f = digits.isEmpty() ? "" : nf.format(Long.parseLong(digits));
                if (!f.equals(e.toString())) {
                    // watcher 제거 후 setText 방식 — afterTextChanged 내 e.replace는
                    // 삼성 키보드와의 경합으로 되돌려질 수 있다
                    removeOneself();
                    amountIn.setText(f);
                    addOneself();
                    amountIn.setSelection(f.length());
                }
                try { amountIn.setSelection(f.length()); } catch (Exception ignored) { }
                recalc();
            }
            private void removeOneself() { amountIn.removeTextChangedListener(this); }
            private void addOneself() { amountIn.addTextChangedListener(this); }
        });
        if (!amt0.isEmpty()) {
            try { amountIn.setText(nf.format(Long.parseLong(amt0))); } catch (Exception ignored) { }
        }
        arow.addView(weight(amountIn, 1));
        kBtn = mkText("K", 15, kOn ? Theme.ON_PRIMARY : Theme.FOREGROUND, Typeface.BOLD);
        kBtn.setGravity(Gravity.CENTER);
        kBtn.setBackground(pill(kOn ? Theme.PRIMARY : Color0(), kOn ? 0 : Theme.HAIRLINE, Theme.R_CONTROL));
        kBtn.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, dp(44));
        klp.leftMargin = dp(8);
        kBtn.setLayoutParams(klp);
        kBtn.setOnClickListener(v -> {
            kOn = !kOn;
            kBtn.setTextColor(kOn ? Theme.ON_PRIMARY : Theme.FOREGROUND);
            kBtn.setBackground(pill(kOn ? Theme.PRIMARY : Color0(), kOn ? 0 : Theme.HAIRLINE, Theme.R_CONTROL));
            recalc();
        });
        arow.addView(kBtn);
        card.addView(arow);

        // tax row (single line): custom input first, then presets
        TextView taxLbl = mkText("세금 / 가산세 (%)", 12, Theme.MUTED, 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(14);
        card.addView(taxLbl, tlp);

        taxIn = new EditText(this);
        taxIn.setHint("직접");
        taxIn.setHintTextColor(Theme.MUTED);
        taxIn.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        taxIn.setTextColor(Theme.FOREGROUND);
        taxIn.setTextSize(13);
        taxIn.setSingleLine(true);
        taxIn.setGravity(Gravity.CENTER);
        taxIn.setBackground(pill(Theme.SURFACE, Theme.HAIRLINE, Theme.R_CONTROL));
        taxIn.addTextChangedListener(watcher());
        taxRow = row();
        taxRow.setPadding(0, dp(6), 0, 0);
        taxRow.addView(fixed(taxIn, dp(76), dp(Theme.H_COMPACT), 0));
        for (int preset = 0; preset <= 25; preset += 5) taxRow.addView(taxPill(preset));
        card.addView(wrapH(taxRow));

        // result card
        LinearLayout res = new LinearLayout(this);
        res.setOrientation(LinearLayout.VERTICAL);
        res.setBackground(pill(Theme.SURFACE, 0, Theme.R_CARD));
        res.setPadding(dp(Theme.S_CONTENT), dp(16), dp(Theme.S_CONTENT), dp(16));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(14);
        resultBig = mkText("₩0", 32, Theme.FOREGROUND, Typeface.BOLD);
        resultCap = mkText("", 12, Theme.MUTED, 0);
        res.addView(resultBig);
        res.addView(resultCap);
        card.addView(res, rlp);

        // rate info
        rateInfo = mkText("", 12, Theme.MUTED, 0);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = dp(14);
        card.addView(rateInfo, ilp);

        // buttons: refresh now / exit(red)
        LinearLayout rrow = row();
        rrow.setPadding(0, dp(8), 0, 0);
        rrow.addView(compactBtn("↻ 지금 갱신", Theme.PRIMARY, v -> {
            Rates.refresh(this, refreshUi);
            toast("환율 갱신 중…");
        }));
        rrow.addView(compactBtn("■ 앱 종료", Theme.DANGER, v -> {
            toast("우리돈으로 얼마지? 종료");
            stopSelf();
        }));
        card.addView(rrow);

        // footer: sponsor (web-home footer와 동일 문구) + 버전명(우측)
        LinearLayout frow = row();
        frow.setPadding(0, dp(12), 0, 0);
        TextView sponsor = mkText("♡ Become a sponsor to sudo-redyell", 12, Theme.LINK, 0);
        frow.addView(weight(sponsor, 1));
        sponsor.setOnClickListener(v -> {
            android.util.Log.i(Rates.TAG, "sponsor click");
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/sponsors/sudo-redyell"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                android.util.Log.i(Rates.TAG, "sponsor startActivity ok");
            } catch (Exception ex) {
                android.util.Log.e(Rates.TAG, "sponsor startActivity fail", ex);
            }
        });
        TextView ver = mkText(VERSION, 10, Theme.MUTED, 0);
        ver.setPadding(dp(8), 0, 0, 0);
        frow.addView(ver);
        card.addView(frow);

        panel = sv;
    }

    private int Color0() { return 0; } // transparent fill marker for pill()

    // ── small widgets ──────────────────────────────────────────
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    private View weight(View v, int wgt) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, wgt);
        v.setLayoutParams(lp);
        return v;
    }
    private View fixed(View v, int w, int h, int mL) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.leftMargin = mL;
        v.setLayoutParams(lp);
        return v;
    }
    private HorizontalScrollView wrapH(LinearLayout inner) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(inner);
        // 우측 페이드: 잘린 버튼(계속됨) 암시 — 나머지 폭은 캔버스색이라 무해
        GradientDrawable fade = new GradientDrawable();
        fade.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        fade.setColors(new int[]{0, 0, Theme.CANVAS}, new float[]{0f, 0.88f, 1f});
        hsv.setForeground(fade);
        return hsv;
    }
    private TextView curPill(String[] c) {
        boolean sel = c[0].equals(cur);
        TextView t = mkText(c[2] + " " + c[1], 15,
                sel ? Theme.ON_PRIMARY : Theme.FOREGROUND, sel ? Typeface.BOLD : 0);
        t.setBackground(pill(sel ? Theme.PRIMARY : 0, sel ? 0 : Theme.HAIRLINE, Theme.R_CONTROL));
        t.setPadding(dp(Theme.S_CONTROL), dp(8), dp(Theme.S_CONTROL), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            cur = c[0];
            Rates.setCur(this, cur);
            rebuildCurRow();
            updateRateInfo();
            recalc();
        });
        return t;
    }
    private void rebuildCurRow() {
        curRow.removeAllViews();
        for (String[] c : Rates.CURRENCIES) curRow.addView(curPill(c));
    }
    private TextView taxPill(int pct) {
        boolean sel = Math.abs(tax - pct) < 0.0001;
        TextView t = mkText(pct + "%", 13, sel ? Theme.ON_PRIMARY : Theme.FOREGROUND,
                sel ? Typeface.BOLD : 0);
        t.setBackground(pill(sel ? Theme.PRIMARY : 0, sel ? 0 : Theme.HAIRLINE, Theme.R_CONTROL));
        t.setPadding(dp(13), dp(7), dp(13), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            tax = pct;
            if (taxIn != null) taxIn.setText("");
            rebuildTaxRow();
            recalc();
        });
        return t;
    }
    private void rebuildTaxRow() {
        taxRow.removeAllViews();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(76), dp(Theme.H_COMPACT));
        taxRow.addView(fixed(taxIn, dp(76), dp(Theme.H_COMPACT), 0));
        for (int preset = 0; preset <= 25; preset += 5) taxRow.addView(taxPill(preset));
    }
    private TextView compactBtn(String s, int bg, View.OnClickListener l) {
        TextView t = mkText(s, 12, Theme.ON_PRIMARY, Typeface.BOLD);
        t.setBackground(pill(bg, 0, Theme.R_CONTROL));
        t.setPadding(dp(Theme.S_COMPACT + 4), 0, dp(Theme.S_COMPACT + 4), 0);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(Theme.H_COMPACT));
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }
    private TextWatcher watcher() {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { onInput(); }
        };
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ── calc ───────────────────────────────────────────────────
    private void onInput() {
        double t = parseNum(taxIn.getText().toString());
        if (t > 0 && Math.abs(t - tax) > 0.0001) {
            tax = t;
            rebuildTaxRow();
        }
        recalc();
    }

    private void recalc() {
        double amt = parseNum(amountIn != null ? amountIn.getText().toString() : "");
        if (kOn) amt *= 1000;
        double rate = Rates.rate(this, cur);
        long total = Math.round(amt * rate * (1 + tax / 100.0));
        lastTotal = amt > 0 ? total : -1;
        saveSession();
        if (resultBig != null) {
            resultBig.setText("₩" + nf.format(total));
            String sym = symOf(cur), flag = flagOf(cur);
            String r = fmtRate(rate);
            StringBuilder cap = new StringBuilder();
            if (amt > 0) {
                cap.append(nf.format(amt)).append(" ").append(sym).append(kOn ? " (K×1,000)" : "");
                cap.append(" × ").append(r);
                if (tax > 0) cap.append("  ·  +").append(trim(tax)).append("%");
            } else {
                cap.append(flag).append(" ").append(sym).append(" · 환율 ").append(r);
            }
            resultCap.setText(cap);
        }
        updateBubble();
    }

    private String symOf(String c) { for (String[] r : Rates.CURRENCIES) if (r[0].equals(c)) return r[1]; return c; }
    private String flagOf(String c) { for (String[] r : Rates.CURRENCIES) if (r[0].equals(c)) return r[2]; return c; }

    private String fmtRate(double r) {
        if (r >= 100) return nf.format(Math.round(r));
        if (r >= 1) return trim(r);
        return new DecimalFormat("0.####").format(r);
    }
    private String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** "100.000"→100000 (3자리 grouping dots), "12.5"→12.5, "1,500"→1500 */
    static double parseNum(String s) {
        if (s == null) return 0;
        s = s.trim().replace(" ", "").replace(",", "");
        if (s.isEmpty()) return 0;
        if (s.matches("\\d{1,3}(\\.\\d{3})+")) s = s.replace(".", "");
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private void updateRateInfo() {
        if (rateInfo == null) return;
        double r = Rates.rate(this, cur);
        long at = Rates.rateAt(this, cur);
        String when = at > 0 ? new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(at)) : "예비값";
        rateInfo.setText("자동(매시 정각) · 1 " + cur + " = ₩" + fmtRate(r) + " (" + when + ")");
    }
}
