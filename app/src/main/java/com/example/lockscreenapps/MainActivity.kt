package com.example.lockscreenapps

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

const val MAX_APPS = 12

private val SLOTS = intArrayOf(
    R.id.app0, R.id.app1, R.id.app2, R.id.app3, R.id.app4, R.id.app5,
    R.id.app6, R.id.app7, R.id.app8, R.id.app9, R.id.app10, R.id.app11
)

object Prefs {
    fun load(ctx: Context): List<String> =
        ctx.getSharedPreferences("quick_apps", Context.MODE_PRIVATE)
            .getString("apps", "").orEmpty().split(",").filter { it.isNotBlank() }

    fun save(ctx: Context, apps: List<String>) {
        ctx.getSharedPreferences("quick_apps", Context.MODE_PRIVATE)
            .edit().putString("apps", apps.joinToString(",")).apply()
    }
}

class MainActivity : Activity() {

    private class Row(val pkg: String, val box: CheckBox)
    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
        }

        root.addView(TextView(this).apply {
            text = "Choose up to $MAX_APPS apps for your lock screen"
            textSize = 16f
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val saved = Prefs.load(this).toSet()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        for (ri in apps) {
            val pkg = ri.activityInfo.packageName
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(4), 0, px(4))
            }

            val icon = ImageView(this).apply { setImageDrawable(ri.loadIcon(packageManager)) }
            row.addView(icon, LinearLayout.LayoutParams(px(40), px(40)))

            val box = CheckBox(this).apply {
                text = ri.loadLabel(packageManager)
                isChecked = pkg in saved
                setPadding(px(12), 0, 0, 0)
            }

            box.setOnCheckedChangeListener { _, checked ->
                if (checked && rows.count { it.box.isChecked } > MAX_APPS) {
                    box.isChecked = false
                    Toast.makeText(this, "Maximum is $MAX_APPS apps", Toast.LENGTH_SHORT).show()
                }
            }

            row.addView(box)
            list.addView(row)
            rows.add(Row(pkg, box))
        }

        val save = Button(this).apply {
            text = "Save and show on lock screen"
            setOnClickListener {
                Prefs.save(this@MainActivity, rows.filter { it.box.isChecked }.map { it.pkg })
                ContextCompat.startForegroundService(
                    this@MainActivity, Intent(this@MainActivity, LockScreenService::class.java)
                )
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        }

        val stop = Button(this).apply {
            text = "Turn off"
            setOnClickListener {
                stopService(Intent(this@MainActivity, LockScreenService::class.java))
                Toast.makeText(this@MainActivity, "Turned off", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(save)
        root.addView(stop)
        setContentView(root)
    }
}

class LockScreenService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "lock_screen_apps"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Quick Apps", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        val entries = Prefs.load(this)
            .mapNotNull { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { pkg to it } }
            .take(MAX_APPS)

        val small = RemoteViews(packageName, R.layout.notif_collapsed)
        val big = RemoteViews(packageName, R.layout.notif_expanded)

        for (i in 0 until MAX_APPS) {
            val id = SLOTS[i]
            if (i < entries.size) {
                val (pkg, launch) = entries[i]
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(
                    this, i, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val bmp = packageManager.getApplicationIcon(pkg).toBitmap(96, 96)
                big.setImageViewBitmap(id, bmp)
                big.setOnClickPendingIntent(id, pi)
                if (i < 6) {
                    small.setImageViewBitmap(id, bmp)
                    small.setOnClickPendingIntent(id, pi)
                }
            } else {
                big.setViewVisibility(id, View.INVISIBLE)
                if (i < 6) small.setViewVisibility(id, View.INVISIBLE)
            }
        }
        if (entries.size <= 6) big.setViewVisibility(R.id.row2, View.GONE)

        val settingsPi = PendingIntent.getActivity(
            this, 100, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Quick Apps")
            .setContentText(if (entries.isEmpty()) "Tap to choose your apps" else "Tap here to edit apps")
            .setContentIntent(settingsPi)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (entries.isNotEmpty()) {
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
        }

        startForeground(1, builder.build())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
