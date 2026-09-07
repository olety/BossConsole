package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PluginAccessSnapshot(
    val userId: String?,
    val isAdmin: Boolean,
    val permissions: Set<String>,
)

internal data class PluginAccessReconciliation(
    val reconcile: Boolean,
    val reportMissingDependencies: Boolean,
)

/** Owned by the serial access collector; login establishes a baseline, not a dependency prompt. */
internal class PluginAccessTransitions {
    private var previous: PluginAccessSnapshot? = null

    fun accept(access: PluginAccessSnapshot): PluginAccessReconciliation {
        val before = previous
        previous = access
        val changed = before != access
        return PluginAccessReconciliation(
            reconcile = changed,
            reportMissingDependencies = changed && before?.userId != null && before.userId == access.userId,
        )
    }
}

/** Recovery and redundant enables must not turn into requests to install dependencies. */
internal fun shouldReportPluginReenable(
    succeeded: Boolean,
    wasAlreadyEnabled: Boolean,
    reportMissingDependencies: Boolean,
    canAccess: Boolean,
): Boolean = succeeded && !wasAlreadyEnabled && reportMissingDependencies && canAccess

/**
 * The reporter reads StateFlow snapshots and checks JAR files. Keep those checks off the UI
 * thread, but await them in the caller's coroutine so cancellation and access-event ordering
 * remain intact. Registration has already succeeded; an ordinary reporting failure is advisory.
 */
@Suppress("TooGenericExceptionCaught") // A reporter failure must not undo a successful registration.
internal suspend fun reportPluginActivation(
    manifest: PluginManifest,
    report: (PluginManifest) -> Unit,
): Result<Unit> =
    try {
        withContext(Dispatchers.IO) {
            ensureActive()
            report(manifest)
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
