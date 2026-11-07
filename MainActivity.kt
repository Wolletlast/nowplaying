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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.min

/**
 * Два экрана в одной activity:
 *  - плеер: большая пластинка с обложкой, название, ползунок, крупные кнопки;
 *  - настройки: три карточки (доступ к уведомлениям, работа в фоне, связь с ПК).
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var userSeeking = false
    private var shownCover: Bitmap? = null
    private var tintColor = DEFAULT_TINT

    // общие
    private lateinit var playerPage: View
    private lateinit var settingsPage: View

    // плеер
    private lateinit var chipDot: View
    private lateinit var chipText: TextView
    private lateinit var vinyl: VinylView
    private lateinit var spin: ObjectAnimator
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvApp: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvTotal: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPrev: IconButton
    private lateinit var btnPlay: IconButton
    private lateinit var btnNext: IconButton
    private lateinit var btnLike: IconButton
    private lateinit var btnSetup: TextView

    // настройки
    private lateinit var tvAccess: TextView
    private lateinit var tvConn: TextView
    private lateinit var etHost: EditText

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- построение интерфейса

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        playerPage = buildPlayer()
        settingsPage = buildSettings()
        settingsPage.visibility = View.GONE

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(playerPage, FrameLayout.LayoutParams(-1, -1))
            addView(settingsPage, FrameLayout.LayoutParams(-1, -1))
        })
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    /** Фон с эффектом нажатия (ripple) и скруглением. */
    private fun pressable(color: Int, radius: Int) =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(color, radius), rounded(Color.WHITE, radius))

    private fun pill(label: String, accent: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = pressable(if (accent) ACCENT else 0x2EFFFFFF, dp(22))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildPlayer(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        // ---- верхняя панель: статус связи и кнопка настроек ----
        chipDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) } }
        chipText = text(13f, Color.WHITE)
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = pressable(0x26FFFFFF, dp(20))
            isClickable = true
            setOnClickListener { showSettings(true) }
            addView(chipDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            addView(chipText)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chip)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pill("Настройки") { showSettings(true) })
        }
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        // ---- пластинка по центру свободного места ----
        val dm = resources.displayMetrics
        val size = min(min(dm.widthPixels - dp(72), dp(320)), (dm.heightPixels * 0.40f).toInt())
        vinyl = VinylView(this)
        spin = ObjectAnimator.ofFloat(vinyl, "angle", 0f, 360f).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
            pause()
        }
        val vinylBox = FrameLayout(this).apply {
            minimumHeight = size + dp(32)
            addView(vinyl, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        col.addView(vinylBox, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- название, исполнитель и сердечко ----
        tvTitle = text(21f, Color.WHITE, bold = true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        tvArtist = text(15f, 0xCCFFFFFF.toInt()).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        tvApp = text(12f, 0x80FFFFFF.toInt()).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        btnLike = IconButton(this).apply {
            plain = true
            kind = IconButton.Kind.HEART
            setOnClickListener { controller?.let { MediaUtil.execute(it, "like") } }
        }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvTitle)
            addView(tvArtist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(tvApp, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btnLike, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        }
        col.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---- кнопка «Включить доступ», если его нет ----
        btnSetup = pill("Включить доступ к уведомлениям", accent = true) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btnSetup.visibility = View.GONE
        col.addView(btnSetup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // ---- ползунок и время ----
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x4DFFFFFF)
            setPadding(dp(2), dp(10), dp(2), dp(10))
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
        col.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        tvTime = text(12f, 0xB3FFFFFF.toInt()).apply { text = "00:00" }
        tvTotal = text(12f, 0xB3FFFFFF.toInt()).apply { text = "--:--" }
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tvTime, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tvTotal)
        }
        col.addView(times, LinearLayout.LayoutParams(-1, -2))

        // ---- кнопки управления: три крупные кнопки по центру ----
        btnPrev = IconButton(this).apply {
            kind = IconButton.Kind.PREV
            setOnClickListener { controller?.let { MediaUtil.execute(it, "prev") } }
        }
        btnPlay = IconButton(this).apply {
            kind = IconButton.Kind.PLAY
            solid = true
            setOnClickListener { controller?.let { MediaUtil.execute(it, "toggle") } }
        }
        btnNext = IconButton(this).apply {
            kind = IconButton.Kind.NEXT
            setOnClickListener { controller?.let { MediaUtil.execute(it, "next") } }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cell(btnPrev, dp(60)), LinearLayout.LayoutParams(0, dp(80), 1f))
            addView(cell(btnPlay, dp(80)), LinearLayout.LayoutParams(0, dp(80), 1f))
            addView(cell(btnNext, dp(60)), LinearLayout.LayoutParams(0, dp(80), 1f))
        }
        col.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tintColor, BG))
            addView(col, FrameLayout.LayoutParams(-1, -2))
        }
    }

    /** Ячейка одинаковой ширины, в центре которой стоит кнопка заданного размера. */
    private fun cell(button: View, sizePx: Int) = FrameLayout(this).apply {
        addView(button, FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER))
    }

    private fun card(title: String, description: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = rounded(0xFF181A21.toInt(), dp(20))
        addView(text(16f, Color.WHITE, bold = true).also { it.text = title })
        addView(
            text(13f, 0xFF9AA0AC.toInt()).apply { text = description },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
    }

    private fun buildSettings(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pill("← Назад") { showSettings(false) })
            addView(
                text(22f, Color.WHITE, bold = true).apply { text = "Настройки" },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) }
            )
        }
        col.addView(header, LinearLayout.LayoutParams(-1, -2))

        // ---- 1. доступ к уведомлениям ----
        tvAccess = text(14f, Color.WHITE, bold = true)
        val c1 = card("1. Доступ к уведомлениям", "Нужен, чтобы приложение видело, что играет, и могло управлять плеером.")
        c1.addView(tvAccess, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        c1.addView(
            pill("Открыть настройки доступа", accent = true) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        )
        c1.addView(
            text(12f, 0xFF7C8390.toInt()).apply {
                text = "Если Android пишет «ограниченная настройка»: Настройки → Приложения → NowPlaying → ⋮ → «Разрешить ограниченные настройки»."
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        )
        col.addView(c1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        // ---- 2. работа в фоне ----
        val c2 = card("2. Работа в фоне", "Чтобы Android не отключал связь с ПК при выключенном экране.")
        c2.addView(
            pill("Открыть настройки приложения") {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        )
        c2.addView(
            text(12f, 0xFF7C8390.toInt()).apply { text = "Там откройте «Батарея» и выберите «Без ограничений»." },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        )
        col.addView(c2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // ---- 3. связь с ПК ----
        tvConn = text(14f, Color.WHITE, bold = true)
        val c3 = card("3. Связь с ПК", "Телефон ищет компьютер сам: NowPlaying должен быть запущен на ПК в той же Wi-Fi сети.")
        c3.addView(tvConn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        c3.addView(
            text(12f, 0xFF7C8390.toInt()).apply { text = "IP компьютера (нужен, только если автопоиск не сработал):" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        )
        etHost = EditText(this).apply {
            hint = "например 192.168.1.50"
            setHintTextColor(0xFF6C7280.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            background = rounded(0xFF22252D.toInt(), dp(14))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setText(Sender.host(this@MainActivity))
        }
        c3.addView(etHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val save = pill("Сохранить") {
            Sender.saveHost(this, etHost.text.toString())
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }
        val test = pill("Проверить связь", accent = true) { testConnection() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(save, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(test, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        }
        c3.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        col.addView(c3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(col, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun showSettings(show: Boolean) {
        settingsPage.visibility = if (show) View.VISIBLE else View.GONE
        playerPage.visibility = if (show) View.GONE else View.VISIBLE
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsPage.visibility == View.VISIBLE) showSettings(false) else super.onBackPressed()
    }

    private fun testConnection() {
        Sender.saveHost(this, etHost.text.toString())
        val json = JSONObject()
            .put("title", "Связь работает")
            .put("artist", "NowPlaying")
            .put("app", "Тест")
            .put("playing", false)
            .put("position", 0)
            .put("duration", 0)
        Toast.makeText(this, "Проверяю...", Toast.LENGTH_SHORT).show()
        Sender.post(this, json) { resp ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (resp != null) "Готово, карточка появилась на ПК"
                    else "Нет связи с ПК. Запущен ли NowPlaying? Та же ли Wi-Fi сеть? Порты 8765 и 8766 открыты в брандмауэре?",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------------------------------------------------------- обновление данных

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

    /** Цвет фона из средней окраски обложки, затемнённый, чтобы белый текст читался. */
    private fun tintFor(bmp: Bitmap?): Int {
        if (bmp == null || bmp.isRecycled) return DEFAULT_TINT
        return try {
            val px = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            Color.colorToHSV(px, hsv)
            hsv[1] = min(1f, hsv[1] * 1.1f)
            hsv[2] = hsv[2].coerceIn(0.28f, 0.42f)
            Color.HSVToColor(hsv)
        } catch (_: Exception) {
            DEFAULT_TINT
        }
    }

    private fun setChip(color: Int, label: String) {
        (chipDot.background as GradientDrawable).setColor(color)
        chipText.text = label
    }

    private fun refresh() {
        val access = notificationAccessGranted()
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
            tvTitle.text = if (list == null) "Нужен доступ" else "Ничего не играет"
            tvArtist.text = if (list == null) "Выдайте доступ к уведомлениям, чтобы видеть трек" else "Включите музыку в любом плеере"
            tvApp.text = ""
            tvTotal.text = "--:--"
            if (!userSeeking) {
                tvTime.text = "00:00"
                seek.progress = 0
            }
            seek.max = 1
            seek.isEnabled = false
            btnPlay.kind = IconButton.Kind.PLAY
            btnLike.kind = IconButton.Kind.HEART
            for (b in listOf(btnPrev, btnPlay, btnNext, btnLike)) b.isEnabled = false
            vinyl.cover = null
            shownCover = null
            applyTint(null)
            if (!spin.isPaused) spin.pause()
        } else {
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val playing = st?.state == PlaybackState.STATE_PLAYING
            val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val pos = MediaUtil.position(st)
            val like = MediaUtil.likeAction(st)

            tvTitle.text = title
            tvArtist.text = artist
            tvApp.text = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
            } catch (_: Exception) {
                c.packageName
            }
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
                applyTint(cover)
            }
            if (playing) {
                if (spin.isPaused) spin.resume()
            } else if (!spin.isPaused) {
                spin.pause()
            }
        }

        // ---- статус связи ----
        val ago = System.currentTimeMillis() - Sender.lastOk
        val target = Sender.target(this)
        val connected = Sender.lastOk > 0 && ago < 15000
        btnSetup.visibility = if (access) View.GONE else View.VISIBLE
        when {
            !access -> setChip(0xFFFF5A5F.toInt(), "Нет доступа к уведомлениям")
            connected -> setChip(0xFF3DDC84.toInt(), "ПК подключён")
            else -> setChip(0xFFFFB020.toInt(), "Ищу ПК в Wi-Fi...")
        }

        if (settingsPage.visibility == View.VISIBLE) {
            tvAccess.text = if (access) "✓ Доступ выдан" else "✗ Доступ не выдан"
            tvAccess.setTextColor(if (access) 0xFF3DDC84.toInt() else 0xFFFF5A5F.toInt())
            tvConn.text = when {
                connected -> "✓ Подключено" + if (target.isNotEmpty()) " ($target)" else ""
                target.isEmpty() -> "… Ищу компьютер в Wi-Fi сети"
                else -> "✗ Нет ответа от $target"
            }
            tvConn.setTextColor(
                when {
                    connected -> 0xFF3DDC84.toInt()
                    target.isEmpty() -> 0xFFFFB020.toInt()
                    else -> 0xFFFF5A5F.toInt()
                }
            )
        }
    }

    private fun applyTint(cover: Bitmap?) {
        val t = tintFor(cover)
        if (t == tintColor) return
        tintColor = t
        playerPage.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t, BG))
    }

    companion object {
        private const val BG = 0xFF0E0F13.toInt()
        private const val ACCENT = 0xFF4F6BFF.toInt()
        private const val DEFAULT_TINT = 0xFF2A2140.toInt()
    }
}
