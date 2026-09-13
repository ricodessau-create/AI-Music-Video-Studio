package com.example.aivideostudio.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.aivideostudio.ui.editor.SceneEditorScreen
import com.example.aivideostudio.ui.home.ProjectListScreen
import com.example.aivideostudio.ui.newproject.NewProjectScreen
import com.example.aivideostudio.ui.preview.PreviewScreen
import com.example.aivideostudio.ui.settings.ProjectSettingsScreen

object AiVideoStudioDestinations {
    const val PROJECT_LIST = "project_list"
    const val NEW_PROJECT = "new_project"
    const val SCENE_EDITOR = "scene_editor/{projectId}"
    const val PREVIEW = "preview/{projectId}"
    const val PROJECT_SETTINGS = "project_settings/{projectId}"

    fun sceneEditorRoute(projectId: String) = "scene_editor/$projectId"
    fun previewRoute(projectId: String) = "preview/$projectId"
    fun projectSettingsRoute(projectId: String) = "project_settings/$projectId"
}

@Composable
fun AiVideoStudioRoot() {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = AiVideoStudioDestinations.PROJECT_LIST) {
        composable(AiVideoStudioDestinations.PROJECT_LIST) {
            ProjectListScreen(
                onCreateNewProject = { navController.navigate(AiVideoStudioDestinations.NEW_PROJECT) },
                onOpenProject = { projectId ->
                    navController.navigate(AiVideoStudioDestinations.sceneEditorRoute(projectId))
                }
            )
        }
        composable(AiVideoStudioDestinations.NEW_PROJECT) {
            NewProjectScreen(
                onProjectCreated = { projectId ->
                    navController.navigate(AiVideoStudioDestinations.sceneEditorRoute(projectId)) {
                        popUpTo(AiVideoStudioDestinations.PROJECT_LIST)
                    }
                }
            )
        }
        composable(AiVideoStudioDestinations.SCENE_EDITOR) { backStackEntry ->
            SceneEditorScreen(
                projectId = backStackEntry.arguments?.getString("projectId").orEmpty(),
                onOpenPreview = { id ->
                    navController.navigate(AiVideoStudioDestinations.previewRoute(id))
                },
                onOpenSettings = { id ->
                    navController.navigate(AiVideoStudioDestinations.projectSettingsRoute(id))
                }
            )
        }
        composable(AiVideoStudioDestinations.PREVIEW) { backStackEntry ->
            PreviewScreen(
                projectId = backStackEntry.arguments?.getString("projectId").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }
        composable(AiVideoStudioDestinations.PROJECT_SETTINGS) { backStackEntry ->
            ProjectSettingsScreen(
                projectId = backStackEntry.arguments?.getString("projectId").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }
    }
}
