package com.example.nowplaying

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors

/**
 * Связь с NowPlaying на ПК: автопоиск компьютера в Wi-Fi сети, отправка состояния плеера
 * и приём команд (долгий запрос, команды приходят мгновенно).
 */
object Sender {
    const val PORT = 8765
    private const val DISCOVERY_PORT = 8766

    /** Время последнего успешного обмена с ПК (мс), для индикатора в приложении. */
    @Volatile
    var lastOk: Long = 0

    /** IP ПК, найденный автоматически. */
    @Volatile
    var discovered: String? = null

    private val executor = Executors.newSingleThreadExecutor()

    /** IP, введённый вручную (может быть пустым, тогда ПК ищется сам). */
    fun host(ctx: Context): String =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("host", "")?.trim().orEmpty()

    fun saveHost(ctx: Context, host: String) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("host", host.trim()).apply()
        discovered = null
    }

    /** Текущий адрес ПК: введённый вручную или найденный автоматически. */
    fun target(ctx: Context): String = host(ctx).ifEmpty { discovered.orEmpty() }

    /** Ищет ПК широковещательным запросом. Блокирующий вызов (до 1,5 с), только из фонового потока. */
    fun discover(ctx: Context): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1500
            val data = "NOWPLAYING_DISCOVER".toByteArray()
            for (addr in broadcastAddresses(ctx)) {
                try {
                    socket.send(DatagramPacket(data, data.size, addr, DISCOVERY_PORT))
                } catch (_: Exception) {
                }
            }
            val reply = DatagramPacket(ByteArray(64), 64)
            socket.receive(reply)
            val msg = String(reply.data, 0, reply.length)
            if (msg.startsWith("NOWPLAYING_HERE")) {
                val ip = reply.address.hostAddress
                discovered = ip
                return ip
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }
        return null
    }

    private fun broadcastAddresses(ctx: Context): List<InetAddress> {
        val list = ArrayList<InetAddress>()
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val d = wm.dhcpInfo
            if (d != null && d.ipAddress != 0) {
                val b = (d.ipAddress and d.netmask) or d.netmask.inv()
                val bytes = byteArrayOf(
                    (b and 0xff).toByte(),
                    ((b shr 8) and 0xff).toByte(),
                    ((b shr 16) and 0xff).toByte(),
                    ((b shr 24) and 0xff).toByte()
                )
                list.add(InetAddress.getByAddress(bytes))
            }
        } catch (_: Exception) {
        }
        return list
    }

    /** Отправляет состояние в фоновом потоке. onDone получает ответ ПК или null при ошибке. */
    fun post(ctx: Context, json: JSONObject, onDone: ((JSONObject?) -> Unit)? = null) {
        executor.execute {
            var ip = target(ctx)
            if (ip.isEmpty()) ip = discover(ctx).orEmpty()
            if (ip.isEmpty()) {
                onDone?.invoke(null)
                return@execute
            }
            var result: JSONObject? = null
            try {
                val conn = URL("http://$ip:$PORT/now").openConnection() as HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 3000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    result = try {
                        JSONObject(text)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    lastOk = System.currentTimeMillis()
                }
                conn.disconnect()
            } catch (_: Exception) {
                // ПК мог сменить адрес: в автоматическом режиме найдём его заново
                if (host(ctx).isEmpty()) discovered = null
            }
            onDone?.invoke(result)
        }
    }

    /**
     * Ждёт команды от ПК (до ~25 с). Блокирующий вызов, только из фонового потока.
     * Возвращает список команд (может быть пустым) или null при ошибке связи.
     */
    fun pollOnce(ctx: Context): List<JSONObject>? {
        val ip = target(ctx)
        if (ip.isEmpty()) {
            discover(ctx)
            return null
        }
        return try {
            val conn = URL("http://$ip:$PORT/poll").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 35000
            val cmds = ArrayList<JSONObject>()
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(text).optJSONArray("cmds")
                if (arr != null) for (i in 0 until arr.length()) cmds.add(arr.getJSONObject(i))
                lastOk = System.currentTimeMillis()
                conn.disconnect()
                cmds
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Exception) {
            if (host(ctx).isEmpty()) discovered = null
            null
        }
    }
}
