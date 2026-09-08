package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A declared `minApiVersion` must not be skipped just because the installed API layer cannot be
 * read.
 *
 * `hostApiVersion` is `System.getProperty("boss.api.version")`, published once the API plugin
 * resolves. Before that it is blank, and [satisfiesVersionFloor] answers true for a blank
 * installed version - correct for the home grid, wrong here, because it lets an update whose
 * floor the host does not meet reach the jar swap. The loader rejects it afterwards, leaving a
 * broken plugin rather than no update.
 *
 * terminal-tab 2.5.74 declares `minApiVersion` 1.0.88 and implements renameTab / tabActivity /
 * initialCommand against it. On a host still carrying API 1.0.87 those symbols are absent, so
 * "offered but broken" is materially worse than "not offered yet".
 */
class PluginUpdateManagerApiFloorFailClosedTest {
    private val pluginId = "com.example.demo"

    private fun candidate(
        version: String,
        minApi: String,
    ) = PluginInfo(
        pluginId = pluginId,
        displayName = "Demo",
        version = version,
        minApiVersion = minApi,
    )

    private fun manager(
        latest: PluginInfo,
        installedApi: String,
    ): PluginUpdateManager {
        val repos = PluginRepositoryManager().apply { addRepository(FakeApiFloorRepository(latest)) }
        return PluginUpdateManager(
            repositoryManager = repos,
            hostApiVersion = { installedApi },
        )
    }

    @Test
    fun `declared floor with an unresolved API layer is not offered`() =
        runTest {
            val mgr = manager(candidate("2.5.74", minApi = "1.0.88"), installedApi = "")

            val result = mgr.checkForUpdates(mapOf(pluginId to "2.5.71"))

            assertTrue(
                result.availableUpdates.isEmpty(),
                "a floor we cannot prove is met must not be offered",
            )
            assertEquals(1, result.incompatibleNotices.size)
            val notice = result.incompatibleNotices.first()
            assertEquals("2.5.74", notice.advertisedLatest)
            assertEquals("1.0.88", notice.requiredApiVersion)
        }

    @Test
    fun `declared floor with an unparseable API version is not offered`() =
        runTest {
            val mgr = manager(candidate("2.5.74", minApi = "1.0.88"), installedApi = "not-a-version")

            val result = mgr.checkForUpdates(mapOf(pluginId to "2.5.71"))

            assertTrue(result.availableUpdates.isEmpty())
            assertEquals(1, result.incompatibleNotices.size)
        }

    @Test
    fun `no declared floor is still offered when the API layer is unresolved`() =
        runTest {
            // The guard must key on the floor, not on the installed version. Every plugin
            // version published before min_api_version existed carries "", and gating those
            // would withhold every update on a host whose API has not resolved yet.
            val mgr = manager(candidate("2.5.74", minApi = ""), installedApi = "")

            val result = mgr.checkForUpdates(mapOf(pluginId to "2.5.71"))

            assertEquals(1, result.availableUpdates.size)
            assertTrue(result.incompatibleNotices.isEmpty())
        }

    @Test
    fun `satisfied floor is offered`() =
        runTest {
            val mgr = manager(candidate("2.5.74", minApi = "1.0.88"), installedApi = "1.0.88")

            val result = mgr.checkForUpdates(mapOf(pluginId to "2.5.71"))

            assertEquals(1, result.availableUpdates.size)
            assertEquals("2.5.74", result.availableUpdates.first().newVersion)
            assertTrue(result.incompatibleNotices.isEmpty())
        }

    @Test
    fun `floor one patch above the installed API is not offered`() =
        runTest {
            // The exact terminal-tab case: 1.0.87 against a 1.0.88 floor.
            val mgr = manager(candidate("2.5.74", minApi = "1.0.88"), installedApi = "1.0.87")

            val result = mgr.checkForUpdates(mapOf(pluginId to "2.5.71"))

            assertTrue(result.availableUpdates.isEmpty())
            assertEquals("1.0.87", result.incompatibleNotices.first().hostApiVersion)
        }
}

/** Minimal remote [PluginRepository] that always resolves to [latest]. */
private class FakeApiFloorRepository(
    private val latest: PluginInfo,
) : PluginRepository {
    override val id = "fake-api-floor"
    override val name = "Fake API Floor"
    override val isLocal = false
    override val isAvailable = true

    override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(listOf(latest))

    override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
        Result.success(PluginSearchResult(listOf(latest), totalCount = 1))

    override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> =
        Result.success(if (pluginId == latest.pluginId) latest else null)

    override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> =
        Result.success(if (pluginId == latest.pluginId) listOf(latest) else emptyList())

    override suspend fun downloadPlugin(
        pluginId: String,
        version: String?,
        targetPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Result<String> = Result.success(targetPath)

    override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)
}
