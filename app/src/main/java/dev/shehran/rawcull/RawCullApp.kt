package dev.shehran.rawcull

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch

class RawCullApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.xmpWriteQueue.start()
        container.applicationScope.launch { container.importController.ensureScheduled() }
        // Leaving the app is the last chance to write before the system may kill the process.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                container.applicationScope.launch { container.xmpWriteQueue.flushNow() }
            }
        })
    }
}
