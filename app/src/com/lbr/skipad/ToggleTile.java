package com.lbr.skipad;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 下拉控制中心里的快捷开关：点一下暂停/继续 */
public class ToggleTile extends TileService {
    @Override
    public void onStartListening() { update(); }

    @Override
    public void onClick() {
        Prefs.setPaused(this, !Prefs.paused(this));
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = !Prefs.paused(this);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("抖音划走");
        if (android.os.Build.VERSION.SDK_INT >= 29) t.setSubtitle(on ? "开着" : "暂停了");
        t.updateTile();
    }
}
