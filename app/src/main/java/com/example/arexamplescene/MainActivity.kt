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
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
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
    val cubeInstance = rememberModelInstance(modelLoader, "models/cube.glb")

    val placedAnchors = remember { mutableStateListOf<Anchor>() }

    // Hold the tap event until the next onSessionUpdated, where we hit-test with a fresh frame.
    var pendingTap by remember { mutableStateOf<MotionEvent?>(null) }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        cameraNode = rememberARCameraNode(engine),
        planeRenderer = true,
        sessionConfiguration = { session, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.focusMode = Config.FocusMode.AUTO
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

            // Only enable depth when the session truly reports support.
            config.depthMode =
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }

            // UpdateMode.BLOCKING intentionally NOT set — it's deprecated and
            // causes stale frames + IMU/pose-pipeline stalls.
        },

        onSessionUpdated = { _, frame ->
            // Consume any pending tap NOW, while the frame is guaranteed fresh.
            val tapEvent = pendingTap ?: return@ARSceneView
            pendingTap = null

            try {
                if (frame.camera.trackingState != TrackingState.TRACKING) return@ARSceneView

                val hit = frame.hitTest(tapEvent)
                    .firstOrNull { result ->
                        val trackable = result.trackable
                        trackable is Plane &&
                                trackable.trackingState == TrackingState.TRACKING &&
                                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                trackable.isPoseInPolygon(result.hitPose)
                    }

                hit?.let { placedAnchors.add(it.createAnchor()) }
            } finally {
                tapEvent.recycle()
            }
        },

        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, _ ->
                // Do NOT hit-test here. Just stash a copy of the event.
                pendingTap?.recycle()
                pendingTap = MotionEvent.obtain(event)
            }
        )
    ) {
        placedAnchors.forEach { anchor ->
            key(anchor) {
                AnchorNode(anchor = anchor) {
                    cubeInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.2f,
                            position = Position(0f, 0.1f, 0f),
                            isEditable = true
                        )
                    }
                }
            }
        }
    }
}