package com.uspcontroller.app.di

import javax.inject.Qualifier

/**
 * Qualifier annotation for the application-level [kotlinx.coroutines.CoroutineScope].
 *
 * Used to distinguish the long-lived application scope from shorter-lived scopes
 * (e.g., viewModelScope). Bound to [kotlinx.coroutines.SupervisorJob] + [kotlinx.coroutines.Dispatchers.Default].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
