package com.monitorcheck.monitor

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.monitorcheck.R
class MonitorTileService:TileService(){private val p by lazy{getSharedPreferences("quick_settings_tile",MODE_PRIVATE)};override fun onStartListening(){super.onStartListening();refresh()};override fun onClick(){super.onClick();val active=!p.getBoolean("active",false);p.edit().putBoolean("active",active).apply();if(active)MonitoringService.start(this)else MonitoringService.stop(this);refresh()};private fun refresh(){qsTile?.let{val a=p.getBoolean("active",false);it.state=if(a)Tile.STATE_ACTIVE else Tile.STATE_INACTIVE;it.label=if(a)"Monitoring on" else "Start monitoring";it.icon=Icon.createWithResource(this,R.drawable.ic_notification);it.updateTile()}}}
