//MagnifierActivity.kt

package com.t4paN.AVA

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * MagnifierActivity - Digital magnifying glass for low-vision users
 * 
 * Uses OpenGL ES 2.0 shaders for real-time image processing:
 * - Desaturation (grayscale)
 * - Contrast boost
 * - Sharpening via convolution kernel
 */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class MagnifierActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MagnifierActivity"

        // Tunables - adjust after real-world testing
        // TODO: Add slider or settings menu for zoom
        private const val ZOOM_RATIO = 5.0f
        private const val CONTRAST = 3.0f            // 1.0 = normal, >1 = more contrast
        private const val SATURATION = 0.0f          // 0 = grayscale, 1 = normal color
        private const val SHARPNESS = 1.5f           // 0 = none, 1 = normal, 2 = strong
        private const val EXIT_BUTTON_SIZE_DP = 108
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var exitButton: View

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var renderer: CameraRenderer? = null
    
    // Aspect ratios for center crop calculation
    private var cameraAspectRatio = 16f / 9f  // default, updated when camera binds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setupUI()
        setupBackHandler()
    }

    private fun setupUI() {
        val density = resources.displayMetrics.density
        val exitSizePx = (EXIT_BUTTON_SIZE_DP * density).toInt()
        val marginPx = (16 * density).toInt()

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // GLSurfaceView for camera with shaders
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            renderer = CameraRenderer { surfaceTexture ->
                // Called when GL surface is ready - start camera
                runOnUiThread {
                    startCamera(surfaceTexture)
                }
            }
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        rootLayout.addView(glSurfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Red exit button - top right
        exitButton = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFFCC0000.toInt())
                cornerRadius = 16 * density
            }
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    exitMagnifier()
                }
                true
            }
        }

        val exitParams = FrameLayout.LayoutParams(exitSizePx, exitSizePx).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = marginPx
            topMargin = marginPx
        }
        rootLayout.addView(exitButton, exitParams)

        setContentView(rootLayout)
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitMagnifier()
            }
        })
    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(surfaceTexture)
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider failed", e)
                exitMagnifier()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(surfaceTexture: SurfaceTexture) {
        val provider = cameraProvider ?: return

        val cameraSelector = findMacroCameraSelector(provider) ?: CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder()
            .build()

        preview.setSurfaceProvider { request ->
            val resolution = request.resolution
            cameraAspectRatio = resolution.width.toFloat() / resolution.height.toFloat()
            Log.d(TAG, "Camera resolution: ${resolution.width}x${resolution.height}, aspect: $cameraAspectRatio")
            
            surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
            val surface = Surface(surfaceTexture)
            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { result ->
                surface.release()
            }
            
            // Update renderer with camera aspect ratio
            renderer?.setCameraAspectRatio(cameraAspectRatio)
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, cameraSelector, preview)

            // Enable torch
            camera?.cameraControl?.enableTorch(true)

            // Set zoom
            val zoomState = camera?.cameraInfo?.zoomState?.value
            if (zoomState != null) {
                val actualZoom = ZOOM_RATIO.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(actualZoom)
                Log.d(TAG, "Zoom set to $actualZoom")
            }

            Log.d(TAG, "Camera bound with OpenGL rendering")

        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            exitMagnifier()
        }
    }

    private fun findMacroCameraSelector(provider: ProcessCameraProvider): CameraSelector? {
        val availableCameras = provider.availableCameraInfos

        Log.d(TAG, "Found ${availableCameras.size} cameras")

        var bestMacroCamera: CameraInfo? = null
        var highestFocusDistance = 0f

        for (cameraInfo in availableCameras) {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val lensFacing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)

            val minFocusDistance = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f

            val focalLengths = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: floatArrayOf()

            val cameraId = camera2Info.cameraId
            val facingStr = when (lensFacing) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                else -> "EXTERNAL"
            }
            Log.d(TAG, "Camera $cameraId ($facingStr): minFocusDist=$minFocusDistance, focalLengths=${focalLengths.contentToString()}")

            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) continue

            if (minFocusDistance > 5f && minFocusDistance > highestFocusDistance) {
                highestFocusDistance = minFocusDistance
                bestMacroCamera = cameraInfo
                Log.d(TAG, "New best macro candidate: camera $cameraId with minFocusDist=$minFocusDistance")
            }
        }

        return bestMacroCamera?.cameraSelector?.also {
            Log.d(TAG, "Selected macro camera with focus distance: $highestFocusDistance diopters")
        }
    }

    private fun exitMagnifier() {
        Log.d(TAG, "Exiting magnifier")
        finish()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera", e)
        }
        Log.d(TAG, "MagnifierActivity destroyed")
    }

    /**
     * OpenGL renderer that applies sharpening, contrast, and desaturation shaders
     */
    private inner class CameraRenderer(
        private val onSurfaceReady: (SurfaceTexture) -> Unit
    ) : GLSurfaceView.Renderer {

        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var program = 0
        
        // Aspect ratio handling
        private var cameraAspect = 16f / 9f
        private var surfaceWidth = 1920
        private var surfaceHeight = 1080

        // Vertex coordinates (full screen quad)
        private val vertexCoords = floatArrayOf(
            -1f, -1f,  // bottom left
             1f, -1f,  // bottom right
            -1f,  1f,  // top left
             1f,  1f   // top right
        )

        // Texture coordinates - will be adjusted for center crop
        private var texCoords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var texCoordBuffer: FloatBuffer

        private val transformMatrix = FloatArray(16)
        
        fun setCameraAspectRatio(aspect: Float) {
            cameraAspect = aspect
            updateTexCoords()
        }
        
        private fun updateTexCoords() {
            val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
            
            // Calculate center crop offsets
            var left = 0f
            var right = 1f
            var bottom = 0f
            var top = 1f
            
            if (cameraAspect > surfaceAspect) {
                // Camera is wider than surface - crop sides
                val scale = surfaceAspect / cameraAspect
                val offset = (1f - scale) / 2f
                left = offset
                right = 1f - offset
            } else {
                // Camera is taller than surface - crop top/bottom
                val scale = cameraAspect / surfaceAspect
                val offset = (1f - scale) / 2f
                bottom = offset
                top = 1f - offset
            }
            
            texCoords = floatArrayOf(
                left, bottom,
                right, bottom,
                left, top,
                right, top
            )
            
            // Update buffer if already initialized
            if (::texCoordBuffer.isInitialized) {
                texCoordBuffer.clear()
                texCoordBuffer.put(texCoords)
                texCoordBuffer.position(0)
            }
            
            Log.d(TAG, "TexCoords updated: surface=${surfaceWidth}x${surfaceHeight} (${surfaceAspect}), camera=$cameraAspect, crop=[$left,$bottom]-[$right,$top]")
        }

        // Shader with sharpening, contrast, and desaturation
        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uTexMatrix;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexelSize;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uSharpness;
            
            void main() {
                // Sample center and neighbors for sharpening
                vec4 center = texture2D(uTexture, vTexCoord);
                
                // Sharpen using unsharp mask (3x3 kernel)
                vec4 blur = vec4(0.0);
                blur += texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, -uTexelSize.y));
                blur += texture2D(uTexture, vTexCoord + vec2( 0.0,          -uTexelSize.y));
                blur += texture2D(uTexture, vTexCoord + vec2( uTexelSize.x, -uTexelSize.y));
                blur += texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x,  0.0));
                blur += texture2D(uTexture, vTexCoord + vec2( uTexelSize.x,  0.0));
                blur += texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x,  uTexelSize.y));
                blur += texture2D(uTexture, vTexCoord + vec2( 0.0,           uTexelSize.y));
                blur += texture2D(uTexture, vTexCoord + vec2( uTexelSize.x,  uTexelSize.y));
                blur /= 8.0;
                
                // Unsharp mask: sharpened = center + (center - blur) * amount
                vec4 sharpened = center + (center - blur) * uSharpness;
                
                // Desaturation
                float gray = dot(sharpened.rgb, vec3(0.299, 0.587, 0.114));
                vec3 desaturated = mix(vec3(gray), sharpened.rgb, uSaturation);
                
                // Contrast: (color - 0.5) * contrast + 0.5
                vec3 contrasted = (desaturated - 0.5) * uContrast + 0.5;
                
                gl_FragColor = vec4(clamp(contrasted, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            // Create texture for camera
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Create SurfaceTexture
            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener {
                    glSurfaceView.requestRender()
                }
            }

            // Compile shaders
            program = createProgram(vertexShaderCode, fragmentShaderCode)

            // Setup vertex buffers
            vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertexCoords)
            vertexBuffer.position(0)

            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(texCoords)
            texCoordBuffer.position(0)

            // Notify that surface is ready
            onSurfaceReady(surfaceTexture!!)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            surfaceWidth = width
            surfaceHeight = height
            updateTexCoords()
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val st = surfaceTexture ?: return

            try {
                st.updateTexImage()
                st.getTransformMatrix(transformMatrix)
            } catch (e: Exception) {
                return
            }

            GLES20.glUseProgram(program)

            // Vertex position
            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            // Texture coordinates
            val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            // Texture matrix
            val texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, transformMatrix, 0)

            // Texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
            GLES20.glUniform1i(textureHandle, 0)

            // Texel size for sharpening kernel (approximate based on 1080p)
            val texelSizeHandle = GLES20.glGetUniformLocation(program, "uTexelSize")
            GLES20.glUniform2f(texelSizeHandle, 1f / 1920f, 1f / 1080f)

            // Filter uniforms
            val contrastHandle = GLES20.glGetUniformLocation(program, "uContrast")
            GLES20.glUniform1f(contrastHandle, CONTRAST)

            val saturationHandle = GLES20.glGetUniformLocation(program, "uSaturation")
            GLES20.glUniform1f(saturationHandle, SATURATION)

            val sharpnessHandle = GLES20.glGetUniformLocation(program, "uSharpness")
            GLES20.glUniform1f(sharpnessHandle, SHARPNESS)

            // Draw
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(texHandle)
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                return 0
            }

            return program
        }

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }

            return shader
        }
    }
}
