package pm.gnosis.heimdall.common.di.module

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import pm.gnosis.heimdall.common.di.ApplicationContext
import javax.inject.Singleton

@Module
class CoreModule(val application: Application) {

    @Provides
    @Singleton
    @ApplicationContext
    fun providesContext(): Context = application

    @Provides
    @Singleton
    fun providesApplication(): Application = application
}