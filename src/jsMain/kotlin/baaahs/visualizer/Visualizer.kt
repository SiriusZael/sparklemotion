package baaahs.visualizer

import baaahs.document
import baaahs.mapper.JsMapperUi
import baaahs.model.Model
import baaahs.util.Clock
import baaahs.util.Framerate
import baaahs.util.asMillis
import baaahs.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSpanElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import three.js.*
import three_ext.OrbitControls
import kotlin.collections.set
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class Visualizer(
    private val model: Model,
    private val clock: Clock
) : JsMapperUi.StatusListener {
    val facade = Facade()

    private var container: HTMLElement? = null
        set(value) {
            if (value != null) {
                field = value
                containerAttached()
            } else {
                containerWillDetach()
                field = value
            }
        }

    var stopRendering: Boolean = false
    var rotate: Boolean = false

    var mapperIsRunning = false
        set(isRunning) {
            field = isRunning

            entityVisualizers.forEach { it.mapperIsRunning = isRunning }

            if (isRunning) {
                rotate = false
            }
        }

    private val prerenderListeners = mutableListOf<() -> Unit>()
    private val frameListeners = mutableListOf<FrameListener>()

    private var controls: OrbitControls? = null
    private val camera: PerspectiveCamera =
        PerspectiveCamera(45, 1.0, 1, 10000).apply {
            position.z = 1000.0
        }
    private val scene: Scene = Scene()
    private val renderer = WebGLRenderer().apply {
        localClippingEnabled = true
    }

    private val pointMaterial = PointsMaterial().apply { color.set(0xffffff) }

    private val raycaster = Raycaster()
    private var mouse: Vector2? = null
    private val originDot: Mesh<*, *>

    private val entityVisualizers = arrayListOf<EntityVisualizer>()
    private val sceneObjs = mutableMapOf<Number, EntityVisualizer>()
    private val selectionSpan = document.createElement("span") as HTMLSpanElement
    private var selectedEntity: EntityVisualizer? = null
        set(value) {
            field?.let {
                console.log("Deselecting ${it.title}")
                it.selected = false
            }
            field = value

            if (value == null) {
                selectionSpan.style.display = "none"
                selectionSpan.innerText = ""
            } else {
                console.log("Selecting ${value.title}")
                selectionSpan.style.display = "inherit"
                selectionSpan.innerText = "Selected: ${value.title}"
            }

            facade.selectedEntity = value
            facade.notifyChanged()
        }

    init {
        scene.add(camera)
//        renderer.setPixelRatio(window.devicePixelRatio)

        raycaster.asDynamic().params.Points.threshold = 1
        originDot = Mesh(
            SphereBufferGeometry(1, 32, 32),
            MeshBasicMaterial().apply { color.set(0xff0000) }
        )
        scene.add(originDot)
    }

    init {
        var resizeTaskId: Int? = null
        window.addEventListener("resize", {
            if (resizeTaskId !== null) {
                window.clearTimeout(resizeTaskId!!)
            }

            resizeTaskId = window.setTimeout({
                resizeTaskId = null
                resize()
            }, resizeDelay)
        })
    }

    private fun containerAttached() {
        container!!.appendChild(renderer.domElement)
        container!!.appendChild(selectionSpan)
        container!!.addEventListener("pointerdown", this::onMouseDown)

        controls = OrbitControls(camera, container!!).apply {
            minPolarAngle = PI / 2 - .25 // radians
            maxPolarAngle = PI / 2 + .25 // radians

            enableKeys = false
        }

        resize()
        startRender()
    }

    private fun containerWillDetach() {
        container?.removeChild(renderer.domElement)
        container?.removeChild(selectionSpan)
        container?.removeEventListener("pointerdown", this::onMouseDown)
        controls?.dispose()
        stopRendering = true
        controls = null
    }

    fun addPrerenderListener(callback: () -> Unit) {
        prerenderListeners.add(callback)
    }

    fun removePrerenderListener(callback: () -> Unit) {
        prerenderListeners.remove(callback)
    }

    fun addFrameListener(frameListener: FrameListener) {
        frameListeners.add(frameListener)
    }

    fun removeFrameListener(frameListener: FrameListener) {
        frameListeners.remove(frameListener)
    }

    fun onMouseDown(event: Event) {
        event as MouseEvent

        container?.let {
            mouse = Vector2(
                (event.clientX.toDouble() / it.offsetWidth) * 2 - 1,
                -(event.clientY.toDouble() / it.offsetHeight) * 2 + 1
            )
        }
    }

    fun addEntityVisualizer(entityVisualizer: EntityVisualizer) {
        entityVisualizer.addTo(VizScene(object : SceneListener {
            override fun add(obj: Object3D) {
                obj.name = entityVisualizer.title
                sceneObjs[obj.id] = entityVisualizer
                scene.add(obj)
            }

            override fun remove(obj: Object3D) {
                scene.remove(obj)
                sceneObjs.remove(obj.id)
            }
        }))

        entityVisualizers.add(entityVisualizer)
    }

    private fun startRender() {
        pointAtModel()

        stopRendering = false
        requestAnimationFrame()
    }

    private fun pointAtModel() {
        val target = model.center.toVector3()
        controls?.target = target
        camera.lookAt(target)
    }

    fun render() {
        if (stopRendering) return

        prerenderListeners.forEach { value -> value.invoke() }

        mouse?.let { mouseClick ->
            mouse = null
            raycaster.setFromCamera(mouseClick, camera)
            val intersections = raycaster.intersectObjects(scene.children, false)
            var acceptedIntersection = false
            intersections.forEach { intersection ->
                val intersectedObject = intersection.`object`
                console.log("Found intersection with ${intersectedObject.name} at ${intersection.distance}.")
                if (!acceptedIntersection) {
                    sceneObjs[intersectedObject.id]?.let {
                        selectedEntity = it
                        acceptedIntersection = true
                    }
                }
            }
            if (intersections.isEmpty()) {
                selectedEntity = null
            }
        }

        if (!mapperIsRunning && rotate) {
            val rotSpeed = .01
            val x = camera.position.x
            val z = camera.position.z
            camera.position.x = x * cos(rotSpeed) + z * sin(rotSpeed)
            camera.position.z = z * cos(rotSpeed * 2) - x * sin(rotSpeed * 2)
            camera.lookAt(scene.position)
        }

        controls?.update()

        val startTime = clock.now()
        renderer.render(scene, camera)
        facade.framerate.elapsed((clock.now() - startTime).asMillis().toInt())

        frameListeners.forEach { f -> f.onFrameReady(scene, camera) }

        requestAnimationFrame()
    }

    private fun requestAnimationFrame() {
        window.requestAnimationFrame { render() }
    }

// vector.applyMatrix(object.matrixWorld).project(camera) to get 2d x,y coord

    private val resizeDelay = 100

    fun resize() {
        container?.let {
            val canvas = renderer.domElement
            canvas.width = it.offsetWidth
            canvas.height = it.offsetHeight

            camera.aspect = it.offsetWidth.toDouble() / it.offsetHeight
            camera.updateProjectionMatrix()

            renderer.setSize(it.offsetWidth, it.offsetHeight, updateStyle = false)
        }
    }

    override fun mapperStatusChanged(isRunning: Boolean) {
        mapperIsRunning = isRunning
    }

    interface FrameListener {
        @JsName("onFrameReady")
        fun onFrameReady(scene: Scene, camera: Camera)
    }

    inner class Facade : baaahs.ui.Facade() {
        var container: HTMLElement?
            get() = this@Visualizer.container
            set(value) {
                this@Visualizer.container = value
            }

        var rotate: Boolean
            get() = this@Visualizer.rotate
            set(value) {
                this@Visualizer.rotate = value
            }

        var selectedEntity: EntityVisualizer? = null

        val framerate = Framerate()

        fun resize() = this@Visualizer.resize()
    }

    companion object {
        private const val DEFAULT_REFRESH_DELAY = 50 // ms
    }
}
