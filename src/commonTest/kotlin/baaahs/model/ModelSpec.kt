package baaahs.model

import baaahs.describe
import baaahs.fakeModel
import baaahs.geom.Matrix4F
import baaahs.geom.Vector3F
import baaahs.gl.override
import baaahs.toEqual
import ch.tutteli.atrium.api.verbs.expect
import org.spekframework.spek2.Spek

object ModelSpec : Spek({
    describe<Model> {
        context("model bounds") {
            val v1 by value { Vector3F(1f, 0f, 5f) }
            val v2 by value { Vector3F(-1f, 1f, 0f) }
            val v3 by value { Vector3F(0f, 1f, -.25f) }

            val transformation by value { Matrix4F.identity }
            val model by value {
                val geometry = Model.Geometry(listOf(v1, v2, v3))
                fakeModel(
                    Model.Surface(
                        "triangle", "triangle", null,
                        listOf(Model.Face(geometry, 0, 1, 2)),
                        listOf(
                            Model.Line(geometry, 0, 1),
                            Model.Line(geometry, 1, 2),
                            Model.Line(geometry, 2, 0)
                        ),
                        geometry,
                        transformation
                    )
                )
            }

            it("should include all points defining a surface within modelBounds") {
                expect(model.modelBounds).toEqual(
                    Vector3F(-1f, 0f, -.25f) to Vector3F(1f, 1f, 5f)
                )
            }

            it("should compute the correct center") {
                expect(model.center).toEqual(
                    Vector3F(0f, .5f, 2.375f)
                )
            }

            context("with a transformation") {
                value(transformation) { Matrix4F.identity.translate(Vector3F.unit3d) }

                it("should include all points defining a surface within modelBounds") {
                    expect(model.modelBounds).toEqual(
                        Vector3F(0f, 1f, .75f) to Vector3F(2f, 2f, 6f)
                    )
                }

                it("should compute the correct center") {
                    expect(model.center).toEqual(
                        Vector3F(1f, 1.5f, 3.375f)
                    )
                }
            }

            context("with light bars") {
                val v4 by value { Vector3F(7f, -3f, 5.25f) }

                override(model) {
                    fakeModel(
                        LightBar("bar1", "bar2", v1, v2),
                        LightBar("bar2", "bar2", v3, v4)
                    )
                }

                it("should include start and end points in a light bar") {
                    expect(model.modelBounds).toEqual(
                        Vector3F(-1f, -3f, -.25f) to Vector3F(7f, 1f, 5.25f)
                    )
                }

                it("should compute the correct center") {
                    expect(model.center).toEqual(
                        Vector3F(3f, -1f, 2.5f)
                    )
                }
            }

            context("with light rings") {
                override(model) {
                    fakeModel(
                        LightRing("bar1", "bar2", v1, 1f, Vector3F.facingForward),
                        LightRing("bar2", "bar2", v2, 1f, Vector3F.facingForward)
                    )
                }

                it("should include all points on both light rings") {
                    expect(model.modelBounds).toEqual(
                        Vector3F(-2f, -1f, 0f) to Vector3F(2f, 2f, 5f)
                    )
                }

                it("should compute the correct center") {
                    expect(model.center).toEqual(
                        Vector3F(0f, .5f, 2.5f)
                    )
                }
            }
        }
    }
})