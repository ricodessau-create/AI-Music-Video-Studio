package com.example.aivideostudio.render

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class VideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    private val bitrate: Int = 8_000_000
) {

    companion object {
        private const val TAG = "AI_MUSIC_VIDEO_RENDER"
        private const val INPUT_TIMEOUT_US = 250_000L
        private const val MAX_EOS_WAIT_ATTEMPTS = 120
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null

    private var trackIndex = -1
    private var muxerStarted = false
    private var started = false

    private var frameCount = 0L
    private var writtenSampleCount = 0L
    private var lastPresentationTimeUs = -1L

    private var selectedCodecName = "unknown"

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var textureId = 0
    private var shaderProgram = 0

    private var positionHandle = -1
    private var texCoordHandle = -1
    private var textureHandle = -1

    private val bufferInfo = MediaCodec.BufferInfo()

    private val vertexBuffer: FloatBuffer =
        ByteBuffer
            .allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        -1f, -1f,
                        1f, -1f,
                        -1f, 1f,
                        1f, 1f
                    )
                )
                position(0)
            }

    private val textureBuffer: FloatBuffer =
        ByteBuffer
            .allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        0f, 1f,
                        1f, 1f,
                        0f, 0f,
                        1f, 0f
                    )
                )
                position(0)
            }

    private fun log(message: String) {
        val fullMessage = "[VideoEncoder] $message"

        Log.d(TAG, fullMessage)

        try {
            val directory =
                outputFile.parentFile?.resolve("render_diagnostics")

            if (directory != null) {
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                directory
                    .resolve("video_encoder_diagnostics.txt")
                    .appendText(fullMessage + "\n")
            }
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Konnte Diagnose-Logdatei nicht schreiben.",
                exception
            )
        }
    }

    private fun logError(
        message: String,
        throwable: Throwable? = null
    ) {
        val fullMessage = "[VideoEncoder][ERROR] $message"

        Log.e(TAG, fullMessage, throwable)

        try {
            val directory =
                outputFile.parentFile?.resolve("render_diagnostics")

            if (directory != null) {
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                val file =
                    directory.resolve(
                        "video_encoder_diagnostics.txt"
                    )

                file.appendText(fullMessage + "\n")

                if (throwable != null) {
                    file.appendText(
                        throwable.stackTraceToString() + "\n"
                    )
                }
            }
        } catch (loggingException: Exception) {
            Log.e(
                TAG,
                "Konnte Fehlerdiagnose nicht schreiben.",
                loggingException
            )
        }
    }

    fun start() {
        check(!started) {
            "VideoEncoder wurde bereits gestartet."
        }

        require(width > 0) {
            "Ungültige Videobreite: $width"
        }

        require(height > 0) {
            "Ungültige Videohöhe: $height"
        }

        require(frameRate > 0) {
            "Ungültige Framerate: $frameRate"
        }

        require(bitrate > 0) {
            "Ungültige Bitrate: $bitrate"
        }

        outputFile.parentFile?.mkdirs()

        log("==================================================")
        log("VIDEO ENCODER START - SURFACE/EGL MODE")
        log("==================================================")
        log("Android SDK=${Build.VERSION.SDK_INT}")
        log("Android Release=${Build.VERSION.RELEASE}")
        log("Manufacturer=${Build.MANUFACTURER}")
        log("Model=${Build.MODEL}")
        log("Device=${Build.DEVICE}")
        log("Product=${Build.PRODUCT}")
        log("Board=${Build.BOARD}")
        log("ABI=${Build.SUPPORTED_ABIS.joinToString()}")
        log("Output=${outputFile.absolutePath}")
        log("Resolution=${width}x$height")
        log("FrameRate=$frameRate")
        log("Bitrate=$bitrate")

        val codecName =
            findSurfaceEncoderCodec()

        selectedCodecName = codecName

        log("Selected AVC surface codec=$selectedCodecName")

        val codec =
            try {
                MediaCodec.createByCodecName(codecName)
            } catch (exception: Exception) {
                logError(
                    "MediaCodec.createByCodecName() fehlgeschlagen.",
                    exception
                )
                throw exception
            }

        try {
            val format =
                MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    width,
                    height
                )

            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )

            format.setInteger(
                MediaFormat.KEY_BIT_RATE,
                bitrate
            )

            format.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                frameRate
            )

            format.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                2
            )

            log("MIME=${MediaFormat.MIMETYPE_VIDEO_AVC}")
            log(
                "COLOR_FORMAT=" +
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface +
                    " (COLOR_FormatSurface)"
            )
            log("BIT_RATE=$bitrate")
            log("FRAME_RATE=$frameRate")
            log("I_FRAME_INTERVAL=2")

            codec.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            log("codec.configure() erfolgreich.")

            inputSurface =
                codec.createInputSurface()

            log(
                "MediaCodec Input Surface erfolgreich erstellt."
            )

            muxer =
                MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

            log("MediaMuxer erfolgreich erstellt.")

            encoder = codec

            codec.start()

            log("codec.start() erfolgreich.")

            initializeEgl()

            log("EGL/OpenGL erfolgreich initialisiert.")

            started = true
            frameCount = 0L
            writtenSampleCount = 0L
            lastPresentationTimeUs = -1L

            log("Encoder vollständig gestartet.")
        } catch (exception: Exception) {
            logError(
                "Fehler während configure/start/EGL.",
                exception
            )

            release()

            throw exception
        }
    }

    fun encodeFrame(
        bitmap: Bitmap,
        presentationTimeUs: Long
    ) {
        check(started) {
            "VideoEncoder wurde noch nicht gestartet."
        }

        require(!bitmap.isRecycled) {
            "Das übergebene Bitmap wurde bereits recycelt."
        }

        require(bitmap.width == width) {
            "Bitmap-Breite ${bitmap.width} entspricht nicht der Encoder-Breite $width."
        }

        require(bitmap.height == height) {
            "Bitmap-Höhe ${bitmap.height} entspricht nicht der Encoder-Höhe $height."
        }

        require(presentationTimeUs >= 0L) {
            "Ungültiger Presentation Timestamp: $presentationTimeUs"
        }

        require(
            presentationTimeUs > lastPresentationTimeUs
        ) {
            "Presentation Timestamp muss monoton steigen. " +
                "Vorher=$lastPresentationTimeUs, " +
                "aktuell=$presentationTimeUs"
        }

        val currentFrame =
            frameCount + 1L

        log(
            "--------------------------------------------------"
        )
        log("FRAME #$currentFrame")
        log("presentationTimeUs=$presentationTimeUs")
        log("bitmap=${bitmap.width}x${bitmap.height}")
        log("bitmapConfig=${bitmap.config}")

        try {
            renderBitmapToSurface(
                bitmap = bitmap,
                presentationTimeUs = presentationTimeUs
            )
        } catch (exception: Exception) {
            logError(
                "EGL/OpenGL Rendering für Frame #$currentFrame fehlgeschlagen.",
                exception
            )
            throw exception
        }

        frameCount++
        lastPresentationTimeUs = presentationTimeUs

        try {
            drainEncoder(false)
        } catch (exception: Exception) {
            logError(
                "drainEncoder() nach Frame #$currentFrame fehlgeschlagen.",
                exception
            )
            throw exception
        }

        log(
            "Frame #$currentFrame abgeschlossen. " +
                "writtenSamples=$writtenSampleCount"
        )
    }

    fun finish() {
        if (!started) {
            log("finish(): Encoder war nicht gestartet.")
            return
        }

        log("==================================================")
        log("VIDEO ENCODER FINISH")
        log("Frames verarbeitet=$frameCount")
        log("Samples geschrieben=$writtenSampleCount")

        val codec =
            encoder
                ?: throw IllegalStateException(
                    "Encoder ist nicht verfügbar."
                )

        try {
            log(
                "signalEndOfInputStream() wird aufgerufen."
            )

            codec.signalEndOfInputStream()

            log(
                "signalEndOfInputStream() erfolgreich."
            )

            var eosReached = false
            var drainPasses = 0

            while (!eosReached) {
                drainPasses++

                if (drainPasses > MAX_EOS_WAIT_ATTEMPTS) {
                    throw IllegalStateException(
                        "EOS wurde nach " +
                            "$MAX_EOS_WAIT_ATTEMPTS " +
                            "Drain-Versuchen nicht erreicht."
                    )
                }

                log(
                    "EOS drain pass #$drainPasses"
                )

                eosReached =
                    drainEncoder(true)
            }

            log(
                "End-of-stream erfolgreich erreicht."
            )

            if (!muxerStarted) {
                throw IllegalStateException(
                    "Der MP4-Muxer wurde nie gestartet. " +
                        "Es wurde kein gültiger Video-Track erzeugt."
                )
            }

            if (writtenSampleCount <= 0L) {
                throw IllegalStateException(
                    "Der Encoder hat keine Videoframes " +
                        "in die MP4-Datei geschrieben."
                )
            }

            if (
                !outputFile.exists() ||
                outputFile.length() <= 1024L
            ) {
                throw IllegalStateException(
                    "Die erzeugte MP4-Datei ist ungültig oder leer: " +
                        outputFile.absolutePath
                )
            }

            log("MP4 erfolgreich erzeugt.")
            log("MP4 size=${outputFile.length()} bytes")
            log("Final frames=$frameCount")
            log("Final samples=$writtenSampleCount")
        } catch (exception: Exception) {
            logError(
                "Fehler während finish().",
                exception
            )
            throw exception
        } finally {
            release()
        }
    }

    private fun drainEncoder(
        endOfStream: Boolean
    ): Boolean {
        val codec =
            encoder
                ?: return false

        var eosReached = false

        val timeoutUs =
            if (endOfStream) {
                INPUT_TIMEOUT_US
            } else {
                0L
            }

        while (true) {
            val outputIndex =
                try {
                    codec.dequeueOutputBuffer(
                        bufferInfo,
                        timeoutUs
                    )
                } catch (exception: Exception) {
                    logError(
                        "dequeueOutputBuffer() fehlgeschlagen.",
                        exception
                    )
                    throw exception
                }

            when {
                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return eosReached
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    log(
                        "INFO_OUTPUT_FORMAT_CHANGED"
                    )

                    if (muxerStarted) {
                        throw IllegalStateException(
                            "Das Encoder-Ausgabeformat wurde mehrfach geändert."
                        )
                    }

                    val outputFormat =
                        codec.outputFormat

                    log(
                        "Encoder outputFormat=$outputFormat"
                    )

                    val currentMuxer =
                        muxer
                            ?: throw IllegalStateException(
                                "Muxer ist nicht verfügbar."
                            )

                    trackIndex =
                        currentMuxer.addTrack(
                            outputFormat
                        )

                    log(
                        "Muxer trackIndex=$trackIndex hinzugefügt."
                    )

                    currentMuxer.start()
                    muxerStarted = true

                    log("Muxer gestartet.")
                }

                outputIndex >= 0 -> {
                    val encodedData =
                        codec.getOutputBuffer(
                            outputIndex
                        )

                    log(
                        "OutputBuffer #$outputIndex: " +
                            "offset=${bufferInfo.offset}, " +
                            "size=${bufferInfo.size}, " +
                            "pts=${bufferInfo.presentationTimeUs}, " +
                            "flags=${bufferInfo.flags}"
                    )

                    if (
                        encodedData != null &&
                        bufferInfo.size > 0 &&
                        muxerStarted &&
                        trackIndex >= 0
                    ) {
                        encodedData.position(
                            bufferInfo.offset
                        )

                        encodedData.limit(
                            bufferInfo.offset +
                                bufferInfo.size
                        )

                        muxer?.writeSampleData(
                            trackIndex,
                            encodedData,
                            bufferInfo
                        )

                        writtenSampleCount++

                        log(
                            "Output-Sample geschrieben. " +
                                "sampleCount=$writtenSampleCount"
                        )
                    }

                    val isCodecConfig =
                        (
                            bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                            ) != 0

                    val isKeyFrame =
                        (
                            bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            ) != 0

                    val isEndOfStream =
                        (
                            bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            ) != 0

                    log(
                        "Output flags: " +
                            "codecConfig=$isCodecConfig, " +
                            "keyFrame=$isKeyFrame, " +
                            "eos=$isEndOfStream"
                    )

                    codec.releaseOutputBuffer(
                        outputIndex,
                        false
                    )

                    if (isEndOfStream) {
                        eosReached = true

                        log(
                            "BUFFER_FLAG_END_OF_STREAM erreicht."
                        )

                        return true
                    }
                }

                else -> {
                    log(
                        "Unerwarteter dequeueOutputBuffer() Wert: " +
                            "$outputIndex"
                    )

                    return eosReached
                }
            }
        }
    }

    private fun renderBitmapToSurface(
        bitmap: Bitmap,
        presentationTimeUs: Long
    ) {
        check(
            eglDisplay != EGL14.EGL_NO_DISPLAY
        ) {
            "EGL Display ist nicht initialisiert."
        }

        check(
            eglContext != EGL14.EGL_NO_CONTEXT
        ) {
            "EGL Context ist nicht initialisiert."
        }

        check(
            eglSurface != EGL14.EGL_NO_SURFACE
        ) {
            "EGL Surface ist nicht initialisiert."
        }

        check(
            shaderProgram != 0
        ) {
            "OpenGL Shader ist nicht initialisiert."
        }

        check(
            textureId != 0
        ) {
            "OpenGL Texture ist nicht initialisiert."
        }

        if (
            !EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext
            )
        ) {
            throwEglError(
                "eglMakeCurrent() fehlgeschlagen."
            )
        }

        GLES20.glViewport(
            0,
            0,
            width,
            height
        )

        checkGlError(
            "glViewport()"
        )

        GLES20.glUseProgram(
            shaderProgram
        )

        checkGlError(
            "glUseProgram()"
        )

        GLES20.glActiveTexture(
            GLES20.GL_TEXTURE0
        )

        GLES20.glBindTexture(
            GLES20.GL_TEXTURE_2D,
            textureId
        )

        checkGlError(
            "glBindTexture()"
        )

        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLUtils.texImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            bitmap,
            0
        )

        checkGlError(
            "GLUtils.texImage2D()"
        )

        vertexBuffer.position(0)

        GLES20.glEnableVertexAttribArray(
            positionHandle
        )

        GLES20.glVertexAttribPointer(
            positionHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )

        checkGlError(
            "glVertexAttribPointer(position)"
        )

        textureBuffer.position(0)

        GLES20.glEnableVertexAttribArray(
            texCoordHandle
        )

        GLES20.glVertexAttribPointer(
            texCoordHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureBuffer
        )

        checkGlError(
            "glVertexAttribPointer(texture)"
        )

        GLES20.glUniform1i(
            textureHandle,
            0
        )

        GLES20.glClearColor(
            0f,
            0f,
            0f,
            1f
        )

        GLES20.glClear(
            GLES20.GL_COLOR_BUFFER_BIT
        )

        GLES20.glDrawArrays(
            GLES20.GL_TRIANGLE_STRIP,
            0,
            4
        )

        checkGlError(
            "glDrawArrays()"
        )

        GLES20.glDisableVertexAttribArray(
            positionHandle
        )

        GLES20.glDisableVertexAttribArray(
            texCoordHandle
        )

        EGLExt.eglPresentationTimeANDROID(
            eglDisplay,
            eglSurface,
            presentationTimeUs * 1000L
        )

        if (
            !EGL14.eglSwapBuffers(
                eglDisplay,
                eglSurface
            )
        ) {
            throwEglError(
                "eglSwapBuffers() fehlgeschlagen."
            )
        }

        GLES20.glBindTexture(
            GLES20.GL_TEXTURE_2D,
            0
        )
    }

    private fun initializeEgl() {
        val surface =
            inputSurface
                ?: throw IllegalStateException(
                    "MediaCodec Input Surface fehlt."
                )

        eglDisplay =
            EGL14.eglGetDisplay(
                EGL14.EGL_DEFAULT_DISPLAY
            )

        if (
            eglDisplay == EGL14.EGL_NO_DISPLAY
        ) {
            throwEglError(
                "eglGetDisplay() fehlgeschlagen."
            )
        }

        val version =
            IntArray(2)

        if (
            !EGL14.eglInitialize(
                eglDisplay,
                version,
                0,
                version,
                1
            )
        ) {
            throwEglError(
                "eglInitialize() fehlgeschlagen."
            )
        }

        log(
            "EGL version=${version[0]}.${version[1]}"
        )

        val configAttributes =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE
            )

        val configs =
            arrayOfNulls<EGLConfig>(1)

        val numConfigs =
            IntArray(1)

        if (
            !EGL14.eglChooseConfig(
                eglDisplay,
                configAttributes,
                0,
                configs,
                0,
                configs.size,
                numConfigs,
                0
            )
        ) {
            throwEglError(
                "eglChooseConfig() fehlgeschlagen."
            )
        }

        val config =
            configs[0]
                ?: throw IllegalStateException(
                    "Keine passende EGLConfig gefunden."
                )

        val contextAttributes =
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                2,
                EGL14.EGL_NONE
            )

        eglContext =
            EGL14.eglCreateContext(
                eglDisplay,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0
            )

        if (
            eglContext == EGL14.EGL_NO_CONTEXT
        ) {
            throwEglError(
                "eglCreateContext() fehlgeschlagen."
            )
        }

        val surfaceAttributes =
            intArrayOf(
                EGL14.EGL_NONE
            )

        eglSurface =
            EGL14.eglCreateWindowSurface(
                eglDisplay,
                config,
                surface,
                surfaceAttributes,
                0
            )

        if (
            eglSurface == EGL14.EGL_NO_SURFACE
        ) {
            throwEglError(
                "eglCreateWindowSurface() fehlgeschlagen."
            )
        }

        if (
            !EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext
            )
        ) {
            throwEglError(
                "eglMakeCurrent() bei Initialisierung fehlgeschlagen."
            )
        }

        createGlProgram()
        createGlTexture()

        checkGlError(
            "EGL/OpenGL Initialisierung"
        )
    }

    private fun createGlProgram() {
        val vertexShaderSource =
            """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
            """.trimIndent()

        val fragmentShaderSource =
            """
            precision mediump float;

            uniform sampler2D uTexture;
            varying vec2 vTexCoord;

            void main() {
                gl_FragColor = texture2D(
                    uTexture,
                    vTexCoord
                );
            }
            """.trimIndent()

        val vertexShader =
            compileShader(
                GLES20.GL_VERTEX_SHADER,
                vertexShaderSource
            )

        val fragmentShader =
            compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                fragmentShaderSource
            )

        shaderProgram =
            GLES20.glCreateProgram()

        checkGlError(
            "glCreateProgram()"
        )

        GLES20.glAttachShader(
            shaderProgram,
            vertexShader
        )

        GLES20.glAttachShader(
            shaderProgram,
            fragmentShader
        )

        GLES20.glLinkProgram(
            shaderProgram
        )

        val linkStatus =
            IntArray(1)

        GLES20.glGetProgramiv(
            shaderProgram,
            GLES20.GL_LINK_STATUS,
            linkStatus,
            0
        )

        if (
            linkStatus[0] == 0
        ) {
            val info =
                GLES20.glGetProgramInfoLog(
                    shaderProgram
                )

            GLES20.glDeleteProgram(
                shaderProgram
            )

            shaderProgram = 0

            throw IllegalStateException(
                "OpenGL Shader-Linking fehlgeschlagen: $info"
            )
        }

        GLES20.glDeleteShader(
            vertexShader
        )

        GLES20.glDeleteShader(
            fragmentShader
        )

        positionHandle =
            GLES20.glGetAttribLocation(
                shaderProgram,
                "aPosition"
            )

        texCoordHandle =
            GLES20.glGetAttribLocation(
                shaderProgram,
                "aTexCoord"
            )

        textureHandle =
            GLES20.glGetUniformLocation(
                shaderProgram,
                "uTexture"
            )

        if (
            positionHandle < 0 ||
            texCoordHandle < 0 ||
            textureHandle < 0
        ) {
            throw IllegalStateException(
                "OpenGL Shader-Handles konnten nicht gefunden werden."
            )
        }

        log(
            "OpenGL Shader erfolgreich erstellt."
        )
    }

    private fun compileShader(
        type: Int,
        source: String
    ): Int {
        val shader =
            GLES20.glCreateShader(type)

        checkGlError(
            "glCreateShader()"
        )

        GLES20.glShaderSource(
            shader,
            source
        )

        GLES20.glCompileShader(
            shader
        )

        val compileStatus =
            IntArray(1)

        GLES20.glGetShaderiv(
            shader,
            GLES20.GL_COMPILE_STATUS,
            compileStatus,
            0
        )

        if (
            compileStatus[0] == 0
        ) {
            val info =
                GLES20.glGetShaderInfoLog(
                    shader
                )

            GLES20.glDeleteShader(
                shader
            )

            throw IllegalStateException(
                "OpenGL Shader-Kompilierung fehlgeschlagen: $info"
            )
        }

        return shader
    }

    private fun createGlTexture() {
        val textures =
            IntArray(1)

        GLES20.glGenTextures(
            1,
            textures,
            0
        )

        textureId =
            textures[0]

        if (
            textureId == 0
        ) {
            throw IllegalStateException(
                "OpenGL Texture konnte nicht erstellt werden."
            )
        }

        GLES20.glBindTexture(
            GLES20.GL_TEXTURE_2D,
            textureId
        )

        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glBindTexture(
            GLES20.GL_TEXTURE_2D,
            0
        )

        log(
            "OpenGL Texture erstellt: id=$textureId"
        )
    }

    private fun findSurfaceEncoderCodec(): String {
        val codecList =
            MediaCodecList(
                MediaCodecList.ALL_CODECS
            )

        val candidates =
            codecList.codecInfos
                .filter { codecInfo ->
                    if (!codecInfo.isEncoder) {
                        return@filter false
                    }

                    val supportsAvc =
                        codecInfo.supportedTypes.any { type ->
                            type.equals(
                                MediaFormat.MIMETYPE_VIDEO_AVC,
                                ignoreCase = true
                            )
                        }

                    if (!supportsAvc) {
                        return@filter false
                    }

                    val capabilities =
                        try {
                            codecInfo.getCapabilitiesForType(
                                MediaFormat.MIMETYPE_VIDEO_AVC
                            )
                        } catch (_: Exception) {
                            return@filter false
                        }

                    val supportsSurface =
                        capabilities.colorFormats.contains(
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )

                    if (!supportsSurface) {
                        return@filter false
                    }

                    val videoCapabilities =
                        capabilities.videoCapabilities

                    videoCapabilities.isSizeSupported(
                        width,
                        height
                    )
                }
                .sortedBy { codecInfo ->
                    val name =
                        codecInfo.name.lowercase()

                    when {
                        name.contains("google") -> 3
                        name.contains("android") -> 2
                        name.contains("sw") -> 2
                        else -> 0
                    }
                }

        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "Auf diesem Gerät wurde kein AVC-Encoder " +
                    "mit COLOR_FormatSurface für " +
                    "${width}x$height gefunden."
            )
        }

        candidates.forEach { codecInfo ->
            log(
                "Surface-Encoder Kandidat: " +
                    codecInfo.name
            )
        }

        return candidates.first().name
    }

    private fun checkGlError(
        operation: String
    ) {
        val error =
            GLES20.glGetError()

        if (
            error != GLES20.GL_NO_ERROR
        ) {
            throw IllegalStateException(
                "$operation: OpenGL Fehler 0x" +
                    Integer.toHexString(error)
            )
        }
    }

    private fun throwEglError(
        message: String
    ): Nothing {
        val error =
            EGL14.eglGetError()

        throw IllegalStateException(
            "$message EGL Fehler 0x" +
                Integer.toHexString(error)
        )
    }

    private fun release() {
        log("release() gestartet.")

        try {
            if (
                eglDisplay != EGL14.EGL_NO_DISPLAY &&
                eglSurface != EGL14.EGL_NO_SURFACE
            ) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
        } catch (exception: Exception) {
            logError(
                "eglMakeCurrent() beim Release fehlgeschlagen.",
                exception
            )
        }

        if (
            textureId != 0 &&
            eglDisplay != EGL14.EGL_NO_DISPLAY
        ) {
            try {
                GLES20.glDeleteTextures(
                    1,
                    intArrayOf(textureId),
                    0
                )
            } catch (exception: Exception) {
                logError(
                    "OpenGL Texture konnte nicht gelöscht werden.",
                    exception
                )
            }

            textureId = 0
        }

        if (
            shaderProgram != 0 &&
            eglDisplay != EGL14.EGL_NO_DISPLAY
        ) {
            try {
                GLES20.glDeleteProgram(
                    shaderProgram
                )
            } catch (exception: Exception) {
                logError(
                    "OpenGL Shader-Programm konnte nicht gelöscht werden.",
                    exception
                )
            }

            shaderProgram = 0
        }

        if (
            eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglSurface != EGL14.EGL_NO_SURFACE
        ) {
            try {
                EGL14.eglDestroySurface(
                    eglDisplay,
                    eglSurface
                )
            } catch (exception: Exception) {
                logError(
                    "EGL Surface konnte nicht zerstört werden.",
                    exception
                )
            }
        }

        eglSurface =
            EGL14.EGL_NO_SURFACE

        if (
            eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglContext != EGL14.EGL_NO_CONTEXT
        ) {
            try {
                EGL14.eglDestroyContext(
                    eglDisplay,
                    eglContext
                )
            } catch (exception: Exception) {
                logError(
                    "EGL Context konnte nicht zerstört werden.",
                    exception
                )
            }
        }

        eglContext =
            EGL14.EGL_NO_CONTEXT

        if (
            eglDisplay != EGL14.EGL_NO_DISPLAY
        ) {
            try {
                EGL14.eglTerminate(
                    eglDisplay
                )
            } catch (exception: Exception) {
                logError(
                    "EGL Display konnte nicht beendet werden.",
                    exception
                )
            }
        }

        eglDisplay =
            EGL14.EGL_NO_DISPLAY

        try {
            inputSurface?.release()
        } catch (exception: Exception) {
            logError(
                "MediaCodec Input Surface konnte nicht freigegeben werden.",
                exception
            )
        }

        inputSurface = null

        try {
            encoder?.stop()
            log("Encoder.stop() erfolgreich.")
        } catch (exception: Exception) {
            logError(
                "Encoder.stop() fehlgeschlagen.",
                exception
            )
        }

        try {
            encoder?.release()
            log("Encoder.release() erfolgreich.")
        } catch (exception: Exception) {
            logError(
                "Encoder.release() fehlgeschlagen.",
                exception
            )
        }

        encoder = null

        if (muxerStarted) {
            try {
                muxer?.stop()
                log("Muxer.stop() erfolgreich.")
            } catch (exception: Exception) {
                logError(
                    "Muxer.stop() fehlgeschlagen.",
                    exception
                )
            }
        }

        try {
            muxer?.release()
            log("Muxer.release() erfolgreich.")
        } catch (exception: Exception) {
            logError(
                "Muxer.release() fehlgeschlagen.",
                exception
            )
        }

        muxer = null

        muxerStarted = false
        trackIndex = -1
        started = false

        log("release() abgeschlossen.")
        log("==================================================")
    }
}