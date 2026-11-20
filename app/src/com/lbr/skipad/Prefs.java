package com.lbr.skipad;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有开关和关键词都存在这里，界面和服务共用。 */
public class Prefs {
    static final String NAME = "skipad";

    // key, 名称, 默认关键词, 默认开关, 最长匹配长度(0=匹配整段标题), 说明
    static final String[][] RULES = {
        {"ad",    "广告",     "广告,赞助", "1", "8",  "视频上标着“广告”"},
        {"live",  "直播带货", "直播中,点击进入直播间,进入直播间,查看直播", "1", "30", "带直播间入口的视频"},
        {"shop",  "购物链接", "购物车,小黄车,商品橱窗,立即购买,去购买,去抢购,已售,券后", "1", "30", "挂着商品、小黄车"},
        {"tuan",  "团购",     "团购,限时秒杀,免预约,到店,门店", "1", "30", "团购、秒杀、到店优惠"},
        {"brand", "软广",     "种草,好物推荐,好物分享,同款链接,旗舰店,左下角", "1", "0", "标题里有这些词，可以加品牌名"},
    };

    static int maxLen(String key) {
        for (String[] r : RULES) if (r[0].equals(key)) return Integer.parseInt(r[4]);
        return 8;
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c, String key) {
        String def = "0";
        for (String[] r : RULES) if (r[0].equals(key)) def = r[3];
        return sp(c).getBoolean("on_" + key, def.equals("1"));
    }

    static String defaultKeywords(String key) {
        for (String[] r : RULES) if (r[0].equals(key)) return r[2];
        return "";
    }

    static int total(Context c) {
        int t = 0;
        for (String[] r : RULES) t += count(c, r[0]);
        return t;
    }

    static String keywords(Context c, String key) {
        String def = "";
        for (String[] r : RULES) if (r[0].equals(key)) def = r[2];
        return sp(c).getString("kw_" + key, def);
    }

    /** 暂停：服务还在，但什么都不做 */
    static boolean paused(Context c) { return sp(c).getBoolean("paused", false); }

    static void setPaused(Context c, boolean v) { sp(c).edit().putBoolean("paused", v).apply(); }

    /** 试运行：只提示不划走，第一次测试用 */
    static boolean dryRun(Context c) { return sp(c).getBoolean("dry_run", true); }

    /** 调试：把当前屏幕上读到的文字记下来 */
    static boolean debug(Context c) { return sp(c).getBoolean("debug", false); }

    static int count(Context c, String key) { return sp(c).getInt("cnt_" + key, 0); }

    static void inc(Context c, String key) {
        sp(c).edit().putInt("cnt_" + key, count(c, key) + 1).apply();
    }
}
