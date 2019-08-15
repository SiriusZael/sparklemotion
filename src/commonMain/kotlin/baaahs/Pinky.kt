package baaahs

import baaahs.geom.Vector2F
import baaahs.io.ByteArrayReader
import baaahs.io.ByteArrayWriter
import baaahs.io.Fs
import baaahs.mapper.MapperEndpoint
import baaahs.mapper.MappingResults
import baaahs.mapper.Storage
import baaahs.net.FragmentingUdpLink
import baaahs.net.Network
import baaahs.proto.*
import baaahs.shaders.PixelShader
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

class Pinky(
    val model: Model<*>,
    val shows: List<Show>,
    val network: Network,
    val dmxUniverse: Dmx.Universe,
    val beatSource: BeatSource,
    val clock: Clock,
    val fs: Fs,
    val firmwareDaddy: FirmwareDaddy,
    val display: PinkyDisplay,
    private val prerenderPixels: Boolean = false
) : Network.UdpListener {
    private val storage = Storage(fs)
    private val mappingResults = storage.loadMappingData(model)

    private val link = FragmentingUdpLink(network.link())
    val httpServer = link.startHttpServer(Ports.PINKY_UI_TCP)


    private val beatProvider = PinkyBeatDisplayer(beatSource)
    private var mapperIsRunning = false
    private var selectedShow = shows.first()
        set(value) {
            field = value
            display.selectedShow = value
            showRunner.nextShow = selectedShow
        }

    private val pubSub: PubSub.Server = PubSub.Server(httpServer).apply { install(gadgetModule) }
    private val gadgetManager = GadgetManager(pubSub)
    private val movingHeadManager = MovingHeadManager(fs, pubSub, model.movingHeads)
    private val showRunner =
        ShowRunner(model, selectedShow, gadgetManager, beatSource, dmxUniverse, movingHeadManager, clock)

    private val brainToSurfaceMap_CHEAT = mutableMapOf<BrainId, Model.Surface>()
    private val surfaceToPixelLocationMap_CHEAT = mutableMapOf<Model.Surface, List<Vector2F>>()

    private val brainInfos: MutableMap<BrainId, BrainInfo> = mutableMapOf()
    private val pendingBrainInfos: MutableMap<BrainId, BrainInfo> = mutableMapOf()

    val address: Network.Address get() = link.myAddress
    private val networkStats = NetworkStats()

    // This needs to go last-ish, otherwise we start getting network traffic too early.
    private val udpSocket = link.listenUdp(Ports.PINKY, this)

    init {
        httpServer.listenWebSocket("/ws/mapper") { MapperEndpoint(storage) }
    }

    suspend fun run(): Show.Renderer {
        GlobalScope.launch { beatProvider.run() }

        display.listShows(shows)
        display.selectedShow = selectedShow

        pubSub.publish(Topics.availableShows, shows.map { show -> show.name }) {}
        val selectedShowChannel = pubSub.publish(Topics.selectedShow, shows[0].name) { selectedShow ->
            this.selectedShow = shows.find { it.name == selectedShow }!!
        }

        display.onShowChange = {
            this.selectedShow = display.selectedShow!!
            selectedShowChannel.onChange(this.selectedShow.name)
        }

        while (true) {
            if (mapperIsRunning) {
                disableDmx()
                delay(50)
                continue
            }

            updateSurfaces()

            networkStats.reset()
            val elapsedMs = time {
                drawNextFrame()
            }
            display.showFrameMs = elapsedMs.toInt()
            display.stats = networkStats

            delay(50)
        }
    }

    internal fun updateSurfaces() {
        if (pendingBrainInfos.isNotEmpty()) {
            val brainSurfacesToRemove = mutableListOf<ShowRunner.SurfaceReceiver>()
            val brainSurfacesToAdd = mutableListOf<ShowRunner.SurfaceReceiver>()

            pendingBrainInfos.forEach { (brainId, brainInfo) ->
                val priorBrainInfo = brainInfos[brainId]
                if (priorBrainInfo != null) {
                    brainSurfacesToRemove.add(priorBrainInfo.surfaceReceiver)
                }
                brainSurfacesToAdd.add(brainInfo.surfaceReceiver)

                brainInfos[brainId] = brainInfo
            }

            showRunner.surfacesChanged(brainSurfacesToAdd, brainSurfacesToRemove)

            pendingBrainInfos.clear()
        }

        display.brainCount = brainInfos.size
    }

    internal fun drawNextFrame() {
        aroundNextFrame {
            showRunner.nextFrame()
        }
    }

    private fun disableDmx() {
        dmxUniverse.allOff()
    }

    override fun receive(fromAddress: Network.Address, fromPort: Int, bytes: ByteArray) {
        val message = parse(bytes)
        when (message) {
            is BrainHelloMessage -> foundBrain(fromAddress, message)
            is MapperHelloMessage -> {
                println("Mapper isRunning=${message.isRunning}")
                mapperIsRunning = message.isRunning
            }
            is PingMessage -> if (message.isPong) receivedPong(message, fromAddress)
        }
    }

    private fun foundBrain(
        brainAddress: Network.Address,
        msg: BrainHelloMessage
    ) {
        val brainId = BrainId(msg.brainId)
        val surfaceName = msg.surfaceName

        println("foundBrain($brainAddress, $msg)")
        if (firmwareDaddy.doesntLikeThisVersion(msg.firmwareVersion)) {
            // You need the new hotness bro
            println("The firmware daddy doesn't like $brainId having ${msg.firmwareVersion} so we'll send ${firmwareDaddy.urlForPreferredVersion}")
            val newHotness = UseFirmwareMessage(firmwareDaddy.urlForPreferredVersion)
            udpSocket.sendUdp(brainAddress, Ports.BRAIN, newHotness)
        }


        // println("Heard from brain $brainId at $brainAddress for $surfaceName")
        val dataFor = mappingResults.dataFor(brainId) ?: findMappingInfo_CHEAT(surfaceName, brainId)

        val surface = dataFor?.let {
            val pixelVertices = dataFor.pixelLocations
            val pixelCount = pixelVertices?.size ?: SparkleMotion.MAX_PIXEL_COUNT

            val mappingMsg = BrainMappingMessage(
                brainId, dataFor.surface.name, null, Vector2F(0f, 0f),
                Vector2F(0f, 0f), pixelCount, pixelVertices ?: emptyList()
            )
            udpSocket.sendUdp(brainAddress, Ports.BRAIN, mappingMsg)
            IdentifiedSurface(dataFor.surface, pixelCount, dataFor.pixelLocations)
        } ?: AnonymousSurface(brainId)


        val priorBrainInfo = brainInfos[brainId]
        if (priorBrainInfo != null) {
            if (priorBrainInfo.brainId == brainId && priorBrainInfo.surface == surface) {
                // Duplicate packet?
//                logger.debug(
//                    "Ignore ${priorBrainInfo.brainId} ${priorBrainInfo.surface.describe()} ->" +
//                            " ${surface.describe()} because probably duplicate?"
//                )
                return
            }

//            logger.debug(
//                "Remapping ${priorBrainInfo.brainId} from ${priorBrainInfo.surface.describe()} ->" +
//                        " ${surface.describe()}"
//            )
        }

        val sendFn: (Shader.Buffer) -> Unit = { shaderBuffer -> sendBrainShaderMessage(brainAddress, shaderBuffer) }
        val surfaceReceiver = if (prerenderPixels) {
            PrerenderingSurfaceReceiver(surface, sendFn)
        } else {
            ShowRunner.SurfaceReceiver(surface, sendFn)
        }

        val brainInfo = BrainInfo(brainAddress, brainId, surface, msg.firmwareVersion, msg.idfVersion, surfaceReceiver)
//        logger.debug("Map ${brainInfo.brainId} to ${brainInfo.surface.describe()}")
        pendingBrainInfos[brainId] = brainInfo

        // Decide whether or not to tell this brain it should use a different firmware

    }

    private fun sendBrainShaderMessage(brainAddress: Network.Address, shaderBuffer: Shader.Buffer) {
        // If any of this network stuff blows up, we let that propagate
        // so that a caller knows not to try again. The common occurrence is
        // that Pinky learns of a Brain, but then that brain goes away. Well
        // Pinky needs to stop sending messages in that case and the way
        // to detect that seems to be via an excpetion thrown from the network
        // layer that we call here. So over in ShowRunner is where the list of
        // surface receivers needs to be cleaned up.
        val pongData = ByteArrayWriter().apply {
            writeLong(getTimeMillis())
        }.toBytes()

        val message = BrainShaderMessage(shaderBuffer.shader, shaderBuffer, pongData).toBytes()
        udpSocket.sendUdp(brainAddress, Ports.BRAIN, message)

        networkStats.packetsSent++
        networkStats.bytesSent += message.size
    }


    private fun findMappingInfo_CHEAT(surfaceName: String?, brainId: BrainId): MappingResults.Info? {
        val modelSurface = surfaceName?.let { model.findModelSurface(surfaceName) } ?: brainToSurfaceMap_CHEAT[brainId]
        return if (modelSurface != null) {
            MappingResults.Info(modelSurface, surfaceToPixelLocationMap_CHEAT[modelSurface])
        } else {
            null
        }
    }

    private fun receivedPong(message: PingMessage, fromAddress: Network.Address) {
        val originalSentAt = ByteArrayReader(message.data).readLong()
        val elapsedMs = getTimeMillis() - originalSentAt
//        logger.debug("Shader pong from $fromAddress took ${elapsedMs}ms")
    }

    fun providePanelMapping_CHEAT(brainId: BrainId, surface: Model.Surface) {
        brainToSurfaceMap_CHEAT[brainId] = surface
    }

    fun providePixelMapping_CHEAT(surface: Model.Surface, pixelLocations: List<Vector2F>) {
        surfaceToPixelLocationMap_CHEAT[surface] = pixelLocations
    }

    inner class PinkyBeatDisplayer(val beatSource: BeatSource) {
        suspend fun run() {
            while (true) {
               val beatData = beatSource.getBeatData()
                display.beat = beatData.beatWithinMeasure(clock).toInt()
                display.bpm = beatData.bpm.toInt() //TODO: show fractions of bpm
                delay(10)
            }
        }
    }

    class NetworkStats(var bytesSent: Int = 0, var packetsSent: Int = 0) {
        internal fun reset() {
            bytesSent = 0
            packetsSent = 0
        }
    }

    private inner class PrerenderingSurfaceReceiver(surface: Surface, sendFn: (Shader.Buffer) -> Unit) :
        ShowRunner.SurfaceReceiver(surface, sendFn) {
        var currentRenderTree: Brain.RenderTree<*>? = null
        private var currentPoolKey: Any? = null
        var pixels: PixelsAdapter? = null
        var currentBuffer: Shader.Buffer? = null

        @Suppress("UNCHECKED_CAST")
        override fun send(shaderBuffer: Shader.Buffer) {
            val shader = shaderBuffer.shader as Shader<Shader.Buffer>
            var renderTree = currentRenderTree
            if (renderTree == null || renderTree.shader != shader) {
                val priorPoolKey = currentPoolKey
                var newPoolKey: Any? = null

                val renderer = shader.createRenderer(surface, object : RenderContext {
                    override fun <T : PooledRenderer> registerPooled(key: Any, fn: () -> T): T {
                        newPoolKey = key
                        return poolingRenderContext.registerPooled(key, fn)
                    }
                })

                if (newPoolKey != priorPoolKey) {
                    if (priorPoolKey != null) {
                        poolingRenderContext.decrement(priorPoolKey)
                    }
                    currentPoolKey = newPoolKey
                }

                renderTree = Brain.RenderTree(shader, renderer, shaderBuffer)
                currentRenderTree = renderTree

                if (pixels == null) {
                    val pixelBuffer = PixelShader(PixelShader.Encoding.DIRECT_RGB).createBuffer(surface)
                    pixels = PixelsAdapter(pixelBuffer)
                }
            }

            val renderer = currentRenderTree!!.renderer as Shader.Renderer<Shader.Buffer>
            renderer.beginFrame(shaderBuffer, pixels!!.size)

            // we need to reorder the draw cycle, so don't do the rest of the render yet!
            currentBuffer = shaderBuffer
        }

        @Suppress("UNCHECKED_CAST")
        fun actuallySend() {
            val renderTree = currentRenderTree
            if (renderTree != null) {
                val renderer = renderTree.renderer as Shader.Renderer<Shader.Buffer>
                val pixels = pixels!!
                val currentBuffer = currentBuffer!!

                for (i in pixels.indices) {
                    pixels[i] = renderer.draw(currentBuffer, i)
                }
                this.currentBuffer = null

                renderer.endFrame()
                pixels.finishedFrame()

                renderTree.draw(pixels)

                super.send(pixels.buffer)
            }
        }
    }

    var poolingRenderContext = PoolingRenderContext()
    var lastSentAt: Long = 0

    private fun aroundNextFrame(callNextFrame: () -> Unit) {
        /**
         * [ShowRunner.SurfaceReceiver.send] is called here; if [prerenderPixels] is true, it won't
         * actually send; we need to do that ourselves.
         */
        callNextFrame()

        if (prerenderPixels) {
            val preDrawElapsed = timeSync {
                poolingRenderContext.preDraw()
            }

            val sendElapsed = timeSync {
                brainInfos.values.forEach { brainInfo ->
                    val surfaceReceiver = brainInfo.surfaceReceiver as PrerenderingSurfaceReceiver
                    surfaceReceiver.actuallySend()
                }
            }

//            println("preDraw took ${preDrawElapsed}ms, send took ${sendElapsed}ms")
        }
        val now = getTimeMillis()
        val elapsedMs = now - lastSentAt
//        println("It's been $elapsedMs")
        lastSentAt = now
    }


    class PoolingRenderContext : RenderContext {
        private val pooledRenderers = hashMapOf<Any, Holder<*>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : PooledRenderer> registerPooled(key: Any, fn: () -> T): T {
            val holder = pooledRenderers.getOrPut(key) { Holder(fn()) }
            holder.count++
            return holder.pooledRenderer as T
        }

        fun decrement(key: Any) {
            val holder = pooledRenderers[key]!!
            holder.count--
            if (holder.count == 0) {
                logger.debug("Removing pooled renderer for $key")
                pooledRenderers.remove(key)
            }
        }

        fun preDraw() {
            pooledRenderers.values.forEach { holder ->
                holder.pooledRenderer.preDraw()
            }
        }

        class Holder<T : PooledRenderer>(val pooledRenderer: T, var count: Int = 0)
    }

    private class PixelsAdapter(internal val buffer: PixelShader.Buffer) : Pixels {
        override val size: Int = buffer.colors.size

        override fun get(i: Int): Color = buffer.colors[i]

        override fun set(i: Int, color: Color) {
            buffer.colors[i] = color
        }

        override fun set(colors: Array<Color>) {
            for (i in 0 until min(colors.size, size)) {
                buffer.colors[i] = colors[i]
            }
        }

    }
}

data class BrainId(val uuid: String)

class BrainInfo(
    val address: Network.Address,
    val brainId: BrainId,
    val surface: Surface,
    val firmwareVersion: String?,
    val idfVersion: String?,
    val surfaceReceiver: ShowRunner.SurfaceReceiver
)
