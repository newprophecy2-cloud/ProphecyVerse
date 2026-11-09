package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FirebaseVersionControlReadTest {
    @Test
    fun runtimeProjectServerReadAndCacheReadAreValid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firebaseApp = try {
            FirebaseApp.getInstance()
        } catch (error: Throwable) {
            throw AssertionError(
                "Firebase default-app initialization failed: ${error.javaClass.name}; cause=${error.cause?.javaClass?.name}",
                error
            )
        }

        assertEquals("prophecy-verse-483df", firebaseApp.options.projectId)
        assertEquals("com.aistudio.prophecyverse.vykqtb", context.packageName)
        assertTrue("Firebase application ID must be present", !firebaseApp.options.applicationId.isNullOrBlank())

        val document = FirebaseFirestore.getInstance()
            .collection("app_config")
            .document("version_control")

        val serverSnapshot = try {
            Tasks.await(document.get(Source.SERVER), 45, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            val category = when (firestoreError?.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> "PERMISSION_DENIED"
                FirebaseFirestoreException.Code.UNAVAILABLE -> "UNAVAILABLE"
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> "DEADLINE_EXCEEDED"
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> "UNAUTHENTICATED"
                FirebaseFirestoreException.Code.FAILED_PRECONDITION -> "FAILED_PRECONDITION"
                FirebaseFirestoreException.Code.NOT_FOUND -> "NOT_FOUND"
                else -> when (generateSequence(error) { it.cause }.firstOrNull { it is UnknownHostException }) {
                    null -> firestoreError?.code?.name ?: "${error.javaClass.simpleName}/OTHER"
                    else -> "DNS_NETWORK"
                }
            }
            throw AssertionError(
                "Firestore Android SDK server read failed: category=$category; " +
                    "exception=${error.javaClass.name}; firebaseAppId=${firebaseApp.options.applicationId}; " +
                    "projectId=${firebaseApp.options.projectId}; package=${context.packageName}"
            )
        }

        assertTrue("Server read returned no app_config/version_control document", serverSnapshot.exists())
        val latestCode = serverSnapshot.getLong("latest_version_code")
            ?: serverSnapshot.getLong("latestVersionCode")
        val minimumCode = serverSnapshot.getLong("min_supported_version_code")
            ?: serverSnapshot.getLong("minSupportedVersionCode")
        assertEquals("Server latest version code", 6L, latestCode)
        assertEquals("Server minimum supported code", 5L, minimumCode)
        assertEquals("Current code 6 must remain supported", true, 6L >= (minimumCode ?: Long.MAX_VALUE))

        val cacheSnapshot = Tasks.await(document.get(Source.CACHE), 5, TimeUnit.SECONDS)
        assertTrue("Cache-only read should contain the server-fetched document", cacheSnapshot.exists())
        assertEquals("Cache and server policy should agree", latestCode, cacheSnapshot.getLong("latest_version_code")
            ?: cacheSnapshot.getLong("latestVersionCode"))
    }
}
