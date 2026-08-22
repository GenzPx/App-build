package com.monitorcheck.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.monitorcheck.MainActivity
import com.monitorcheck.R
import java.text.DateFormat
import java.util.Date

class MonitorWidgetProvider : AppWidgetProvider() {
    data class WidgetSnapshot(val cpu:String="Unavailable",val ram:String="Unavailable",val battery:String="Unavailable",val temperature:String="Unavailable",val network:String="Unavailable",val updatedAt:Long=0L)
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){ ids.forEach { updateWidget(context,manager,it,null) }; if(ids.isNotEmpty()) WidgetRefreshScheduler.schedule(context) }
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:Bundle){ updateWidget(context,manager,id,options) }
    override fun onEnabled(context:Context){ WidgetRefreshScheduler.schedule(context) }
    override fun onDisabled(context:Context){ WidgetRefreshScheduler.cancel(context) }
    companion object {
        private const val PREFS="monitor_widget_snapshot"; private const val CPU="cpu"; private const val RAM="ram"; private const val BATTERY="battery"; private const val TEMP="temperature"; private const val NETWORK="network"; private const val UPDATED="updated"
        fun updateAll(context:Context,snapshot:WidgetSnapshot){ context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(CPU,snapshot.cpu).putString(RAM,snapshot.ram).putString(BATTERY,snapshot.battery).putString(TEMP,snapshot.temperature).putString(NETWORK,snapshot.network).putLong(UPDATED,snapshot.updatedAt).apply(); val m=AppWidgetManager.getInstance(context); val c=ComponentName(context,MonitorWidgetProvider::class.java); m.getAppWidgetIds(c).forEach { updateWidget(context,m,it,null) } }

        fun peek(context:Context):WidgetSnapshot = snap(context)
        private fun snap(context:Context):WidgetSnapshot { val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE); return WidgetSnapshot(p.getString(CPU,"Unavailable")!!,p.getString(RAM,"Unavailable")!!,p.getString(BATTERY,"Unavailable")!!,p.getString(TEMP,"Unavailable")!!,p.getString(NETWORK,"Unavailable")!!,p.getLong(UPDATED,0L)) }

        private fun updateWidget(context:Context,manager:AppWidgetManager,id:Int,options:Bundle?){ val o=options?:manager.getAppWidgetOptions(id); val w=o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,110); val layout=when{w<180->R.layout.widget_small;w<280->R.layout.widget_medium;else->R.layout.widget_large}; val s=snap(context); val v=RemoteViews(context.packageName,layout); v.setTextViewText(R.id.widget_cpu,"CPU  ${s.cpu}");v.setTextViewText(R.id.widget_ram,"RAM  ${s.ram}");v.setTextViewText(R.id.widget_battery,"BAT  ${s.battery}");v.setTextViewText(R.id.widget_temperature,"TEMP  ${s.temperature}");v.setTextViewText(R.id.widget_network,s.network);v.setTextViewText(R.id.widget_updated,if(s.updatedAt>0)"Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.updatedAt))}" else "Monitoring not active"); val i=Intent(context,MainActivity::class.java).apply{putExtra(MainActivity.EXTRA_ROUTE,"dashboard");flags=Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP};v.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(context,id,i,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT));manager.updateAppWidget(id,v) }
    }
}
