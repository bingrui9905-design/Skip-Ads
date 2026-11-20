package com.lbr.skipad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 设置页：上面一块黄色显示状态和数字，下面是开关列表。 */
public class MainActivity extends Activity {
    static final int YELLOW = 0xFFFFD60A;
    static final int INK = 0xFF000000;
    static final int GREY = 0xFF8E8E93;
    static final int LINE = 0xFFE5E5EA;
    static final int PAUSED = 0xFFE5E5EA;

    private SharedPreferences sp;
    private LinearLayout page;
    private TextView countView, stateView;
    private View header;
    private boolean running;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.sp(this);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);

        buildHeader();

        for (String[] r : Prefs.RULES) {
            final String[] rule = r;
            View row = switchRow(r[1], Prefs.enabled(this, r[0]),
                    on -> sp.edit().putBoolean("on_" + rule[0], on).apply());
            row.setOnClickListener(v -> editKeywords(rule));
            page.addView(row);
            page.addView(line());
        }
        gap();
        page.addView(switchRow("试运行", Prefs.dryRun(this), on -> {
            sp.edit().putBoolean("dry_run", on).apply();
            refresh();
        }));
        page.addView(line());
        gap();
        page.addView(linkRow("后台保活", this::keepAlive));
        page.addView(line());
        page.addView(linkRow("漏划记录", this::missed));
        page.addView(line());

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.setFillViewport(true);
        sv.addView(page, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // 黄色头部：名字、大数字、状态。没开启时整块可点，直接去开。
    private void buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), new ColorDrawable(YELLOW), null));
        h.setPadding(dp(24), dp(20), dp(24), dp(28));

        TextView name = text("抖音划走", 17, INK);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        h.addView(name);

        countView = text("0", 96, INK);
        countView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        countView.setIncludeFontPadding(false);
        countView.setLetterSpacing(-0.04f);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = dp(48);
        h.addView(countView, cl);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView unit = text("条已划走", 17, INK);
        bottom.addView(unit, new LinearLayout.LayoutParams(0, -2, 1));
        stateView = text("", 15, INK);
        stateView.setTypeface(Typeface.DEFAULT_BOLD);
        stateView.setPadding(dp(14), dp(7), dp(14), dp(7));
        bottom.addView(stateView);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(6);
        h.addView(bottom, bl);

        h.setOnClickListener(v -> {
            if (!running) showSetup();
        });
        // 开启后，点状态胶囊就是暂停/继续
        stateView.setOnClickListener(v -> {
            if (!running) { showSetup(); return; }
            Prefs.setPaused(this, !Prefs.paused(this));
            refresh();
        });
        header = h;
        page.addView(h);
    }

    private void refresh() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        running = on != null && on.contains(getPackageName() + "/");
        countView.setText(String.valueOf(Prefs.total(this)));
        countView.setTextColor(Prefs.paused(this) && running ? GREY : INK);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(dp(100));
        boolean paused = Prefs.paused(this);
        int headColor = (running && paused) ? PAUSED : YELLOW;
        header.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), new ColorDrawable(headColor), null));
        getWindow().setStatusBarColor(headColor);
        if (!running) {
            stateView.setText("点这里开启");
            pill.setColor(INK);
            stateView.setTextColor(YELLOW);
        } else {
            stateView.setText(paused ? "暂停了" : (Prefs.dryRun(this) ? "试运行" : "开着"));
            pill.setColor(0x00000000);
            pill.setStroke(dp(1.5f), INK);
            stateView.setTextColor(INK);
        }
        stateView.setBackground(pill);
        header.setClickable(!running);
        if (running && setupDialog != null && setupDialog.isShowing()) setupDialog.dismiss();
    }

    // ---------- 开启引导：先允许获取权限，再打开无障碍 ----------
    private AlertDialog setupDialog;

    private void showSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));
        box.addView(stepRow("1", "允许获取权限", this::openPermission));
        box.addView(line());
        box.addView(stepRow("2", "打开无障碍", this::openAccessibility));
        setupDialog = new AlertDialog.Builder(this)
                .setTitle("开启需要两步")
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
        tint(setupDialog);
    }

    private View stepRow(String num, String title, Runnable click) {
        LinearLayout row = row();
        TextView n = text(num, 15, INK);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable c = new android.graphics.drawable.GradientDrawable();
        c.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        c.setColor(YELLOW);
        n.setBackground(c);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(28), dp(28));
        nl.rightMargin = dp(14);
        row.addView(n, nl);
        row.addView(text(title, 17, INK), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text("›", 24, 0xFFC7C7CC));
        row.setOnClickListener(v -> click.run());
        return row;
    }

    /** 第 1 步：小米的“权限管理”页，右上角 ⋮ 里有“允许应用获取权限”。打不开就退回应用信息页。 */
    private void openPermission() {
        Intent miui = new Intent("miui.intent.action.APP_PERM_EDITOR");
        miui.setClassName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity");
        miui.putExtra("extra_pkgname", getPackageName());
        if (tryStart(miui)) return;
        Intent miui2 = new Intent("miui.intent.action.APP_PERM_EDITOR");
        miui2.setPackage("com.miui.securitycenter");
        miui2.putExtra("extra_pkgname", getPackageName());
        if (tryStart(miui2)) return;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        tryStart(i);
    }

    /** 第 2 步：直接跳到「抖音划走」自己的无障碍开关；不支持的系统退回列表并高亮这一项。 */
    private void openAccessibility() {
        String comp = new android.content.ComponentName(this, SkipService.class).flattenToString();
        Intent d = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        d.putExtra(Intent.EXTRA_COMPONENT_NAME, comp);
        if (tryStart(d)) return;
        Intent l = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", comp);
        l.putExtra(":settings:fragment_args_key", comp);
        l.putExtra(":settings:show_fragment_args", args);
        tryStart(l);
    }

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 弹窗 ----------
    private void editKeywords(String[] r) {
        final String key = r[0];
        EditText et = new EditText(this);
        et.setText(Prefs.keywords(this, key));
        et.setTextSize(16);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setBackgroundTintList(ColorStateList.valueOf(INK));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        tint(new AlertDialog.Builder(this)
                .setTitle(r[1] + "关键词")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> sp.edit().putString("kw_" + key, et.getText().toString().trim()).apply())
                .setNeutralButton("恢复默认", (d, w) -> sp.edit().remove("kw_" + key).apply())
                .setNegativeButton("取消", null)
                .show());
    }

    private void keepAlive() {
        tint(new AlertDialog.Builder(this)
                .setTitle("后台保活")
                .setMessage("1. 省电策略改成“无限制”\n2. 打开自启动\n3. 在最近任务里把它锁住")
                .setPositiveButton("去设置", (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                })
                .setNegativeButton("关闭", null)
                .show());
    }

    private void missed() {
        int n = sp.getInt("missed_n", 0);
        boolean dbg = Prefs.debug(this);
        tint(new AlertDialog.Builder(this)
                .setTitle("漏划记录")
                .setMessage("记了 " + n + " 条。刷到漏掉的，在通知栏点“漏了，记一下”。")
                .setPositiveButton("分享", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, SkipService.lastDump);
                    startActivity(Intent.createChooser(i, "分享"));
                })
                .setNeutralButton(dbg ? "关闭调试" : "开启调试",
                        (d, w) -> sp.edit().putBoolean("debug", !dbg).apply())
                .setNegativeButton("关闭", null)
                .show());
    }

    private void tint(AlertDialog d) {
        int[] ids = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int id : ids) if (d.getButton(id) != null) d.getButton(id).setTextColor(INK);
    }

    // ---------- 行 ----------
    interface OnToggle { void on(boolean v); }

    private View switchRow(String title, boolean checked, OnToggle cb) {
        LinearLayout row = row();
        row.addView(text(title, 17, INK), new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{YELLOW, Color.WHITE}));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{INK, 0xFFD1D1D6}));
        sw.setTrackTintMode(PorterDuff.Mode.SRC);
        sw.setOnCheckedChangeListener((v, isOn) -> cb.on(isOn));
        row.addView(sw);
        return row;
    }

    private View linkRow(String title, Runnable click) {
        LinearLayout row = row();
        row.addView(text(title, 17, INK), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text("›", 24, 0xFFC7C7CC));
        row.setOnClickListener(v -> click.run());
        return row;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(58));
        r.setPadding(dp(24), 0, dp(20), 0);
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x14000000), null, new ColorDrawable(Color.WHITE)));
        return r;
    }

    private View line() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.leftMargin = dp(24);
        v.setLayoutParams(lp);
        return v;
    }

    private void gap() {
        page.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(28)));
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
