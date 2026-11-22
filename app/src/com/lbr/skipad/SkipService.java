package com.lbr.skipad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 识别与划走：
 * - 先按关键词让系统直接查找节点；没命中再完整遍历界面兜底（限频）
 * - 节流：抖音界面一变就检查，最多每 150ms 一次；不用防抖，否则视频播放时会被无限推迟
 * - 常驻通知：前台服务，防止系统冻结/杀掉
 */
public class SkipService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    static final String DOUYIN = "com.ss.android.ugc.aweme";
    static final long MIN_INTERVAL_MS = 150;  // 两次扫描最小间隔
    static final long COOLDOWN_MS = 800;      // 划走后冷却，等新视频加载
    static final int MAX_LABEL_LEN = 8;       // 只认短标签，避免把文案里的“广告”当标签
    static final float TOP_EXCLUDE = 0.12f;   // 屏幕顶部 tab 栏不参与识别
    static final String CH = "skip_fg";
    static final long WALK_INTERVAL_MS = 400; // 兜底遍历最多每 0.4s 一次
    private long lastWalkAt = 0;

    static volatile String lastDump = "（调试未开启或还没有数据）";

    private final Handler h = new Handler(Looper.getMainLooper());
    private int screenW, screenH;
    private long lastScanAt = 0, lastSwipeAt = 0;
    private boolean pending = false;
    private long lastCost = 0, totalSkipped = 0;

    // 缓存的规则：[key, kw1, kw2, ...]
    private final List<String[]> rules = new ArrayList<>();
    private boolean dryRun, debug, paused;

    private final Runnable scan = () -> { pending = false; scanNow(); };

    @Override
    protected void onServiceConnected() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        loadRules();
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(this);
        for (String[] r : Prefs.RULES) totalSkipped += Prefs.count(this, r[0]);
        IntentFilter f = new IntentFilter(ACTION_MISS);
        f.addAction(ACTION_TOGGLE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(missReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(missReceiver, f);
        goForeground();
        Toast.makeText(this, "抖音划走已开启", Toast.LENGTH_SHORT).show();
    }

    static final String ACTION_MISS = "com.lbr.skipad.MISS";
    static final String ACTION_TOGGLE = "com.lbr.skipad.TOGGLE";

    /** 通知栏“漏了，记下这条”：抓取抖音当前界面全部文字，存起来给你看/发给我调规则 */
    private final BroadcastReceiver missReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ACTION_TOGGLE.equals(i.getAction())) {
                Prefs.setPaused(c, !Prefs.paused(c));
                return;
            }
            // 收起通知栏后再抓，避免抓到的是通知栏
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(15 /* GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE */);
            else performGlobalAction(GLOBAL_ACTION_BACK);
            h.postDelayed(() -> {
                AccessibilityNodeInfo root = douyinRoot();
                if (root == null) {
                    Toast.makeText(c, "没找到抖音画面，回到抖音再点一次", Toast.LENGTH_SHORT).show();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                walk(root, sb, 0);
                String head = "【漏检记录】" + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date()) + "\n";
                lastDump = head + sb;
                writeFile("missed.txt", lastDump + "\n------\n");
                int n = Prefs.sp(c).getInt("missed_n", 0) + 1;
                Prefs.sp(c).edit().putInt("missed_n", n).apply();
                Toast.makeText(c, "记下了", Toast.LENGTH_SHORT).show();
            }, 450);
        }
    };

    private AccessibilityNodeInfo douyinRoot() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null && r.getPackageName() != null && DOUYIN.contentEquals(r.getPackageName())) return r;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo wr = w.getRoot();
            if (wr != null && wr.getPackageName() != null && DOUYIN.contentEquals(wr.getPackageName())) return wr;
        }
        return null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key != null && key.startsWith("cnt_")) return;
        loadRules();
    }

    private void loadRules() {
        rules.clear();
        for (String[] r : Prefs.RULES) {
            if (!Prefs.enabled(this, r[0])) continue;
            List<String> kws = new ArrayList<>();
            kws.add(r[0]);
            for (String kw : Prefs.keywords(this, r[0]).split("[,，]")) {
                kw = kw.trim();
                if (!kw.isEmpty()) kws.add(kw);
            }
            rules.add(kws.toArray(new String[0]));
        }
        dryRun = Prefs.dryRun(this);
        debug = Prefs.debug(this);
        boolean was = paused;
        paused = Prefs.paused(this);
        if (was != paused) updateNotification();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !DOUYIN.contentEquals(pkg)) return;
        if (paused || pending) return;
        long now = SystemClock.uptimeMillis();
        long wait = Math.max(0, MIN_INTERVAL_MS - (now - lastScanAt));
        wait = Math.max(wait, COOLDOWN_MS - (now - lastSwipeAt));
        pending = true;
        h.postDelayed(scan, Math.max(wait, 30)); // 至少等 30ms，让这一帧界面落定
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(this);
        try { unregisterReceiver(missReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void scanNow() {
        long t0 = SystemClock.uptimeMillis();
        lastScanAt = t0;
        if (t0 - lastSwipeAt < COOLDOWN_MS) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !DOUYIN.contentEquals(root.getPackageName())) return;

        // 评论区/输入框打开时不动
        if (visibleMatch(root, "条评论", 20) != null || visibleMatch(root, "说点什么", 12) != null) return;

        String hitKey = null, hitWord = null;
        outer:
        for (String[] r : rules) {
            for (int i = 1; i < r.length; i++) {
                String m = visibleMatch(root, r[i], ruleMaxLen(r[0], r[i]));
                if (m != null) { hitKey = r[0]; hitWord = m; break outer; }
            }
        }
        boolean viaWalk = false;
        long tw = SystemClock.uptimeMillis();
        if (hitKey == null && tw - lastWalkAt > WALK_INTERVAL_MS) {
            lastWalkAt = tw;
            List<String> ts = new ArrayList<>();
            List<Rect> rs = new ArrayList<>();
            gather(root, ts, rs, 0);
            outer2:
            for (String[] r : rules) {
                for (int i = 1; i < r.length; i++) {
                    int ml = ruleMaxLen(r[0], r[i]);
                    for (int j = 0; j < ts.size(); j++) {
                        String m = accept(ts.get(j), rs.get(j), r[i], ml);
                        if (m != null) { hitKey = r[0]; hitWord = m; viaWalk = true; break outer2; }
                    }
                }
            }
        }
        lastCost = SystemClock.uptimeMillis() - t0;

        if (hitKey != null) {
            Prefs.inc(this, hitKey);
            totalSkipped++;
            if (dryRun) {
                lastSwipeAt = SystemClock.uptimeMillis(); // 试运行也冷却，避免同一条反复提示
                Toast.makeText(this, "会划走：" + hitWord + (debug ? " " + lastCost + "ms" + (viaWalk ? " 遍历" : "") : ""), Toast.LENGTH_SHORT).show();
            } else {
                swipeUp();
            }
            updateNotification();
        }

        if (debug) dump(root, hitKey, hitWord);
    }

    /** 快速路径：让系统按文字查找节点 */
    private String visibleMatch(AccessibilityNodeInfo root, String kw, int maxLen) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(kw);
        if (list == null) return null;
        Rect b = new Rect();
        for (AccessibilityNodeInfo n : list) {
            if (!n.isVisibleToUser()) continue;
            n.getBoundsInScreen(b);
            String m = accept(str(n.getText()), b, kw, maxLen);
            if (m == null) m = accept(str(n.getContentDescription()), b, kw, maxLen);
            if (m != null) return m;
        }
        return null;
    }

    private static String str(CharSequence c) { return c == null ? null : c.toString().trim(); }

    /** 判断一段文字是否算命中：位置在屏幕内且不在顶部 tab 栏，长度符合规则 */
    private String accept(String s, Rect b, String kw, int maxLen) {
        if (s == null || s.isEmpty()) return null;
        if (b.isEmpty() || b.top < screenH * TOP_EXCLUDE || b.top >= screenH
                || b.right <= 0 || b.left >= screenW) return null;
        if (!s.toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))) return null;
        if (maxLen > 0) return s.length() <= maxLen ? s : null;
        if (b.top < screenH * 0.4f) return null; // 整段匹配只看下半屏的标题区
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    private int ruleMaxLen(String key, String kw) {
        int m = Prefs.maxLen(key);
        return m == 0 ? 0 : Math.max(m, kw.length() + 2);
    }

    /** 兜底路径：完整遍历界面树。抖音部分控件是自绘的，按文字查找会漏，遍历才能读到 */
    private void gather(AccessibilityNodeInfo n, List<String> ts, List<Rect> rs, int depth) {
        if (n == null || depth > 40 || !n.isVisibleToUser()) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        String t = str(n.getText()), d = str(n.getContentDescription());
        if (t != null && !t.isEmpty()) { ts.add(t); rs.add(b); }
        if (d != null && !d.isEmpty() && !d.equals(t)) { ts.add(d); rs.add(b); }
        for (int i = 0; i < n.getChildCount(); i++) gather(n.getChild(i), ts, rs, depth + 1);
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) return t.toString().trim();
        CharSequence d = n.getContentDescription();
        return d != null ? d.toString().trim() : null;
    }

    private void swipeUp() {
        Path p = new Path();
        float x = screenW * 0.5f;
        p.moveTo(x, screenH * 0.72f);
        p.lineTo(x, screenH * 0.22f);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 150))
                .build();
        lastSwipeAt = SystemClock.uptimeMillis();
        dispatchGesture(g, null, null);
    }

    // ---------- 常驻通知（保活 + 显示统计） ----------
    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, "运行提示", NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(paused ? "暂停了" : (dryRun ? "试运行中" : "开着"))
                .setContentText("划走了 " + totalSkipped + " 条")
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, paused ? "继续" : "暂停",
                        PendingIntent.getBroadcast(this, 2, new Intent(ACTION_TOGGLE).setPackage(getPackageName()),
                                PendingIntent.FLAG_IMMUTABLE)).build())
                .addAction(new Notification.Action.Builder(null, "漏了，记一下",
                        PendingIntent.getBroadcast(this, 1, new Intent(ACTION_MISS).setPackage(getPackageName()),
                                PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
    }

    private void goForeground() {
        try { startForeground(1, buildNotification()); } catch (Exception ignored) {}
    }

    private void updateNotification() {
        try { getSystemService(NotificationManager.class).notify(1, buildNotification()); } catch (Exception ignored) {}
    }

    // ---------- 调试：完整遍历，只在调试开启时做 ----------
    private long lastDumpAt = 0;

    private void dump(AccessibilityNodeInfo root, String hitKey, String hitWord) {
        long now = SystemClock.uptimeMillis();
        if (hitKey == null && now - lastDumpAt < 1500) return; // 未命中时最多 1.5s 记一次
        lastDumpAt = now;
        StringBuilder sb = new StringBuilder();
        walk(root, sb, 0);
        String head = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date())
                + "  识别耗时 " + lastCost + "ms  命中: "
                + (hitKey == null ? "无" : hitKey + "「" + hitWord + "」") + "\n";
        lastDump = head + sb;
        writeFile("douyin_dump.txt", lastDump + "\n------\n");
    }

    private void writeFile(String name, String content) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, name);
            if (f.length() > 2_000_000) f.delete();
            try (FileWriter w = new FileWriter(f, true)) { w.write(content); }
        } catch (Exception ignored) {}
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40 || !n.isVisibleToUser()) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (b.bottom > 0 && b.top < screenH && !b.isEmpty()) {
            CharSequence t = n.getText(), d = n.getContentDescription();
            if ((t != null && t.length() > 0) || (d != null && d.length() > 0)) {
                sb.append("· ").append(t == null ? "" : t);
                if (d != null && d.length() > 0) sb.append("  [desc] ").append(d);
                String id = n.getViewIdResourceName();
                if (id != null) sb.append("  [id] ").append(id);
                sb.append("  ").append(b.toShortString()).append('\n');
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), sb, depth + 1);
    }
}
