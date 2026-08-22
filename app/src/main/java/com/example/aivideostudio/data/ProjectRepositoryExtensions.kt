package com.example.aivideostudio.data

suspend fun ProjectRepository.saveScenesRaw(scenes: List<SceneEntity>) {
    for (scene in scenes) {
        this.updateScene(scene)
    }
}
