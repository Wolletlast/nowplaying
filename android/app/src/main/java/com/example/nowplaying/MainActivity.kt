package com.example.nowplaying

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Главный экран: плеер в том же стиле, что и на ПК (вращающаяся пластинка, ползунок, кнопки)
 * плюс настройки связи с компьютером.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var userSeeking = false
    private var shownCover: Bitmap? = null

    private lateinit var vinyl: VinylView
    private lateinit var spin: ObjectAnimator
    private lateinit var tvApp: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvTotal: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPrev: IconButton
    private lateinit var btnPlay: IconButton
    private lateinit var btnNext: IconButton
    private lateinit var btnLike: IconButton
    private lateinit var tvStatus: TextView

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        // ---------- карточка плеера ----------
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3A2A55.toInt(), 0xFF14161C.toInt())
            ).apply { cornerRadius = dp(28).toFloat() }
        }

        vinyl = VinylView(this)
        card.addView(vinyl, LinearLayout.LayoutParams(dp(104), dp(104)).apply { marginEnd = dp(14) })
        spin = ObjectAnimator.ofFloat(vinyl, View.ROTATION, 0f, 360f).apply {
            duration = 5500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
            pause()
        }

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        tvApp = TextView(this).apply {
            textSize = 11f
            setTextColor(0xB3FFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tvTitle = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x4DFFFFFF)
            setPadding(dp(8), 0, dp(8), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) tvTime.text = fmt(p.toLong())
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    userSeeking = false
                    controller?.let { MediaUtil.execute(it, "seek", sb.progress.toLong()) }
                }
            })
        }
        tvTime = TextView(this).apply { textSize = 10f; setTextColor(0xB3FFFFFF.toInt()); text = "00:00" }
        tvTotal = TextView(this).apply { textSize = 10f; setTextColor(0xB3FFFFFF.toInt()); text = "--:--" }
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(tvTime, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(tvTotal)
        }

        btnPrev = IconButton(this).apply { kind = IconButton.Kind.PREV }
        btnPlay = IconButton(this).apply { kind = IconButton.Kind.PLAY }
        btnNext = IconButton(this).apply { kind = IconButton.Kind.NEXT }
        btnLike = IconButton(this).apply { kind = IconButton.Kind.HEART }
        btnPrev.setOnClickListener { controller?.let { MediaUtil.execute(it, "prev") } }
        btnPlay.setOnClickListener { controller?.let { MediaUtil.execute(it, "toggle") } }
        btnNext.setOnClickListener { controller?.let { MediaUtil.execute(it, "next") } }
        btnLike.setOnClickListener { controller?.let { MediaUtil.execute(it, "like") } }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for (b in listOf(btnPrev, btnPlay, btnNext, btnLike)) {
                val size = if (b === btnPlay) dp(44) else dp(38)
                addView(b, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6); marginEnd = dp(6) })
            }
        }

        right.addView(tvApp)
        right.addView(tvTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        right.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        right.addView(times)
        right.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(card, LinearLayout.LayoutParams(-1, -2))

        // ---------- настройки связи ----------
        val info = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB8BDC8.toInt())
            text = "Запустите NowPlaying на ПК: телефон найдёт его сам в той же Wi-Fi сети. " +
                "Выдайте доступ к уведомлениям и нажмите «Проверить связь». " +
                "IP вводить нужно только если автопоиск не сработал."
            setPadding(0, dp(24), 0, dp(8))
        }
        val host = EditText(this).apply {
            hint = "IP ПК (необязательно, ищется автоматически)"
            setHintTextColor(0xFF6C7280.toInt())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Sender.host(this@MainActivity))
            setSingleLine()
        }
        val save = Button(this).apply {
            text = "Сохранить"
            setOnClickListener {
                Sender.saveHost(this@MainActivity, host.text.toString())
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }
        val access = Button(this).apply {
            text = "Доступ к уведомлениям"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        val battery = Button(this).apply {
            text = "Не засыпать (батарея)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        val test = Button(this).apply {
            text = "Проверить связь"
            setOnClickListener {
                Sender.saveHost(this@MainActivity, host.text.toString())
                val json = JSONObject()
                    .put("title", "Связь работает")
                    .put("artist", "NowPlaying")
                    .put("app", "Тест")
                    .put("playing", false)
                    .put("position", 0)
                    .put("duration", 0)
                Sender.post(this@MainActivity, json) { resp ->
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            if (resp != null) "Отправлено, смотрите карточку на ПК"
                            else "Нет связи с ПК: он должен быть запущен, в той же Wi-Fi сети, порты 8765 и 8766 открыты в брандмауэре. Можно ввести IP вручную.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        tvStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB8BDC8.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        for (v in listOf(info, host, save, access, battery, test, tvStatus)) root.addView(v)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0E0F13.toInt())
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun fmt(ms: Long): String {
        if (ms < 0) return "--:--"
        val s = ms / 1000
        val h = s / 3600
        val m = s % 3600 / 60
        val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%02d:%02d".format(m, x)
    }

    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun refresh() {
        val list: List<MediaController>? = try {
            (getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(this, MediaListenerService::class.java))
        } catch (_: SecurityException) {
            null
        }

        val c = if (list != null) MediaUtil.pick(list) else null
        controller = c
        val md = c?.metadata
        val st = c?.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()

        if (c == null || md == null || title.isBlank()) {
            tvApp.text = ""
            tvTitle.text = if (list == null) "Нужен доступ к уведомлениям" else "Ничего не играет"
            tvTotal.text = "--:--"
            if (!userSeeking) {
                tvTime.text = "00:00"
                seek.progress = 0
            }
            seek.max = 1
            btnPlay.kind = IconButton.Kind.PLAY
            btnLike.kind = IconButton.Kind.HEART
            for (b in listOf(btnPrev, btnPlay, btnNext, btnLike)) b.isEnabled = false
            vinyl.cover = null
            shownCover = null
            if (!spin.isPaused) spin.pause()
        } else {
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val playing = st?.state == PlaybackState.STATE_PLAYING
            val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val pos = MediaUtil.position(st)
            val like = MediaUtil.likeAction(st)

            tvApp.text = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
            } catch (_: Exception) {
                c.packageName
            }
            tvTitle.text = if (artist.isEmpty()) title else "$title • $artist"
            tvTotal.text = if (duration > 0) fmt(duration) else "--:--"
            seek.max = if (duration > 0) duration.toInt() else 1
            if (!userSeeking) {
                seek.progress = pos.coerceAtMost(seek.max.toLong()).toInt()
                tvTime.text = fmt(pos)
            }
            seek.isEnabled = st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L && duration > 0

            btnPlay.kind = if (playing) IconButton.Kind.PAUSE else IconButton.Kind.PLAY
            btnLike.kind = if (MediaUtil.isLiked(like)) IconButton.Kind.HEART_FILLED else IconButton.Kind.HEART
            for (b in listOf(btnPrev, btnPlay, btnNext)) b.isEnabled = true
            btnLike.isEnabled = like != null

            val cover = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (cover !== shownCover) {
                shownCover = cover
                vinyl.cover = cover
            }
            if (playing) {
                if (spin.isPaused) spin.resume()
            } else if (!spin.isPaused) {
                spin.pause()
            }
        }

        val target = Sender.target(this)
        val ago = System.currentTimeMillis() - Sender.lastOk
        tvStatus.text = buildString {
            append(if (notificationAccessGranted()) "Доступ к уведомлениям: есть" else "Доступ к уведомлениям: НЕТ")
            append("\n")
            append(
                when {
                    target.isEmpty() -> "Связь с ПК: ищу компьютер в Wi-Fi сети..."
                    Sender.lastOk > 0 && ago < 12000 -> "Связь с ПК: подключено ($target)"
                    else -> "Связь с ПК: нет ответа"
                }
            )
        }
    }
}
