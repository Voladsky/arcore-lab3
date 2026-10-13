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
import androidx.compose.ui.geometry.Offset
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
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlin.math.hypot

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

/** Touch slop in pixels — below this we treat the gesture as a tap. */
private const val DRAG_SLOP_PX = 12f

@Composable
fun ARScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val placedAnchors = remember { mutableStateListOf<Anchor>() }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // ---- Drag state -------------------------------------------------------
    var draggedNode by remember { mutableStateOf<Node?>(null) }
    var dragStartTouch by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    var suppressNextTap by remember { mutableStateOf(false) }
    // -----------------------------------------------------------------------

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        sessionConfiguration = { _, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.depthMode = Config.DepthMode.AUTOMATIC
        },
        onSessionUpdated = { _, frame ->
            latestFrame = frame
        },
        onGestureListener = rememberOnGestureListener(
            onDown = { event, node ->
                draggedNode = node?.takeIf { it.isEditable }
                dragStartTouch = Offset(event.x, event.y)
                isDragging = false
            },

            onMove = { _, event, _ ->
                val node = draggedNode ?: return@rememberOnGestureListener
                val frame = latestFrame ?: return@rememberOnGestureListener

                if (!isDragging) {
                    val dx = event.x - dragStartTouch.x
                    val dy = event.y - dragStartTouch.y
                    if (hypot(dx, dy) < DRAG_SLOP_PX) {
                        return@rememberOnGestureListener
                    }
                    isDragging = true
                    suppressNextTap = true   // <-- arm the suppressor
                }

                frame.hitTest(event)
                    .firstOrNull { it.trackable.isTracking }
                    ?.let { hit ->
                        val pose = hit.hitPose
                        node.worldPosition = Position(pose.tx(), pose.ty() + 0.01f, pose.tz())
                    }
            },

            onSingleTapConfirmed = { event, node ->
                // 1) If we just finished a drag, swallow this tap.
                if (suppressNextTap) {
                    suppressNextTap = false
                    draggedNode = null
                    isDragging = false
                    return@rememberOnGestureListener
                }

                // 2) Only spawn when the tap is on empty space.
                if (node == null) {
                    val frame = latestFrame ?: return@rememberOnGestureListener

                    val hit = frame.hitTest(event).firstOrNull { result ->
                        val trackable = result.trackable
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
                    cubeInstance?.let {
                        var modelNode by remember { mutableStateOf<Node?>(null) }
                        ModelNode(
                            modelInstance = it,
                            scaleToUnits = .1f,
                            position = Position(0f, 1f, 0f),
                            apply = { modelNode = this },
                            isEditable = true
                        )
                        if (draggedNode != modelNode) {
                            modelNode?.let { node ->
                                this@AnchorNode.PhysicsNode(
                                    node = node,
                                    restitution = .7f,
                                    linearVelocity = Position(0f, 0f, 0f),
                                    floorY = 0f,
                                    radius = 0.1f
                                )
                            }
                        }
                        // If you add a PhysicsNode here, disable/remove it while
                        // `draggedNode == modelNode`, otherwise the physics body
                        // will fight the drag and snap the model back every frame.
                    }
                }
            }
        }
    }
}