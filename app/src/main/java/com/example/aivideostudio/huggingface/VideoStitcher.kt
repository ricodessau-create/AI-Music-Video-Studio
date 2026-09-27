package com.example.aivideostudio.huggingface

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

data class ClipToStitch(
    val filePath: String,
    val targetDurationSeconds: Double,
    val width: Int,
    val height: Int
)

class VideoStitcher(private val workingDirectory: File) {

    fun stitch(clips: List<ClipToStitch>, originalAudioPath: String, outputFile: File): Boolean {
        if (clips.isEmpty()) return false

        val normalizedClipPaths = ArrayList<String>()
        for ((index, clip) in clips.withIndex()) {
            val normalizedFile = File(workingDirectory, "normalized_$index.mp4")
            val success = normalizeClip(clip, normalizedFile)
            if (!success) return false
            normalizedClipPaths.add(normalizedFile.absolutePath)
        }

        val concatListFile = File(workingDirectory, "concat_list.txt")
        concatListFile.writeText(
            normalizedClipPaths.joinToString(separator = "\n") { path -> "file '$path'" }
        )

        val silentVideoFile = File(workingDirectory, "concatenated_video.mp4")
        val concatCommand = "-y -f concat -safe 0 -i \"${concatListFile.absolutePath}\" -c copy \"${silentVideoFile.absolutePath}\""
        val concatSession = FFmpegKit.execute(concatCommand)
        if (!ReturnCode.isSuccess(concatSession.returnCode)) {
            return false
        }

        val muxCommand = "-y -i \"${silentVideoFile.absolutePath}\" -i \"$originalAudioPath\" " +
            "-map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 192k -shortest \"${outputFile.absolutePath}\""
        val muxSession = FFmpegKit.execute(muxCommand)
        return ReturnCode.isSuccess(muxSession.returnCode)
    }

    private fun normalizeClip(clip: ClipToStitch, outputFile: File): Boolean {
        val command = "-y -stream_loop -1 -i \"${clip.filePath}\" -t ${clip.targetDurationSeconds} " +
            "-vf \"scale=${clip.width}:${clip.height}:force_original_aspect_ratio=decrease," +
            "pad=${clip.width}:${clip.height}:(ow-iw)/2:(oh-ih)/2,fps=30\" " +
            "-an -c:v libx264 -preset veryfast -pix_fmt yuv420p \"${outputFile.absolutePath}\""
        val session = FFmpegKit.execute(command)
        return ReturnCode.isSuccess(session.returnCode)
    }
}
