package com.example.aivideostudio.localai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxJavaType
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.aivideostudio.render.VideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

data class LocalI2VGenerationRequest(
    val referenceImagePath: String,
    val prompt: String,
    val negativePrompt: String,
    val outputFile: File,
    val width: Int,
    val height: Int,
    val frameRate: Int = 24,
    val frames: Int = 17,
    val diffusionSteps: Int = 2,
    val seed: Long = 0L
)

data class LocalI2VGenerationProgress(
    val stage: String,
    val progress: Float
)

class LocalI2VEngine(
    context: Context
) {

    companion object {
        private const val INPUT_IMAGE_WIDTH = 1280
        private const val INPUT_IMAGE_HEIGHT = 720
        private const val UNET_CHANNELS = 128
        private const val MODEL_FRAMES = 17
        private const val TEXT_SEQUENCE_LENGTH = 300
        private const val TEXT_EMBEDDING_SIZE = 896

        private const val TIMESTEP_1 = 999L
        private const val TIMESTEP_2 = 499L

        private const val ALPHA_1 = 0.0001f
        private const val ALPHA_2 = 0.5f

        private const val SIGMA_1 = 0.9999f
        private const val SIGMA_2 = 0.5f
    }

    private val appContext =
        context.applicationContext

    private val modelStore =
        LocalI2VModelStore(appContext)

    private val environment =
        OrtEnvironment.getEnvironment()

    suspend fun generate(
        request: LocalI2VGenerationRequest,
        onProgress: (LocalI2VGenerationProgress) -> Unit = {}
    ): File = withContext(Dispatchers.Default) {

        validateRequest(request)

        if (!modelStore.areAllModelsInstalled()) {
            throw IllegalStateException(
                buildMissingModelMessage()
            )
        }

        coroutineContext.ensureActive()

        onProgress(
            LocalI2VGenerationProgress(
                "Lokale KI-Modelle werden vorbereitet",
                0.02f
            )
        )

        val inputImage =
            loadAndPrepareImage(
                request.referenceImagePath
            )

        coroutineContext.ensureActive()

        onProgress(
            LocalI2VGenerationProgress(
                "Referenzbild wird in den latenten Raum übertragen",
                0.10f
            )
        )

        val encodedLatent =
            runVaeEncoder(
                inputImage
            )

        coroutineContext.ensureActive()

        onProgress(
            LocalI2VGenerationProgress(
                "Text-/Szenenbedingungen werden vorbereitet",
                0.18f
            )
        )

        val textEmbedding =
            runTextEncoder(
                request.prompt
            )

        coroutineContext.ensureActive()

        onProgress(
            LocalI2VGenerationProgress(
                "Bewegung wird lokal generiert",
                0.25f
            )
        )

        val denoisedLatent =
            runDiffusion(
                encodedLatent,
                textEmbedding,
                request.diffusionSteps,
                onProgress
            )

        coroutineContext.ensureActive()

        onProgress(
            LocalI2VGenerationProgress(
                "KI-Frames werden dekodiert",
                0.78f
            )
        )

        val frames =
            runDecoder(
                denoisedLatent,
                request.frames,
                onProgress
            )

        coroutineContext.ensureActive()

        onProgress(
            LocalI2VGenerationProgress(
                "Lokales MP4 wird erzeugt",
                0.92f
            )
        )

        encodeFramesToVideo(
            frames = frames,
            outputFile = request.outputFile,
            width = request.width,
            height = request.height,
            frameRate = request.frameRate
        )

        onProgress(
            LocalI2VGenerationProgress(
                "Lokale Bild-zu-Video-Generierung abgeschlossen",
                1f
            )
        )

        request.outputFile
    }

    private fun validateRequest(
        request: LocalI2VGenerationRequest
    ) {
        require(
            request.referenceImagePath.isNotBlank()
        ) {
            "Kein Referenzbild angegeben."
        }

        require(
            File(request.referenceImagePath).exists()
        ) {
            "Referenzbild nicht gefunden: ${request.referenceImagePath}"
        }

        require(request.width > 0)
        require(request.height > 0)
        require(request.frameRate > 0)
        require(request.frames > 0)
    }

    private fun buildMissingModelMessage(): String {
        val missing =
            modelStore.getMissingModels()

        return buildString {
            append(
                "Die lokale Bild-zu-Video-KI ist noch nicht vollständig installiert.\n\n"
            )

            append(
                "Fehlende Modelle:\n"
            )

            missing.forEach {
                append("• ")
                append(it)
                append('\n')
            }

            append("\nModellordner:\n")
            append(
                modelStore
                    .getDirectory()
                    .absolutePath
            )

            append(
                "\n\nNach der einmaligen Installation arbeitet die Generierung lokal ohne ComfyUI."
            )
        }
    }

    private fun loadAndPrepareImage(
        path: String
    ): FloatArray {
        val bitmap =
            BitmapFactory.decodeFile(path)
                ?: throw IllegalStateException(
                    "Referenzbild konnte nicht geladen werden."
                )

        val resized =
            resizeCenterCrop(
                bitmap,
                INPUT_IMAGE_WIDTH,
                INPUT_IMAGE_HEIGHT
            )

        if (
            resized !== bitmap &&
            !bitmap.isRecycled
        ) {
            bitmap.recycle()
        }

        val area =
            INPUT_IMAGE_WIDTH *
                INPUT_IMAGE_HEIGHT

        val result =
            FloatArray(
                3 * area
            )

        val pixels =
            IntArray(area)

        resized.getPixels(
            pixels,
            0,
            INPUT_IMAGE_WIDTH,
            0,
            0,
            INPUT_IMAGE_WIDTH,
            INPUT_IMAGE_HEIGHT
        )

        var index = 0

        for (pixel in pixels) {
            val red =
                (pixel shr 16) and 0xFF

            val green =
                (pixel shr 8) and 0xFF

            val blue =
                pixel and 0xFF

            result[index] =
                red / 127.5f - 1f

            result[index + area] =
                green / 127.5f - 1f

            result[index + area * 2] =
                blue / 127.5f - 1f

            index++
        }

        if (!resized.isRecycled) {
            resized.recycle()
        }

        return result
    }

    private fun resizeCenterCrop(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val scale =
            max(
                targetWidth.toFloat() / source.width,
                targetHeight.toFloat() / source.height
            )

        val scaledWidth =
            (source.width * scale)
                .toInt()

        val scaledHeight =
            (source.height * scale)
                .toInt()

        val scaled =
            Bitmap.createScaledBitmap(
                source,
                scaledWidth,
                scaledHeight,
                true
            )

        val left =
            ((scaledWidth - targetWidth) / 2)
                .coerceAtLeast(0)

        val top =
            ((scaledHeight - targetHeight) / 2)
                .coerceAtLeast(0)

        return Bitmap.createBitmap(
            scaled,
            left,
            top,
            min(
                targetWidth,
                scaled.width - left
            ),
            min(
                targetHeight,
                scaled.height - top
            )
        )
    }

    private fun createSession(
        file: File
    ): OrtSession {
        val options =
            OrtSession.SessionOptions()

        options.setOptimizationLevel(
            OrtSession.SessionOptions.OptLevel.ALL_OPT
        )

        options.setIntraOpNumThreads(
            max(
                1,
                Runtime.getRuntime()
                    .availableProcessors()
                    .coerceAtMost(4)
            )
        )

        try {
            options.addXnnpack(
                emptyMap()
            )
        } catch (_: Exception) {
        }

        try {
            options.addNnapi()
        } catch (_: Exception) {
        }

        return try {
            environment.createSession(
                file.absolutePath,
                options
            )
        } finally {
            options.close()
        }
    }

    private fun runVaeEncoder(
        pixels: FloatArray
    ): FloatArray {
        val file =
            modelStore.getModelFile(
                LocalI2VModelStore.VAE_ENCODER
            )

        createSession(file).use { session ->

            val inputName =
                findInputName(
                    session,
                    "pixel_values"
                )

            val outputName =
                findOutputName(
                    session,
                    "latent"
                )

            val tensor =
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(pixels),
                    longArrayOf(
                        1,
                        3,
                        INPUT_IMAGE_HEIGHT.toLong(),
                        INPUT_IMAGE_WIDTH.toLong()
                    )
                )

            tensor.use {
                session.run(
                    mapOf(
                        inputName to tensor
                    )
                ).use { result ->

                    val output =
                        result[outputName]
                            ?: result[0]

                    return readFloatOutput(
                        output
                    )
                }
            }
        }
    }

    private fun runTextEncoder(
        prompt: String
    ): FloatArray {
        val file =
            modelStore.getModelFile(
                LocalI2VModelStore.QWEN2_ENCODER
            )

        createSession(file).use { session ->

            val inputNames =
                session.inputNames

            if (
                inputNames.isEmpty()
            ) {
                return FloatArray(
                    TEXT_SEQUENCE_LENGTH *
                        TEXT_EMBEDDING_SIZE
                )
            }

            val inputIdsName =
                inputNames.firstOrNull {
                    it.contains(
                        "input_ids",
                        true
                    )
                }

            val attentionName =
                inputNames.firstOrNull {
                    it.contains(
                        "attention",
                        true
                    )
                }

            if (
                inputIdsName == null
            ) {
                return createZeroTextEmbedding(
                    session
                )
            }

            val ids =
                createPromptTokenIds(
                    prompt
                )

            val mask =
                LongArray(
                    TEXT_SEQUENCE_LENGTH
                ) {
                    if (it < 2) 1L else 0L
                }

            val tensors =
                ArrayList<OnnxTensor>()

            try {
                val idsTensor =
                    OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(ids),
                        longArrayOf(
                            1,
                            TEXT_SEQUENCE_LENGTH.toLong()
                        )
                    )

                tensors.add(idsTensor)

                val inputs =
                    HashMap<String, OnnxTensor>()

                inputs[inputIdsName] =
                    idsTensor

                if (
                    attentionName != null
                ) {
                    val maskTensor =
                        OnnxTensor.createTensor(
                            environment,
                            LongBuffer.wrap(mask),
                            longArrayOf(
                                1,
                                TEXT_SEQUENCE_LENGTH.toLong()
                            )
                        )

                    tensors.add(maskTensor)

                    inputs[attentionName] =
                        maskTensor
                }

                session.run(inputs).use { result ->
                    val output =
                        result[0]

                    return readFloatOutput(
                        output
                    )
                }
            } finally {
                tensors.forEach {
                    it.close()
                }
            }
        }
    }

    private fun createPromptTokenIds(
        prompt: String
    ): LongArray {
        val result =
            LongArray(
                TEXT_SEQUENCE_LENGTH
            )

        result[0] = 1L

        val bytes =
            prompt
                .toByteArray(
                    Charsets.UTF_8
                )

        val count =
            min(
                bytes.size,
                TEXT_SEQUENCE_LENGTH - 2
            )

        for (index in 0 until count) {
            result[index + 1] =
                (bytes[index].toInt() and 0xFF)
                    .toLong()
        }

        result[count + 1] =
            2L

        return result
    }

    private fun createZeroTextEmbedding(
        session: OrtSession
    ): FloatArray {
        val outputInfo =
            session.outputInfo.values
                .firstOrNull()
                ?.info

        val shape =
            outputInfo?.let {
                runCatching {
                    (it as ai.onnxruntime.TensorInfo)
                        .shape
                }.getOrNull()
            }

        val size =
            shape
                ?.filter { it > 0 }
                ?.fold(1L) { a, b ->
                    a * b
                }
                ?.coerceAtMost(
                    300L * 1024L
                )
                ?.toInt()
                ?: TEXT_SEQUENCE_LENGTH *
                    TEXT_EMBEDDING_SIZE

        return FloatArray(size)
    }

    private fun runDiffusion(
        encodedLatent: FloatArray,
        textEmbedding: FloatArray,
        requestedSteps: Int,
        onProgress: (LocalI2VGenerationProgress) -> Unit
    ): FloatArray {
        val file =
            modelStore.getModelFile(
                LocalI2VModelStore.MOBILE_I2V_UNET
            )

        createSession(file).use { session ->

            val inputInfo =
                session.inputInfo

            val latentInput =
                inputInfo.keys.firstOrNull {
                    it.equals(
                        "latent",
                        true
                    )
                } ?: inputInfo.keys.first()

            val timestepInput =
                inputInfo.keys.firstOrNull {
                    it.contains(
                        "timestep",
                        true
                    )
                }

            val textInput =
                inputInfo.keys.firstOrNull {
                    it.contains(
                        "text",
                        true
                    )
                }

            val latentShape =
                getTensorShape(
                    inputInfo[latentInput]?.info
                )

            val frames =
                MODEL_FRAMES

            val height =
                if (
                    latentShape.size >= 5 &&
                    latentShape[3] > 0
                ) {
                    latentShape[3].toInt()
                } else {
                    90
                }

            val width =
                if (
                    latentShape.size >= 5 &&
                    latentShape[4] > 0
                ) {
                    latentShape[4].toInt()
                } else {
                    160
                }

            val singleFrameSize =
                UNET_CHANNELS *
                    height *
                    width

            val singleLatentSize =
                encodedLatent.size

            val latent =
                FloatArray(
                    frames *
                        singleFrameSize
                )

            for (frame in 0 until frames) {
                for (
                    index in 0 until
                    singleFrameSize
                ) {
                    val source =
                        encodedLatent[
                            index %
                                singleLatentSize
                        ]

                    latent[
                        frame *
                            singleFrameSize +
                            index
                    ] = source
                }
            }

            val steps =
                requestedSteps
                    .coerceIn(
                        1,
                        2
                    )

            val timesteps =
                if (steps == 1) {
                    longArrayOf(
                        TIMESTEP_1
                    )
                } else {
                    longArrayOf(
                        TIMESTEP_1,
                        TIMESTEP_2
                    )
                }

            val alphas =
                if (steps == 1) {
                    floatArrayOf(
                        ALPHA_1
                    )
                } else {
                    floatArrayOf(
                        ALPHA_1,
                        ALPHA_2
                    )
                }

            val sigmas =
                if (steps == 1) {
                    floatArrayOf(
                        SIGMA_1
                    )
                } else {
                    floatArrayOf(
                        SIGMA_1,
                        SIGMA_2
                    )
                }

            for (step in 0 until steps) {
                coroutineContext.ensureActive()

                val fraction =
                    step.toFloat() /
                        steps.toFloat()

                onProgress(
                    LocalI2VGenerationProgress(
                        "Diffusionsschritt ${step + 1} von $steps",
                        0.25f +
                            fraction * 0.45f
                    )
                )

                val tensors =
                    ArrayList<OnnxTensor>()

                try {
                    val latentTensor =
                        OnnxTensor.createTensor(
                            environment,
                            FloatBuffer.wrap(latent),
                            longArrayOf(
                                1,
                                UNET_CHANNELS.toLong(),
                                frames.toLong(),
                                height.toLong(),
                                width.toLong()
                            )
                        )

                    tensors.add(
                        latentTensor
                    )

                    val inputs =
                        HashMap<String, OnnxTensor>()

                    inputs[latentInput] =
                        latentTensor

                    if (
                        timestepInput != null
                    ) {
                        val timestepTensor =
                            OnnxTensor.createTensor(
                                environment,
                                LongBuffer.wrap(
                                    longArrayOf(
                                        timesteps[step]
                                    )
                                ),
                                longArrayOf(1)
                            )

                        tensors.add(
                            timestepTensor
                        )

                        inputs[timestepInput] =
                            timestepTensor
                    }

                    if (
                        textInput != null
                    ) {
                        val textShape =
                            getTensorShape(
                                inputInfo[
                                    textInput
                                ]?.info
                            )

                        val textSize =
                            textShape
                                .filter {
                                    it > 0
                                }
                                .fold(
                                    1L
                                ) { a, b ->
                                    a * b
                                }
                                .toInt()

                        val textData =
                            FloatArray(
                                textSize
                            )

                        System.arraycopy(
                            textEmbedding,
                            0,
                            textData,
                            0,
                            min(
                                textEmbedding.size,
                                textData.size
                            )
                        )

                        val textTensor =
                            OnnxTensor.createTensor(
                                environment,
                                FloatBuffer.wrap(
                                    textData
                                ),
                                normalizeShape(
                                    textShape,
                                    textData.size
                                )
                            )

                        tensors.add(
                            textTensor
                        )

                        inputs[textInput] =
                            textTensor
                    }

                    session.run(
                        inputs
                    ).use { result ->

                        val output =
                            result[0]

                        val prediction =
                            readFloatOutput(
                                output
                            )

                        val count =
                            min(
                                latent.size,
                                prediction.size
                            )

                        for (
                            index in 0 until count
                        ) {
                            latent[index] =
                                (
                                    latent[index] -
                                        sigmas[step] *
                                        prediction[index]
                                    ) /
                                        alphas[step]
                        }
                    }
                } finally {
                    tensors.forEach {
                        it.close()
                    }
                }
            }

            onProgress(
                LocalI2VGenerationProgress(
                    "Diffusion abgeschlossen",
                    0.72f
                )
            )

            return latent
        }
    }

    private fun runDecoder(
        latent: FloatArray,
        requestedFrames: Int,
        onProgress: (LocalI2VGenerationProgress) -> Unit
    ): List<Bitmap> {
        val file =
            modelStore.getModelFile(
                LocalI2VModelStore.TURBO_VAED
            )

        createSession(file).use { session ->

            val inputName =
                session.inputNames.first()

            val shape =
                getTensorShape(
                    session.inputInfo[
                        inputName
                    ]?.info
                )

            if (shape.size != 5) {
                throw IllegalStateException(
                    "Turbo-VAED erwartet einen 5D-Latent-Tensor."
                )
            }

            val channels =
                shape[1]
                    .takeIf { it > 0 }
                    ?.toInt()
                    ?: UNET_CHANNELS

            val frames =
                shape[2]
                    .takeIf { it > 0 }
                    ?.toInt()
                    ?: MODEL_FRAMES

            val height =
                shape[3]
                    .takeIf { it > 0 }
                    ?.toInt()
                    ?: 23

            val width =
                shape[4]
                    .takeIf { it > 0 }
                    ?.toInt()
                    ?: 40

            val expectedSize =
                channels *
                    frames *
                    height *
                    width

            val decoderLatent =
                FloatArray(
                    expectedSize
                )

            System.arraycopy(
                latent,
                0,
                decoderLatent,
                0,
                min(
                    latent.size,
                    decoderLatent.size
                )
            )

            val tensor =
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(
                        decoderLatent
                    ),
                    longArrayOf(
                        1,
                        channels.toLong(),
                        frames.toLong(),
                        height.toLong(),
                        width.toLong()
                    )
                )

            tensor.use {
                session.run(
                    mapOf(
                        inputName to tensor
                    )
                ).use { result ->

                    val output =
                        result[0]

                    val outputShape =
                        getTensorShape(
                            output.info
                        )

                    val values =
                        readFloatOutput(
                            output
                        )

                    return convertDecodedFrames(
                        values,
                        outputShape,
                        requestedFrames,
                        onProgress
                    )
                }
            }
        }
    }

    private fun convertDecodedFrames(
        values: FloatArray,
        shape: LongArray,
        requestedFrames: Int,
        onProgress: (LocalI2VGenerationProgress) -> Unit
    ): List<Bitmap> {
        if (shape.size != 5) {
            throw IllegalStateException(
                "Turbo-VAED lieferte keine 5D-Videoausgabe."
            )
        }

        val channels =
            shape[1].toInt()

        val frames =
            shape[2].toInt()

        val height =
            shape[3].toInt()

        val width =
            shape[4].toInt()

        if (channels < 3) {
            throw IllegalStateException(
                "Turbo-VAED-Ausgabe enthält weniger als drei Farbkanäle."
            )
        }

        val frameSize =
            channels *
                height *
                width

        if (
            values.size <
            frameSize * frames
        ) {
            throw IllegalStateException(
                "Turbo-VAED-Ausgabe ist kleiner als erwartet."
            )
        }

        val result =
            ArrayList<Bitmap>()

        val actualFrames =
            min(
                requestedFrames,
                frames
            )

        for (frameIndex in 0 until actualFrames) {
            coroutineContext.ensureActive()

            val bitmap =
                Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
                )

            val pixels =
                IntArray(
                    width * height
                )

            val frameOffset =
                frameIndex *
                    frameSize

            for (pixelIndex in pixels.indices) {
                val red =
                    values[
                        frameOffset +
                            pixelIndex
                    ]

                val green =
                    values[
                        frameOffset +
                            height *
                            width +
                            pixelIndex
                    ]

                val blue =
                    values[
                        frameOffset +
                            2 *
                            height *
                            width +
                            pixelIndex
                    ]

                val r =
                    (((red + 1f) * 127.5f)
                        .toInt())
                        .coerceIn(0, 255)

                val g =
                    (((green + 1f) * 127.5f)
                        .toInt())
                        .coerceIn(0, 255)

                val b =
                    (((blue + 1f) * 127.5f)
                        .toInt())
                        .coerceIn(0, 255)

                pixels[pixelIndex] =
                    0xFF000000.toInt() or
                        (r shl 16) or
                        (g shl 8) or
                        b
            }

            bitmap.setPixels(
                pixels,
                0,
                width,
                0,
                0,
                width,
                height
            )

            result.add(bitmap)

            onProgress(
                LocalI2VGenerationProgress(
                    "Frame ${frameIndex + 1} von $actualFrames",
                    0.78f +
                        0.12f *
                        (
                            (frameIndex + 1)
                                .toFloat() /
                                actualFrames
                                    .toFloat()
                        )
                )
            )
        }

        return result
    }

    private fun encodeFramesToVideo(
        frames: List<Bitmap>,
        outputFile: File,
        width: Int,
        height: Int,
        frameRate: Int
    ) {
        if (frames.isEmpty()) {
            throw IllegalStateException(
                "Die lokale KI hat keine Videoframes erzeugt."
            )
        }

        outputFile.parentFile?.mkdirs()

        val encoder =
            VideoEncoder(
                outputFile = outputFile,
                width = width,
                height = height,
                frameRate = frameRate,
                bitrate = 8_000_000
            )

        try {
            encoder.start()

            for ((index, sourceFrame) in frames.withIndex()) {
                coroutineContext.ensureActive()

                val outputFrame =
                    if (
                        sourceFrame.width == width &&
                        sourceFrame.height == height
                    ) {
                        sourceFrame
                    } else {
                        Bitmap.createScaledBitmap(
                            sourceFrame,
                            width,
                            height,
                            true
                        )
                    }

                encoder.encodeFrame(
                    outputFrame,
                    index.toLong() *
                        1_000_000L /
                        frameRate.toLong()
                )

                if (
                    outputFrame !== sourceFrame &&
                    !outputFrame.isRecycled
                ) {
                    outputFrame.recycle()
                }

                if (
                    !sourceFrame.isRecycled
                ) {
                    sourceFrame.recycle()
                }
            }

            encoder.finish()
        } catch (exception: Exception) {
            throw exception
        }
    }

    private fun findInputName(
        session: OrtSession,
        preferred: String
    ): String {
        return session.inputNames.firstOrNull {
            it.equals(
                preferred,
                true
            )
        } ?: session.inputNames.first()
    }

    private fun findOutputName(
        session: OrtSession,
        preferred: String
    ): String {
        return session.outputNames.firstOrNull {
            it.equals(
                preferred,
                true
            )
        } ?: session.outputNames.first()
    }

    private fun getTensorShape(
        info: ai.onnxruntime.NodeInfo?
    ): LongArray {
        val tensorInfo =
            info?.info as? ai.onnxruntime.TensorInfo

        return tensorInfo?.shape
            ?: LongArray(0)
    }

    private fun normalizeShape(
        shape: LongArray,
        elementCount: Int
    ): LongArray {
        if (
            shape.isNotEmpty() &&
            shape.none { it <= 0L }
        ) {
            return shape
        }

        if (
            shape.isEmpty()
        ) {
            return longArrayOf(
                1,
                elementCount.toLong()
            )
        }

        val normalized =
            shape.copyOf()

        var known =
            1L

        var unknownIndex =
            -1

        for (index in normalized.indices) {
            if (
                normalized[index] <= 0L
            ) {
                unknownIndex =
                    index
            } else {
                known *=
                    normalized[index]
            }
        }

        if (
            unknownIndex >= 0 &&
            known > 0L
        ) {
            normalized[
                unknownIndex
            ] =
                max(
                    1L,
                    elementCount.toLong() /
                        known
                )
        }

        return normalized
    }

    private fun readFloatOutput(
        value: ai.onnxruntime.OnnxValue
    ): FloatArray {
        val tensor =
            value as? OnnxTensor
                ?: throw IllegalStateException(
                    "ONNX-Ausgabe ist kein Tensor."
                )

        if (
            tensor.info.type !=
            OnnxJavaType.FLOAT
        ) {
            throw IllegalStateException(
                "ONNX-Ausgabe muss FLOAT sein, ist aber ${tensor.info.type}."
            )
        }

        val raw =
            tensor.value

        return flattenFloatArray(
            raw
        )
    }

    private fun flattenFloatArray(
        value: Any?
    ): FloatArray {
        if (value == null) {
            return FloatArray(0)
        }

        if (value is FloatArray) {
            return value
        }

        if (value is FloatBuffer) {
            val duplicate =
                value.duplicate()

            val result =
                FloatArray(
                    duplicate.remaining()
                )

            duplicate.get(result)

            return result
        }

        if (value is Array<*>) {
            val arrays =
                value.map {
                    flattenFloatArray(it)
                }

            val total =
                arrays.sumOf {
                    it.size
                }

            val result =
                FloatArray(total)

            var offset = 0

            for (array in arrays) {
                System.arraycopy(
                    array,
                    0,
                    result,
                    offset,
                    array.size
                )

                offset +=
                    array.size
            }

            return result
        }

        throw IllegalStateException(
            "Nicht unterstützter ONNX-Ausgabetyp: ${value::class.java.name}"
        )
    }
}