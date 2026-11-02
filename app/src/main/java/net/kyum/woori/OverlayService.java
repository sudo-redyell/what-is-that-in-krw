package net.kyum.woori;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
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

/** 플로팅 버튼(Apple blue 원형) → 탭 시 입력 패널(통화·금액·tax) + 원화 결과. */
public class OverlayService extends Service {

    public static volatile OverlayService inst;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DecimalFormat nf = new DecimalFormat("#,##0");
    private WindowManager wm;
    private float d; // density

    private View bubble;
    private WindowManager.LayoutParams bubbleLp;
    private View panel;
    private WindowManager.LayoutParams panelLp;
    private boolean panelOpen;

    private TextView bubbleText;
    private TextView resultBig, resultCap, rateInfo;
    private EditText amountIn, taxIn, manualIn;
    private LinearLayout curRow, taxRow;
    private String cur = "IDR";
    private double tax = 0;
    private long lastTotal = -1;

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        d = getResources().getDisplayMetrics().density;
        cur = Rates.cur(this);
        startFore();
        makeBubble();
        Rates.refreshIfStale(this, () -> { recalc(); if (panelOpen) updateRateInfo(); });
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        if (bubble != null) wm.removeView(bubble);
        closePanel();
        inst = null;
    }

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
        bubbleText = mkText("₩", 20, Theme.SURFACE, android.graphics.Typeface.BOLD);
        bubbleText.setGravity(Gravity.CENTER);
        int size = dp(56);
        GradientDrawable circle = new GradientDrawable();
        circle.setColor(Theme.PRIMARY);
        circle.setCornerRadius(size);
        bubbleText.setBackground(circle);
        bubble = bubbleText;

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        bubbleLp = new WindowManager.LayoutParams(size, size,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
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
        String s = lastTotal >= 0 ? "₩" + nf.format(lastTotal) : "₩";
        float sp = s.length() <= 2 ? 20 : s.length() <= 6 ? 15 : s.length() <= 8 ? 12 : 10;
        bubbleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        bubbleText.setText(s);
    }

    // ── panel ──────────────────────────────────────────────────
    private void togglePanel() {
        if (panelOpen) closePanel(); else openPanel();
    }

    private void closePanel() {
        if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) { } panel = null; }
        panelOpen = false;
    }

    private void openPanel() {
        buildPanel();
        int w = Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(24));
        panelLp = new WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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

    private LinearLayout card;

    private void buildPanel() {
        if (panel != null) closePanel();
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        GradientDrawable cardBg = pill(Theme.CANVAS, 0, Theme.R_CARD);
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(Theme.S_CONTENT), dp(Theme.S_CONTENT), dp(Theme.S_CONTENT), dp(Theme.S_CONTENT));
        card.setBackground(cardBg);
        sv.addView(card);

        // header
        LinearLayout head = row();
        head.addView(weight(mkText("우리돈으로 얼마지?", 17, Theme.FOREGROUND, android.graphics.Typeface.BOLD), 1));
        TextView close = mkText("✕", 16, Theme.MUTED, 0);
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> closePanel());
        head.addView(close);
        card.addView(head);

        // currency pills
        curRow = row();
        curRow.setPadding(0, dp(12), 0, dp(4));
        for (String[] c : Rates.CURRENCIES) {
            curRow.addView(curPill(c));
        }
        card.addView(wrapH(curRow));

        // amount input
        amountIn = new EditText(this);
        amountIn.setHint("금액 입력 (예: 100.000)");
        amountIn.setHintTextColor(Theme.MUTED);
        amountIn.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amountIn.setTextColor(Theme.FOREGROUND);
        amountIn.setTextSize(17);
        amountIn.setSingleLine(true);
        amountIn.setBackground(pill(Theme.SURFACE, Theme.HAIRLINE, Theme.R_CONTROL));
        amountIn.setPadding(dp(14), dp(12), dp(14), dp(12));
        amountIn.addTextChangedListener(watcher());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(8);
        card.addView(amountIn, alp);

        // tax label + pills + custom
        TextView taxLbl = mkText("세금 / 가산세 (%)", 12, Theme.MUTED, 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(14);
        card.addView(taxLbl, tlp);
        taxRow = row();
        taxRow.setPadding(0, dp(6), 0, 0);
        for (int preset = 0; preset <= 25; preset += 5) taxRow.addView(taxPill(preset));
        taxIn = new EditText(this);
        taxIn.setHint("직접");
        taxIn.setHintTextColor(Theme.MUTED);
        taxIn.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        taxIn.setTextColor(Theme.FOREGROUND);
        taxIn.setTextSize(13);
        taxIn.setSingleLine(true);
        taxIn.setEms(4);
        taxIn.setHint(" tax%");
        taxIn.setBackground(pill(Theme.SURFACE, Theme.HAIRLINE, Theme.R_PILL));
        taxIn.setPadding(dp(12), 0, dp(12), 0);
        taxIn.addTextChangedListener(watcher());
        LinearLayout taxRow2 = row();
        taxRow2.setPadding(0, dp(6), 0, 0);
        taxRow2.addView(taxIn, new LinearLayout.LayoutParams(dp(84), dp(Theme.H_COMPACT)));
        card.addView(wrapH(taxRow));
        card.addView(taxRow2);

        // result card
        LinearLayout res = new LinearLayout(this);
        res.setOrientation(LinearLayout.VERTICAL);
        res.setBackground(pill(Theme.SURFACE, 0, Theme.R_CARD));
        res.setPadding(dp(Theme.S_CONTENT), dp(16), dp(Theme.S_CONTENT), dp(16));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(14);
        resultBig = mkText("₩0", 32, Theme.FOREGROUND, android.graphics.Typeface.BOLD);
        resultCap = mkText("", 12, Theme.MUTED, 0);
        res.addView(resultBig);
        res.addView(resultCap);
        card.addView(res, rlp);

        // rate row
        rateInfo = mkText("", 12, Theme.MUTED, 0);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = dp(14);
        card.addView(rateInfo, ilp);

        LinearLayout rrow = row();
        rrow.setPadding(0, dp(8), 0, 0);
        rrow.addView(compactBtn("↻ 자동 갱신", v -> {
            Rates.refresh(this, () -> { recalc(); updateRateInfo(); });
            toast("환율 갱신 중…");
        }));
        rrow.addView(compactBtn("수동 해제", v -> {
            Rates.clearManual(this, cur); updateRateInfo(); recalc();
        }));
        manualIn = new EditText(this);
        manualIn.setHint("1 " + cur + " = ₩");
        manualIn.setHintTextColor(Theme.MUTED);
        manualIn.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        manualIn.setTextColor(Theme.FOREGROUND);
        manualIn.setTextSize(12);
        manualIn.setSingleLine(true);
        manualIn.setBackground(pill(Theme.SURFACE, Theme.HAIRLINE, Theme.R_PILL));
        manualIn.setPadding(dp(12), 0, dp(12), 0);
        rrow.addView(fixed(manualIn, dp(130), dp(Theme.H_COMPACT), dp(8)));
        rrow.addView(compactBtn("저장", v -> {
            double v2 = parseNum(manualIn.getText().toString());
            if (v2 > 0) { Rates.setManualRate(this, cur, v2); updateRateInfo(); recalc(); }
        }));
        card.addView(rrow);

        CheckBox autoCb = new CheckBox(this);
        autoCb.setText("자동 환율 (6시간)");
        autoCb.setTextColor(Theme.MUTED);
        autoCb.setTextSize(12);
        autoCb.setChecked(Rates.autoOn(this));
        autoCb.setOnCheckedChangeListener((v, on) -> Rates.setAuto(this, on));
        card.addView(autoCb);

        panel = sv;
    }

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
        return hsv;
    }
    private TextView curPill(String[] c) {
        boolean sel = c[0].equals(cur);
        TextView t = mkText(c[1] + " " + c[2], 13,
                sel ? Theme.ON_PRIMARY : Theme.LINK, sel ? android.graphics.Typeface.BOLD : 0);
        t.setBackground(pill(sel ? Theme.PRIMARY : Color.TRANSPARENT, sel ? 0 : Theme.LINK, Theme.R_PILL));
        t.setPadding(dp(Theme.S_CONTROL), dp(8), dp(Theme.S_CONTROL), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            cur = c[0];
            Rates.setCur(this, cur);
            rebuildCurRow();
            manualIn.setHint("1 " + cur + " = ₩");
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
        TextView t = mkText(pct + "%", 13, sel ? Theme.ON_PRIMARY : Theme.LINK,
                sel ? android.graphics.Typeface.BOLD : 0);
        t.setTag(pct);
        t.setBackground(pill(sel ? Theme.PRIMARY : Color.TRANSPARENT, sel ? 0 : Theme.LINK, Theme.R_PILL));
        t.setPadding(dp(13), dp(7), dp(13), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            tax = pct;
            taxIn.setText("");
            rebuildTaxRow();
            recalc();
        });
        return t;
    }
    private void rebuildTaxRow() {
        taxRow.removeAllViews();
        for (int preset = 0; preset <= 25; preset += 5) taxRow.addView(taxPill(preset));
    }
    private TextView compactBtn(String s, View.OnClickListener l) {
        TextView t = mkText(s, 12, Theme.ON_PRIMARY, android.graphics.Typeface.BOLD);
        t.setBackground(pill(Theme.PRIMARY, 0, Theme.R_PILL));
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
            rebuildTaxRow(); // deselect presets when custom typed
        }
        recalc();
    }

    private void recalc() {
        double amt = parseNum(amountIn != null ? amountIn.getText().toString() : "");
        double rate = Rates.rate(this, cur);
        double base = amt * rate;
        long total = Math.round(base * (1 + tax / 100.0));
        lastTotal = amt > 0 ? total : -1;
        if (resultBig != null) {
            resultBig.setText("₩" + nf.format(total));
            String sym = symOf(cur), name = nameOf(cur);
            String r = fmtRate(rate);
            resultCap.setText(amt > 0
                    ? nf.format(amt) + " " + sym + " " + name + " × " + r + (tax > 0 ? "  ·  +" + trim(tax) + "%" : "")
                    : sym + " " + name + " · 환율 " + r);
        }
        updateBubble();
    }

    private String symOf(String c) { for (String[] r : Rates.CURRENCIES) if (r[0].equals(c)) return r[1]; return c; }
    private String nameOf(String c) { for (String[] r : Rates.CURRENCIES) if (r[0].equals(c)) return r[2]; return c; }

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
        String when = at > 0 ? new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(at)) : "예비값";
        rateInfo.setText((Rates.isManual(this, cur) ? "수동 지정 · " : "자동 · ")
                + "1 " + cur + " = ₩" + fmtRate(r) + " (" + when + ")");
    }
}
