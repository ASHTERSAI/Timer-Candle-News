package com.trader.wibcandletimer

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object NtpTimeSync {
    private const val NTP_PORT = 123
    private const val NTP_PACKET_SIZE = 48
    private const val OFFSET_1900_TO_1970 = 2208988800L
    private val SERVERS = listOf("time.google.com", "pool.ntp.org", "time.cloudflare.com")

    @Volatile
    var isSynced: Boolean = false
        private set

    @Volatile
    var lastServer: String = "Belum sinkron (jam HP)"
        private set

    @Volatile
    private var anchorUtcMs: Long = 0L

    @Volatile
    private var anchorElapsedMs: Long = 0L

    fun syncBlocking(timeoutMs: Int = 3000): Boolean {
        for (host in SERVERS) {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val address = InetAddress.getByName(host)
                    val buffer = ByteArray(NTP_PACKET_SIZE)
                    buffer[0] = 0x1B // LI=0, VN=3, Mode=3 (Client)

                    val t1 = System.currentTimeMillis()
                    val t1Elapsed = SystemClock.elapsedRealtime()

                    val request = DatagramPacket(buffer, buffer.size, address, NTP_PORT)
                    socket.send(request)

                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)

                    val t4Elapsed = SystemClock.elapsedRealtime()
                    val t4 = t1 + (t4Elapsed - t1Elapsed)

                    val t2 = readTimeStamp(buffer, 32)
                    val t3 = readTimeStamp(buffer, 40)

                    if (t2 > 0L && t3 > 0L) {
                        val clockOffset = ((t2 - t1) + (t3 - t4)) / 2L
                        anchorUtcMs = t4 + clockOffset
                        anchorElapsedMs = t4Elapsed
                        isSynced = true
                        lastServer = host
                        return true
                    }
                }
            } catch (_: Exception) {
                // Coba server NTP cadangan berikutnya
            }
        }
        return false
    }

    fun nowUtcMs(): Long {
        return if (isSynced) {
            anchorUtcMs + (SystemClock.elapsedRealtime() - anchorElapsedMs)
        } else {
            System.currentTimeMillis()
        }
    }

    private fun read32(buffer: ByteArray, offset: Int): Long {
        val b0 = buffer[offset].toLong() and 0xFF
        val b1 = buffer[offset + 1].toLong() and 0xFF
        val b2 = buffer[offset + 2].toLong() and 0xFF
        val b3 = buffer[offset + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = read32(buffer, offset)
        val fraction = read32(buffer, offset + 4)
        if (seconds == 0L && fraction == 0L) return 0L
        return (seconds - OFFSET_1900_TO_1970) * 1000L + (fraction * 1000L) / 0x100000000L
    }
}
