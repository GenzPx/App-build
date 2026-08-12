package com.monitorcheck.monitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.monitorcheck.MainActivity
import com.monitorcheck.R
import com.monitorcheck.core.Fmt
import com.monitorcheck.core.SettingsRepository
import com.monitorcheck.hardware.cpu.CpuRepository
import com.monitorcheck.hardware.memory.MemoryRepository
import com.monitorcheck.hardware.thermal.ThermalRepository
import com.monitorcheck.network.NetworkRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
class OverlayMonitorService:Service(){private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private var job:Job?=null;private lateinit var wm:WindowManager;private var view:TextView?=null;private lateinit var params:WindowManager.LayoutParams;private lateinit var settings:SettingsRepository;private lateinit var cpu:CpuRepository;private lateinit var mem:MemoryRepository;private lateinit var thermal:ThermalRepository;private lateinit var net:NetworkRepository
 override fun onCreate(){super.onCreate();wm=getSystemService(WINDOW_SERVICE)as WindowManager;settings=SettingsRepository(applicationContext);cpu=CpuRepository();mem=MemoryRepository(applicationContext);thermal=ThermalRepository(applicationContext);net=NetworkRepository(applicationContext);channel();startForegroundCompat(notification());addOverlay();job=scope.launch{loop()}}
 override fun onStartCommand(i:Intent?,f:Int,s:Int):Int{if(i?.action==ACTION_STOP)stopSelf();return START_STICKY}
 private suspend fun loop(){while(scope.isActive){val s=runCatching{settings.settings.first()}.getOrNull();val c=runCatching{cpu.sampleUsage().totalPercent.value}.getOrNull();val r=runCatching{mem.snapshot().value?.usedPercent}.getOrNull();val t=runCatching{thermal.hottestZone().value?.celsius}.getOrNull();val n=runCatching{net.sampleThroughput()}.getOrNull();view?.post{view?.text="MONITORED CHECK\nCPU   ${c?.let{Fmt.percent(it,0)}?:"Unavailable"}\nRAM   ${r?.let{Fmt.percent(it,0)}?:"Unavailable"}\nTEMP  ${t?.let{Fmt.temperature(it)}?:"Unavailable"}"+(if(n?.elapsedMs?:0>0)"\nNET   ↓${Fmt.bytesPerSecond(n!!.rxRateBps)} ↑${Fmt.bytesPerSecond(n.txRateBps)}" else "")};delay(maxOf(1000L,s?.refreshIntervalMs?:2000L))}}
 private fun addOverlay(){val d=resources.displayMetrics.density;params=WindowManager.LayoutParams((174*d).toInt(),WindowManager.LayoutParams.WRAP_CONTENT,if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=(12*d).toInt();y=(96*d).toInt()};view=TextView(this).apply{setTextColor(Color.WHITE);setTextSize(11f);setPadding((10*d).toInt(),(8*d).toInt(),(10*d).toInt(),(8*d).toInt());setBackgroundColor(Color.argb(220,20,31,48));setOnTouchListener(DragListener())};runCatching{wm.addView(view,params)}.onFailure{stopSelf()}}
 private inner class DragListener:View.OnTouchListener{var dx=0f;var dy=0f;var sx=0;var sy=0;override fun onTouch(v:View,e:MotionEvent):Boolean{when(e.action){MotionEvent.ACTION_DOWN->{dx=e.rawX;dy=e.rawY;sx=params.x;sy=params.y};MotionEvent.ACTION_MOVE->{params.x=sx+(e.rawX-dx).toInt();params.y=sy+(e.rawY-dy).toInt();view?.let{runCatching{wm.updateViewLayout(it,params)}}}};return true}}
 private fun notification()=NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle("Floating monitor active").setContentText("CPU, RAM and temperature HUD is visible").setOngoing(true).setSilent(true).addAction(0,"Stop HUD",PendingIntent.getService(this,1,Intent(this,OverlayMonitorService::class.java).apply{action=ACTION_STOP},PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
 private fun startForegroundCompat(n:Notification){try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.UPSIDE_DOWN_CAKE)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(NOTIFICATION_ID,n)}catch(_:Throwable){stopSelf()}}
 private fun channel(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)(getSystemService(Context.NOTIFICATION_SERVICE)as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID,"Floating monitor",NotificationManager.IMPORTANCE_LOW))}
 override fun onDestroy(){job?.cancel();scope.cancel();view?.let{runCatching{wm.removeView(it)}};runCatching{stopForeground(true)};super.onDestroy()};override fun onBind(i:Intent?):IBinder?=null
 companion object{const val CHANNEL_ID="monitored_check_overlay";const val NOTIFICATION_ID=1002;const val ACTION_STOP="com.monitorcheck.action.STOP_OVERLAY";fun start(c:Context){runCatching{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)c.startForegroundService(Intent(c,OverlayMonitorService::class.java))else c.startService(Intent(c,OverlayMonitorService::class.java))}};fun stop(c:Context){runCatching{c.stopService(Intent(c,OverlayMonitorService::class.java))}}}
}
