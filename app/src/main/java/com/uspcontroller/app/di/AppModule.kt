package com.uspcontroller.app.di

import android.content.Context
import com.uspcontroller.app.data.discovery.MdnsDiscoveryService
import com.uspcontroller.app.data.repository.UspRepository
import com.uspcontroller.app.data.transport.WebSocketMtpClient
import com.uspcontroller.app.domain.usecase.PollDeviceMetricsUseCase
import com.uspcontroller.app.domain.usecase.SetWifiPassphraseUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt DI module providing all application-level singletons.
 *
 * Installed in [SingletonComponent] so instances live for the entire app lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val CONTROLLER_EID = "os::usp-controller-android"

    /**
     * Provides an [OkHttpClient] configured for WebSocket usage.
     *
     * readTimeout = 0 is required by OkHttp for WebSocket connections
     * (prevents the client from timing out idle connections).
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    /**
     * Provides the application-level [CoroutineScope] with a [SupervisorJob].
     *
     * Child coroutine failures won't cancel sibling coroutines.
     * Uses [Dispatchers.Default] for CPU-bound work; individual operations
     * switch to IO as needed.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Provides the WebSocket MTP client singleton.
     */
    @Provides
    @Singleton
    fun provideWebSocketMtpClient(
        okHttpClient: OkHttpClient,
        @ApplicationScope scope: CoroutineScope
    ): WebSocketMtpClient = WebSocketMtpClient(okHttpClient, scope)

    /**
     * Provides the USP Repository singleton.
     */
    @Provides
    @Singleton
    fun provideUspRepository(
        mtpClient: WebSocketMtpClient
    ): UspRepository = UspRepository(mtpClient, CONTROLLER_EID)

    /**
     * Provides the mDNS discovery service singleton.
     */
    @Provides
    @Singleton
    fun provideMdnsDiscoveryService(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope
    ): MdnsDiscoveryService = MdnsDiscoveryService(context, scope)

    /**
     * Provides the poll device metrics use case.
     */
    @Provides
    fun providePollDeviceMetricsUseCase(
        repository: UspRepository
    ): PollDeviceMetricsUseCase = PollDeviceMetricsUseCase(repository)

    /**
     * Provides the set Wi-Fi passphrase use case.
     */
    @Provides
    fun provideSetWifiPassphraseUseCase(
        repository: UspRepository
    ): SetWifiPassphraseUseCase = SetWifiPassphraseUseCase(repository)
}
