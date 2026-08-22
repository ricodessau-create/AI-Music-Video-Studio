package com.example.aivideostudio.di

import android.content.Context
import com.example.aivideostudio.data.AppDatabase
import com.example.aivideostudio.data.ProjectRepository

object ServiceLocator {

    @Volatile
    private var repository: ProjectRepository? = null

    fun getProjectRepository(context: Context): ProjectRepository {
        return repository ?: synchronized(this) {
            repository ?: ProjectRepository(AppDatabase.getInstance(context)).also { repository = it }
        }
    }
}
