package baaahs.model

import baaahs.dmx.Shenzarpy
import baaahs.geom.EulerAngle
import baaahs.geom.Vector3F
import baaahs.io.Fs
import baaahs.io.getResource
import baaahs.model.importers.ObjImporter
import baaahs.scene.*
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ModelData(
    val title: String,
    val entities: List<EntityData>,
    val units: ModelUnit = ModelUnit.Meters
) {
    fun edit(): MutableModel =
        MutableModel(this)

    fun open(): Model =
        Model(title, entities.map { entity -> entity.open() }, units)
}

enum class ModelUnit(val display: String) {
    Inches("\""),
    Feet("'"),
    Centimeters("cm"),
    Meters("m");

    companion object {
        val default = Centimeters
    }
}

@Polymorphic
sealed interface EntityData {
    val title: String
    val description: String?
    val position: Vector3F
    val rotation: EulerAngle
    val scale: Vector3F
    @Transient val id: EntityId

    fun edit(): MutableEntity
    fun open(): Model.Entity
}

@Serializable @SerialName("Import")
data class ImportedEntityData(
    override val title: String,
    override val description: String? = null,
    override val position: Vector3F = Vector3F.origin,
    override val rotation: EulerAngle = EulerAngle.identity,
    override val scale: Vector3F = Vector3F.unit3d,
    @Transient override val id: EntityId = Model.Entity.nextId(),
    val objData: String,
    val objDataIsFileRef: Boolean,
    @Polymorphic
    val metadata: EntityMetadataProvider? = null
) : EntityData {
    override fun edit(): MutableEntity = MutableImportedEntityGroup(this)

    override fun open(): ImportedEntityGroup {
        var importFail: Exception? = null
        val importerResults = try {
            ObjImporter.doImport(objData, objDataIsFileRef, title) {
                metadata?.getMetadataFor(this)?.expectedPixelCount
            }
        } catch (e: Exception) {
            importFail = e
            null
        }

        return ImportedEntityGroup(
            title, description, position, rotation, scale, importerResults, importFail, id
        )
    }
}

@Serializable @SerialName("MovingHead")
data class MovingHeadData(
    override val title: String,
    override val description: String? = null,
    override val position: Vector3F = Vector3F.origin,
    override val rotation: EulerAngle = EulerAngle.identity,
    override val scale: Vector3F = Vector3F.unit3d,
    @Transient override val id: EntityId = Model.Entity.nextId(),
    val baseDmxChannel: Int, // TODO: Remove this! Should come from mapping data.
    @Polymorphic
    val adapter: MovingHeadAdapter = Shenzarpy

) : EntityData {
    override fun edit(): MutableEntity =
        MutableMovingHeadData(this)

    override fun open(): Model.Entity =
        MovingHead(title, description, position, rotation, scale, baseDmxChannel, adapter, id)
}

@Serializable @SerialName("LightBar")
data class LightBarData(
    override val title: String,
    override val description: String? = null,
    override val position: Vector3F = Vector3F.origin,
    override val rotation: EulerAngle = EulerAngle.identity,
    override val scale: Vector3F = Vector3F.unit3d,
    @Transient override val id: EntityId = Model.Entity.nextId(),
    val startVertex: Vector3F,
    val endVertex: Vector3F
) : EntityData {
    override fun edit(): MutableEntity =
        MutableLightBarData(this)

    override fun open(): Model.Entity =
        LightBar(title, description, position, rotation, scale, startVertex, endVertex, id)
}

@Serializable @SerialName("PolyLine")
data class PolyLineData(
    override val title: String,
    override val description: String? = null,
    override val position: Vector3F = Vector3F.origin,
    override val rotation: EulerAngle = EulerAngle.identity,
    override val scale: Vector3F = Vector3F.unit3d,
    @Transient override val id: EntityId = Model.Entity.nextId(),
    val segments: List<SegmentData>
) : EntityData {
    override fun edit(): MutableEntity =
        MutablePolyLineData(this)

    override fun open(): Model.Entity =
        PolyLine(title, description, segments.map {
            PolyLine.Segment(it.startVertex, it.endVertex, it.pixelCount)
        }, position, rotation, scale, 1f, 1f, id)
}

@Serializable @SerialName("Grid")
data class GridData(
    override val title: String,
    override val description: String? = null,
    override val position: Vector3F = Vector3F.origin,
    override val rotation: EulerAngle = EulerAngle.identity,
    override val scale: Vector3F = Vector3F.unit3d,
    @Transient override val id: EntityId = Model.Entity.nextId(),
    val rows: Int,
    val columns: Int,
    val rowGap: Float,
    val columnGap: Float,
    val direction: Direction = Direction.ColumnsThenRows,
    val zigZag: Boolean = false
) : EntityData {
    override fun edit(): MutableEntity = MutableGridData(this)

    enum class Direction(val title: String) {
        ColumnsThenRows("Columns then Rows"),
        RowsThenColumns("Rows then Columns")
    }

    override fun open(): Model.Entity = Grid(
        title, description, position, rotation, scale, rows, columns, rowGap, columnGap, direction, zigZag, id
    )
}

@Serializable
data class SegmentData(
    val startVertex: Vector3F,
    val endVertex: Vector3F,
    val pixelCount: Int
)

@Serializable @SerialName("LightRing")
data class LightRingData(
    override val title: String,
    override val description: String? = null,
    override val position: Vector3F = Vector3F.origin,
    override val rotation: EulerAngle = EulerAngle.identity,
    override val scale: Vector3F = Vector3F.unit3d,
    @Transient override val id: EntityId = Model.Entity.nextId(),
    val radius: Float = 1f,
    val firstPixelRadians: Float = 0f,
    val pixelDirection: LightRing.PixelDirection = LightRing.PixelDirection.Clockwise
) : EntityData {
    override fun edit(): MutableEntity =
        MutableLightRingData(this)

    override fun open(): Model.Entity =
        LightRing(
            title, description, position, rotation, scale, radius, firstPixelRadians,
            pixelDirection, id
        )
}

/** For use only in tests for now! */
@Serializable @SerialName("SurfaceDataForTest")
data class SurfaceDataForTest(
    override val title: String,
    override val description: String? = null,
    override val position: Vector3F = Vector3F.origin,
    override val rotation: EulerAngle = EulerAngle.identity,
    override val scale: Vector3F = Vector3F.unit3d,
    @Transient override val id: EntityId = Model.Entity.nextId(),
    val expectedPixelCount: Int? = null,
    val vertices: List<Vector3F> = emptyList()
) : EntityData {
    override fun edit(): MutableEntity =
        TODO("MutableSurfaceDataForTest not implemented yet!")

    override fun open(): Model.Entity =
        Model.Surface(
            title, description, expectedPixelCount, emptyList(), emptyList(), Model.Geometry(vertices),
            position, rotation, scale
        )
}

@Polymorphic
sealed interface EntityMetadataProvider {
    fun getMetadataFor(entity: EntityData): EntityMetadata
    fun getMetadataFor(entity: Model.Entity): EntityMetadata
}

@Serializable
class ConstEntityMetadataProvider(val pixelCount: Int?): EntityMetadataProvider {
    override fun getMetadataFor(entity: EntityData): EntityMetadata =
        object : EntityMetadata {
            override val expectedPixelCount: Int? get() = pixelCount
        }

    override fun getMetadataFor(entity: Model.Entity): EntityMetadata =
        object : EntityMetadata {
            override val expectedPixelCount: Int? get() = pixelCount
        }
}

@Serializable
class StrandCountEntityMetadataProvider(
    val data: Map<String, Int>
) : EntityMetadataProvider {
    override fun getMetadataFor(entity: EntityData): EntityMetadata =
        object : EntityMetadata {
            override val expectedPixelCount: Int?
                get() = data[entity.title]
        }

    override fun getMetadataFor(entity: Model.Entity): EntityMetadata =
        object : EntityMetadata {
            override val expectedPixelCount: Int?
                get() = data[entity.title]
        }

    companion object {
        suspend fun open(file: Fs.File): StrandCountEntityMetadataProvider {
            return open(file.read() ?: error("Unknown file $file."))
        }

        fun openResource(name: String): StrandCountEntityMetadataProvider {
            return open(getResource(name))
        }

        fun open(data: String): StrandCountEntityMetadataProvider {
            return data.split("\n")
                .map { it.split(Regex("\\s+")) }
                .associate { it[0] to it[1].toInt() * 60 }
                .let { StrandCountEntityMetadataProvider(it) }
        }
    }
}

interface EntityMetadata {
    val expectedPixelCount: Int?
}