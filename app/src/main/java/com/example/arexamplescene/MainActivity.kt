package com.example.arexamplescene

import android.Manifest
import android.os.Bundle
import android.view.MotionEvent
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
import io.github.sceneview.SceneView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import io.github.sceneview.model.engine
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import io.github.sceneview.rememberOnGestureListener

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
    var physicsTargetNode by remember { mutableStateOf<Node?>(null) }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,                // show detected planes
        sessionConfiguration = { _, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.depthMode = Config.DepthMode.AUTOMATIC
        },
        onSessionUpdated = { _, frame ->
            latestFrame = frame
        },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, _ ->
                val frame = latestFrame ?: return@rememberOnGestureListener

                // Hit-test against a horizontal plane inside its polygon
                val hit = frame.hitTest(event).firstOrNull { result ->
                    val trackable = result.trackable
                    trackable is Plane &&
                            trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                            trackable.isPoseInPolygon(result.hitPose)
                }

                hit?.createAnchor()?.let { placedAnchors.add(it) }
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
                }
            }
        }
    }
}