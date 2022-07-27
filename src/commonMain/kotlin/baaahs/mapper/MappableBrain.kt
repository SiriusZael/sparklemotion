package baaahs.mapper

import baaahs.Color
import baaahs.MediaDevices
import baaahs.SparkleMotion
import baaahs.imaging.Bitmap
import baaahs.model.Model
import baaahs.net.Network
import baaahs.shaders.PixelBrainShader
import baaahs.sm.brain.proto.BrainShaderMessage
import baaahs.sm.brain.proto.Message
import baaahs.sm.brain.proto.Ports

class MappableBrain(
    val address: Network.Address,
    val brainId: String,
    val sendUdp: (message: Message) -> Unit
) {
    val port get() = Ports.BRAIN

    var expectedPixelCount: Int? = null
    val expectedPixelCountOrDefault: Int
        get() = (guessedEntity as? Model.Surface)?.expectedPixelCount
            ?: expectedPixelCount
            ?: SparkleMotion.DEFAULT_PIXEL_COUNT

    var changeRegion: MediaDevices.Region? = null
    var guessedEntity: Model.Entity? = null
    var guessedVisibleSurface: Mapper.VisibleSurface? = null
    var panelDeltaBitmap: Bitmap? = null
    var deltaImageName: String? = null
    val pixelMapData: MutableMap<Int, Mapper.PixelMapData> = mutableMapOf()

    private val pixelShader = PixelBrainShader(PixelBrainShader.Encoding.INDEXED_2)
    val pixelShaderBuffer = pixelShader.createBuffer(Mapper.maxPixelsPerBrain).apply {
        palette[0] = Color.BLACK
        palette[1] = Color.WHITE
        setAll(0)
    }

    fun shade(shaderMessage: () -> BrainShaderMessage) {
        sendUdp(shaderMessage())
    }

    fun send(message: Message) {
        sendUdp(message)
    }
}