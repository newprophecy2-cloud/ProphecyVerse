package com.example.data.firebase

import com.example.data.local.SharedCatalogCacheEntry
import com.example.data.local.SharedCatalogDao
import com.example.data.local.SharedCatalogPendingEntry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.FieldValue
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Source
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import com.example.data.repository.TitleMatcher
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

/** Direct-client shared catalog access. Backend metadata is always authoritative. */
class SharedCatalogRepository(
    private val dao: SharedCatalogDao,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: (Long) -> Long = { bound -> Random.nextLong(bound) },
    private val networkAvailable: () -> Boolean = { true }
) {
    companion object {
        private const val MAX_BATCH = 20
        private const val MAX_RETRIES = 6
        private const val MAX_QUEUE = 500
        private const val CACHE_READ_TTL = 6L * 60 * 60 * 1000
        private val allowedMetadataFields = setOf(
            "documentId", "canonicalId", "backendId", "contentType", "schemaVersion", "metadataHash",
            "title", "normalizedTitle", "searchKeywords", "description", "posterUrl", "bannerUrl", "mediaType", "releaseYear", "genres",
            "titleEnglish", "titleRomaji", "titleNative", "alternativeTitles", "status", "durationMinutes", "seasonCount", "episodeCount", "metadataComplete",
            "seasonNumber", "episodeNumber", "episodeTitle", "episodeDescription", "parentCanonicalId",
            "parentBackendId", "source", "createdAt", "updatedAt", "lastSyncedAt"
        )
        private val allowedCollections = setOf(
            "shared_catalog_anime", "shared_catalog_movies", "shared_catalog_seasons",
            "shared_catalog_episodes", "shared_catalog_genres", "shared_catalog_categories"
        )
        fun collectionFor(type: String): String? = when (type) {
            "ANIME" -> "shared_catalog_anime"
            "MOVIE" -> "shared_catalog_movies"
            "SEASON" -> "shared_catalog_seasons"
            "EPISODE" -> "shared_catalog_episodes"
            "GENRE" -> "shared_catalog_genres"
            "CATEGORY" -> "shared_catalog_categories"
            else -> null
        }
    }

    private val locks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    suspend fun readAnime(documentId: String, forceServer: Boolean = false) = read("shared_catalog_anime", documentId, forceServer)
    suspend fun readMovie(documentId: String, forceServer: Boolean = false) = read("shared_catalog_movies", documentId, forceServer)
    suspend fun readSeason(documentId: String, forceServer: Boolean = false) = read("shared_catalog_seasons", documentId, forceServer)
    suspend fun readEpisode(documentId: String, forceServer: Boolean = false) = read("shared_catalog_episodes", documentId, forceServer)
    suspend fun readGenre(documentId: String, forceServer: Boolean = false) = read("shared_catalog_genres", documentId, forceServer)
    suspend fun readCategory(documentId: String, forceServer: Boolean = false) = read("shared_catalog_categories", documentId, forceServer)

    /** Firestore-first bounded prefix search. Playback/source fields are never queried or returned. */
    suspend fun searchTitles(query: String, limit: Long = 50): List<SharedCatalogCacheEntry> {
        val normalized = SharedCatalogMetadata.normalizeTitle(query)
        if (normalized.isBlank() || !networkAvailable() || auth.currentUser == null) return emptyList()
        return try {
            val end = normalized + "\uf8ff"
            val tokens = normalized.split(" ").filter { it.length >= 2 }.take(3)
            val candidates = linkedMapOf<String, Pair<String, com.google.firebase.firestore.DocumentSnapshot>>()
            listOf("shared_catalog_anime", "shared_catalog_movies").forEach { collection ->
                val queries = buildList {
                    add(firestore.collection(collection).orderBy("normalizedTitle").startAt(normalized).endAt(end).limit(limit))
                    tokens.forEach { token -> add(firestore.collection(collection).whereArrayContains("searchKeywords", token).limit(limit)) }
                }
                queries.forEach { queryRef ->
                    queryRef.get().await().documents.forEach { document -> candidates.putIfAbsent("$collection/${document.id}", collection to document) }
                }
            }
            candidates.values.mapNotNull { (collection, document) ->
                        val data = document.data ?: return@mapNotNull null
                        val type = data["contentType"] as? String ?: return@mapNotNull null
                        val id = data["documentId"] as? String ?: return@mapNotNull null
                        val backendId = data["backendId"] as? String ?: return@mapNotNull null
                        val hash = data["metadataHash"] as? String ?: return@mapNotNull null
                        if (document.id != id || collectionFor(type) != collection || data["source"] != SharedCatalogMetadata.SOURCE ||
                            (data["schemaVersion"] as? Number)?.toInt() != SharedCatalogMetadata.SCHEMA_VERSION ||
                            !SharedCatalogMetadata.hasStableIdentity(type, backendId, id) ||
                            data["normalizedTitle"] !is String ||
                            data["createdAt"] !is Timestamp || data["updatedAt"] !is Timestamp ||
                            data["lastSyncedAt"] !is Timestamp) return@mapNotNull null
                        val safe = JSONObject()
                        listOf("documentId", "canonicalId", "backendId", "contentType", "schemaVersion", "metadataHash",
                            "title", "normalizedTitle", "searchKeywords", "description", "posterUrl", "bannerUrl",
                            "mediaType", "releaseYear", "genres", "titleEnglish", "titleRomaji", "titleNative", "alternativeTitles",
                            "status", "durationMinutes", "seasonCount", "episodeCount", "metadataComplete", "source").forEach { key ->
                            data[key]?.let { safe.put(key, it) }
                        }
                        SharedCatalogCacheEntry(id, type, data["canonicalId"] as? String, backendId,
                            SharedCatalogMetadata.SCHEMA_VERSION, hash, safe.toString(), now()).also { dao.putCache(it) }
                    }.sortedWith(compareBy<SharedCatalogCacheEntry> {
                        val json = runCatching { JSONObject(it.payloadJson) }.getOrNull()
                        val aliases = buildList {
                            json?.optString("title")?.takeIf { it.isNotBlank() }?.let(::add)
                            json?.optString("titleEnglish")?.takeIf { it.isNotBlank() }?.let(::add)
                            json?.optString("titleRomaji")?.takeIf { it.isNotBlank() }?.let(::add)
                            json?.optString("titleNative")?.takeIf { it.isNotBlank() }?.let(::add)
                            json?.optJSONArray("alternativeTitles")?.let { array -> (0 until array.length()).map { array.optString(it) }.forEach(::add) }
                        }
                        TitleMatcher.rank(normalized, aliases)
                    }.thenBy { it.documentId }).take(limit.toInt().coerceIn(1, 50))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Cache-first read. It only returns validated schema v1 docs, and never writes them over backend state. */
    suspend fun read(collection: String, documentId: String, forceServer: Boolean = false): SharedCatalogCacheEntry? {
        if (collection !in allowedCollections || documentId.isBlank()) return null
        val cached = dao.getCache(documentId)
        if (!forceServer && cached != null && now() - (cached.lastSyncedAt ?: 0L) < CACHE_READ_TTL) return cached
        if (!networkAvailable()) return cached
        return try {
            val snapshot = firestore.collection(collection).document(documentId).get().await()
            if (!snapshot.exists()) return if (forceServer) null else cached
            val data = snapshot.data ?: return cached
            if (!data.keys.all { it in allowedMetadataFields } ||
                !data.keys.containsAll(setOf("documentId", "backendId", "contentType", "schemaVersion", "metadataHash", "createdAt", "updatedAt", "lastSyncedAt", "source")) ||
                data["createdAt"] !is Timestamp || data["updatedAt"] !is Timestamp || data["lastSyncedAt"] !is Timestamp) return cached
            val type = data["contentType"] as? String ?: return cached
            val hash = data["metadataHash"] as? String ?: return cached
            val id = data["documentId"] as? String ?: return cached
            val version = (data["schemaVersion"] as? Number)?.toInt() ?: return cached
            val backendId = data["backendId"] as? String
            if (id != documentId || version != SharedCatalogMetadata.SCHEMA_VERSION ||
                collectionFor(type) != collection || hash.isBlank() ||
                !SharedCatalogMetadata.hasStableIdentity(type, backendId, id) ||
                data["source"] != SharedCatalogMetadata.SOURCE) return cached
            val stringFields = listOf("canonicalId", "backendId", "title", "normalizedTitle", "description", "posterUrl", "bannerUrl",
                "mediaType", "releaseYear", "titleEnglish", "titleRomaji", "titleNative", "status", "episodeTitle", "episodeDescription", "parentCanonicalId", "parentBackendId")
            if (stringFields.any { data[it] != null && data[it] !is String }) return cached
            if (data["searchKeywords"] != null && ((data["searchKeywords"] as? List<*>)?.all { it is String } != true)) return cached
            if (data["genres"] != null && ((data["genres"] as? List<*>)?.all { it is String } != true)) return cached
            if (data["alternativeTitles"] != null && ((data["alternativeTitles"] as? List<*>)?.all { it is String } != true)) return cached
            if (data["metadataComplete"] != null && data["metadataComplete"] !is Boolean) return cached
            if (listOf("durationMinutes", "seasonCount", "episodeCount").any { key -> data[key] != null && data[key] !is Number }) return cached
            if (listOf("seasonNumber", "episodeNumber").any { key -> data[key] != null && data[key] !is Number }) return cached
            if (type == "SEASON" && ((data["seasonNumber"] as? Number)?.toInt()?.let { it > 0 } != true || backendId.isNullOrBlank())) return cached
            if (type == "EPISODE" && ((data["seasonNumber"] as? Number)?.toInt()?.let { it > 0 } != true ||
                    (data["episodeNumber"] as? Number)?.toInt()?.let { it > 0 } != true ||
                    (data["parentBackendId"] as? String).isNullOrBlank())) return cached
            val safe = JSONObject()
            listOf("documentId", "canonicalId", "backendId", "contentType", "schemaVersion", "metadataHash",
                "title", "normalizedTitle", "searchKeywords", "description", "posterUrl", "bannerUrl", "mediaType", "releaseYear", "genres",
                "titleEnglish", "titleRomaji", "titleNative", "alternativeTitles", "status", "durationMinutes", "seasonCount", "episodeCount", "metadataComplete",
                "seasonNumber", "episodeNumber", "episodeTitle", "episodeDescription", "parentCanonicalId",
                "parentBackendId", "source").forEach { key ->
                data[key]?.let { safe.put(key, it) }
            }
            val entry = SharedCatalogCacheEntry(id, type, data["canonicalId"] as? String,
                data["backendId"] as? String, version, hash, safe.toString(), now())
            dao.putCache(entry)
            entry
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { cached }
    }

    /** Backend wins: update Room from backend metadata and queue only when its content hash differs. */
    suspend fun acceptBackendMetadata(metadata: SharedCatalogMetadata) {
        val collection = collectionFor(metadata.contentType) ?: return
        locks.computeIfAbsent(metadata.documentId) { kotlinx.coroutines.sync.Mutex() }.lock()
        try {
            val previous = dao.getCache(metadata.documentId)
            val payload = metadata.firestoreFields(0L, 0L, 0L)
            val json = JSONObject(payload).toString()
            if (previous?.metadataHash == metadata.metadataHash &&
                previous.lastSyncedAt?.let { now() - it < CACHE_READ_TTL } == true) {
                dao.putCache(previous.copy(payloadJson = json))
                return
            }
            // One bounded server read per stale/missing record. Failure is non-fatal and the
            // backend snapshot is still cached/queued; backend is authoritative by policy.
            val serverValue = read(collection, metadata.documentId, forceServer = true)
            val refreshed = dao.getCache(metadata.documentId)
            if (serverValue?.metadataHash == metadata.metadataHash &&
                serverValue.lastSyncedAt?.let { now() - it < CACHE_READ_TTL } == true) return
            dao.putCache(SharedCatalogCacheEntry(metadata.documentId, metadata.contentType,
                metadata.canonicalId, metadata.backendId, metadata.schemaVersion, metadata.metadataHash,
                json, refreshed?.lastSyncedAt))
            val existingPending = dao.getPending(metadata.documentId)
            if (existingPending?.metadataHash == metadata.metadataHash && existingPending.state == "PENDING") return
            dao.enqueueBounded(SharedCatalogPendingEntry(metadata.documentId, collection, metadata.contentType,
                metadata.metadataHash, json, now()), MAX_QUEUE)
        } finally {
            locks[metadata.documentId]?.unlock()
        }
    }

    /** Replays at most 20 persisted events; activation recovery is pending-only, never a catalog scan. */
    suspend fun processPendingQueue(): Int {
        if (auth.currentUser == null || !networkAvailable()) return 0
        val due = dao.getDue(now(), MAX_BATCH)
        var completed = 0
        for (item in due) {
            if (item.collectionName !in allowedCollections || collectionFor(item.contentType) != item.collectionName) {
                dao.updatePending(item.documentId, item.retryCount, Long.MAX_VALUE, "INVALID_RECORD", "PERMANENT_FAILURE")
                continue
            }
            try {
                val payload = JSONObject(item.payloadJson).toMap()
                if (!allowedMetadataFields.containsAll(payload.keys)) {
                    dao.updatePending(item.documentId, item.retryCount, Long.MAX_VALUE, "INVALID_FIELD", "PERMANENT_FAILURE")
                    continue
                }
                val ref = firestore.collection(item.collectionName).document(item.documentId)
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(ref)
                    val existingHash = snapshot.getString("metadataHash")
                    if (existingHash != item.metadataHash) {
                        val outgoing = HashMap<String, Any>(payload.filterKeys { it !in setOf("createdAt", "updatedAt", "lastSyncedAt") })
                        outgoing["documentId"] = item.documentId
                        outgoing["contentType"] = item.contentType
                        outgoing["schemaVersion"] = SharedCatalogMetadata.SCHEMA_VERSION
                        outgoing["metadataHash"] = item.metadataHash
                        outgoing["source"] = SharedCatalogMetadata.SOURCE
                        outgoing["lastSyncedAt"] = FieldValue.serverTimestamp()
                        if (!snapshot.exists()) outgoing["createdAt"] = FieldValue.serverTimestamp()
                        outgoing["updatedAt"] = FieldValue.serverTimestamp()
                        transaction.set(ref, outgoing, SetOptions.merge())
                    }
                    true
                }.await()
                // A completed transaction acknowledges the write, but the sync marker is only
                // advanced after an explicit server read-back (never from the local cache).
                val serverSnapshot = firestore.collection(item.collectionName)
                    .document(item.documentId)
                    .get(Source.SERVER)
                    .await()
                if (!serverSnapshot.exists() ||
                    serverSnapshot.getString("documentId") != item.documentId ||
                    serverSnapshot.getString("metadataHash") != item.metadataHash ||
                    serverSnapshot.getLong("schemaVersion")?.toInt() != SharedCatalogMetadata.SCHEMA_VERSION) {
                    throw IOException("Firestore server read-back did not match shared catalog write")
                }
                dao.deletePending(item.documentId)
                dao.putCache(dao.getCache(item.documentId)?.copy(lastSyncedAt = now()) ?: continue)
                completed++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                val firestoreError = e as? FirebaseFirestoreException
                Log.w(TAG, "Shared catalog sync failed (${firestoreError?.code?.name ?: e::class.simpleName}): ${e.message?.take(240)}")
                val category = when (firestoreError?.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED -> "PERMISSION_DENIED"
                    FirebaseFirestoreException.Code.INVALID_ARGUMENT, FirebaseFirestoreException.Code.FAILED_PRECONDITION -> "PERMANENT_FAILURE"
                    else -> if (e is IOException) "NETWORK" else "TRANSIENT"
                }
                val permanent = category == "PERMISSION_DENIED" || category == "PERMANENT_FAILURE"
                val retries = item.retryCount + 1
                val terminal = permanent || retries >= MAX_RETRIES
                val base = min(6L * 60 * 60 * 1000, 5_000L * (1L shl min(retries, 10)))
                val delay = base + random(maxOf(1L, base / 4))
                dao.updatePending(item.documentId, retries,
                    if (terminal) Long.MAX_VALUE else now() + delay, category,
                    if (terminal) if (permanent) "PERMANENT_FAILURE" else "RETRY_EXHAUSTED" else "PENDING")
            }
        }
        return completed
    }

    private companion object {
        const val TAG = "SharedCatalogRepository"
    }
    private fun JSONObject.toMap(): Map<String, Any> {
        val result = HashMap<String, Any>()
        keys().forEach { key ->
            val value = opt(key)
            if (value != null && value != JSONObject.NULL) result[key] = if (value is org.json.JSONArray) {
                (0 until value.length()).mapNotNull { index -> value.optString(index).takeUnless { it == "null" } }
            } else value
        }
        return result
    }
}
