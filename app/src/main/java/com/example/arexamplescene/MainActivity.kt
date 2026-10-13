package com.example.arexamplescene

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.arexamplescene.ui.theme.ArExampleSceneTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.isTracking
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView

/** How far in front of the camera (metres) the selected object floats. */
private const val FOLLOW_DISTANCE_M = 0.6f

/** Light blue (#87CEFA) used by the "selected" material. */
private val SELECTED_COLOR = floatArrayOf(0.53f, 0.81f, 0.98f)

/** Original glTF base colour factor — white means "as authored". */
private val DEFAULT_COLOR = floatArrayOf(1f, 1f, 1f)

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArExampleSceneTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val cameraPermissionState =
                        rememberPermissionState(Manifest.permission.CAMERA)

                    LaunchedEffect(Unit) {
                        cameraPermissionState.launchPermissionRequest()
                    }

                    if (cameraPermissionState.status.isGranted) {
                        ARScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun ARScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val placedAnchors = remember { mutableStateListOf<Anchor>() }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // The node currently attached to the camera (null = nothing selected).
    var selectedNode by remember { mutableStateOf<Node?>(null) }
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        collisionSystem = collisionSystem,
        planeRenderer = true,
        sessionConfiguration = { _, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.depthMode = Config.DepthMode.AUTOMATIC
        },
        onSessionUpdated = { _, frame ->
            latestFrame = frame

            // ---- Keep the selected object glued in front of the camera ----
            selectedNode?.let { node ->
                val cameraPose = frame.camera.pose
                val translation = cameraPose.translation
                // ARCore cameras look down their own -Z axis.
                val forward = cameraPose.rotateVector(floatArrayOf(0f, 0f, -1f))

                node.worldPosition = Position(
                    translation[0] + forward[0] * FOLLOW_DISTANCE_M,
                    translation[1] + forward[1] * FOLLOW_DISTANCE_M,
                    translation[2] + forward[2] * FOLLOW_DISTANCE_M
                )
            }
        },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, node ->
                if (node != null) {
                    // Tap on an object → select it (tap again to release it).
                    selectedNode = if (selectedNode === node) null else node
                } else {
                    // Tap on empty space → place a new object on the floor.
                    val frame = latestFrame ?: return@rememberOnGestureListener

                    val hit = frame.hitTest(event).firstOrNull { result ->
                        val trackable = result.trackable
                        trackable.isTracking &&
                                trackable is Plane &&
                                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                trackable.isPoseInPolygon(result.hitPose)
                    }

                    hit?.createAnchor()?.let { placedAnchors.add(it) }
                }
            }
        )
    ) {
        placedAnchors.forEach { anchor ->
            key(anchor) {
                AnchorNode(anchor = anchor) {
                    val cubeInstance = rememberModelInstance(modelLoader, "models/cube.glb")
                    cubeInstance?.let { instance ->
                        var modelNode by remember { mutableStateOf<Node?>(null) }
                        val isSelected = modelNode != null && selectedNode === modelNode

                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = .1f,
                            position = Position(0f, 1f, 0f),
                            apply = { modelNode = this },
                            isEditable = true,
                        )

                        // ---- Swap the material colour while selected ----------
                        LaunchedEffect(instance, isSelected) {
                            val color = if (isSelected) SELECTED_COLOR else DEFAULT_COLOR
                            instance.materialInstances.forEach { materialInstance ->
                                val material = materialInstance.material
                                // glTF PBR materials expose their colour as
                                // `baseColorFactor`; skip materials that don't.
                                if (material.hasParameter("baseColorFactor")) {
                                    materialInstance.setParameter(
                                        "baseColorFactor",
                                        color[0], color[1], color[2], 1f
                                    )
                                }
                            }
                        }

                        if (modelNode != null && !isSelected) {
                            PhysicsNode(
                                node = modelNode!!,
                                restitution = .7f,
                                linearVelocity = Position(0f, 0f, 0f),
                                floorY = 0f,
                                radius = 0.1f,
                            )
                        }
                    }
                }
            }
        }
    }
}