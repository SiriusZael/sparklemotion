package baaahs.mapper

import baaahs.api.ws.WebSocketClient
import baaahs.imaging.Bitmap
import baaahs.net.Network
import com.soywiz.klock.DateTime

class MapperBackend(
    link: Network.Link,
    pinkyAddress: Network.Address,
    private val udpSockets: UdpSockets
) {
    private val webSocketClient = WebSocketClient(link, pinkyAddress)

    fun adviseMapperStatus(isRunning: Boolean) {
        udpSockets.adviseMapperStatus(isRunning)
    }

    suspend fun listSessions() =
        webSocketClient.listSessions()

    suspend fun loadSession(name: String) =
        webSocketClient.loadSession(name)

    suspend fun saveImage(sessionStartTime: DateTime, name: String, bitmap: Bitmap): String =
        webSocketClient.saveImage(sessionStartTime, name, bitmap)

    suspend fun saveSession(mappingSession: MappingSession) =
        webSocketClient.saveSession(mappingSession)

    suspend fun getImageUrl(filename: String) =
        webSocketClient.getImageUrl(filename)
}