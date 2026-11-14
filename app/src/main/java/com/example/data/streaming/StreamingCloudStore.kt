package com.example.data.streaming

import com.example.data.firebase.FirestoreRequestGuard
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source

interface StreamingCloudStore {
    data class DocWrite(val path: String, val data: Map<String, Any?>)

    data class RemoteSnapshot(
        val id: String,
        val path: String,
        val exists: Boolean,
        val data: Map<String, Any?> = emptyMap()
    )

    suspend fun getDoc(operation: String, path: String, preferCache: Boolean = false): RemoteSnapshot?
    suspend fun listCollection(operation: String, collectionPath: String, preferCache: Boolean = false): List<RemoteSnapshot>
    suspend fun commitBatch(operation: String, writes: List<DocWrite>)
    suspend fun mergeDoc(operation: String, path: String, data: Map<String, Any?>)
    suspend fun <T> runTx(operation: String, readPath: String, block: (RemoteSnapshot?) -> Pair<T, List<DocWrite>>): T
    fun observeDoc(path: String, onUpdate: (RemoteSnapshot?) -> Unit, onError: (Throwable) -> Unit): () -> Unit
    fun observeCollection(collectionPath: String, onUpdate: (List<RemoteSnapshot>) -> Unit, onError: (Throwable) -> Unit): () -> Unit
}

internal fun extractFirebaseErrorCode(error: Throwable?): String? {
    if (error == null) return null
    val chain = generateSequence(error) { it.cause }.toList()
    return chain.filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
        ?.code
        ?.name
}

class RealFirestoreCloudStore(
    private val db: FirebaseFirestore
) : StreamingCloudStore {

    private fun docRef(path: String): DocumentReference {
        val segments = path.split('/').filter { it.isNotEmpty() }
        require(segments.size >= 2 && segments.size % 2 == 0) {
            "Invalid Firestore document path (must have even segments): $path"
        }
        var current: DocumentReference = db.collection(segments[0]).document(segments[1])
        var i = 2
        while (i < segments.size) {
            current = current.collection(segments[i]).document(segments[i + 1])
            i += 2
        }
        return current
    }

    private fun colRef(collectionPath: String): CollectionReference {
        val segments = collectionPath.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty() && segments.size % 2 == 1) {
            "Invalid Firestore collection path (must have odd segments): $collectionPath"
        }
        var current: CollectionReference = db.collection(segments[0])
        var i = 1
        while (i < segments.size) {
            current = current.document(segments[i]).collection(segments[i + 1])
            i += 2
        }
        return current
    }

    private fun toRemote(snap: DocumentSnapshot?): StreamingCloudStore.RemoteSnapshot? {
        if (snap == null) return null
        return StreamingCloudStore.RemoteSnapshot(
            id = snap.id,
            path = snap.reference.path,
            exists = snap.exists(),
            data = snap.data.orEmpty()
        )
    }

    override suspend fun getDoc(
        operation: String,
        path: String,
        preferCache: Boolean
    ): StreamingCloudStore.RemoteSnapshot? {
        val ref = docRef(path)
        val source = if (preferCache) Source.CACHE else Source.DEFAULT
        val snap = FirestoreRequestGuard.execute(operation) { ref.get(source) }
        return toRemote(snap)
    }

    override suspend fun listCollection(
        operation: String,
        collectionPath: String,
        preferCache: Boolean
    ): List<StreamingCloudStore.RemoteSnapshot> {
        val ref = colRef(collectionPath)
        val source = if (preferCache) Source.CACHE else Source.DEFAULT
        val snap = FirestoreRequestGuard.execute(operation) { ref.get(source) }
        return snap.documents.mapNotNull { toRemote(it) }
    }

    override suspend fun commitBatch(operation: String, writes: List<StreamingCloudStore.DocWrite>) {
        if (writes.isEmpty()) return
        val batch = db.batch()
        for (write in writes) {
            batch.set(docRef(write.path), write.data, SetOptions.merge())
        }
        FirestoreRequestGuard.execute(operation) { batch.commit() }
    }

    override suspend fun mergeDoc(operation: String, path: String, data: Map<String, Any?>) {
        val ref = docRef(path)
        FirestoreRequestGuard.execute(operation) { ref.set(data, SetOptions.merge()) }
    }

    override suspend fun <T> runTx(
        operation: String,
        readPath: String,
        block: (StreamingCloudStore.RemoteSnapshot?) -> Pair<T, List<StreamingCloudStore.DocWrite>>
    ): T {
        val readRef = docRef(readPath)
        return FirestoreRequestGuard.execute(operation) {
            db.runTransaction { tx ->
                val current = toRemote(tx.get(readRef))
                val (result, writes) = block(current)
                for (write in writes) {
                    tx.set(docRef(write.path), write.data, SetOptions.merge())
                }
                result
            }
        }
    }

    override fun observeDoc(
        path: String,
        onUpdate: (StreamingCloudStore.RemoteSnapshot?) -> Unit,
        onError: (Throwable) -> Unit
    ): () -> Unit {
        val reg = docRef(path).addSnapshotListener { snap, error ->
            if (error != null) {
                onError(error)
            } else {
                onUpdate(toRemote(snap))
            }
        }
        return { runCatching { reg.remove() } }
    }

    override fun observeCollection(
        collectionPath: String,
        onUpdate: (List<StreamingCloudStore.RemoteSnapshot>) -> Unit,
        onError: (Throwable) -> Unit
    ): () -> Unit {
        val reg = colRef(collectionPath).addSnapshotListener { snap, error ->
            if (error != null) {
                onError(error)
            } else if (snap != null) {
                onUpdate(snap.documents.mapNotNull { toRemote(it) })
            }
        }
        return { runCatching { reg.remove() } }
    }
}
