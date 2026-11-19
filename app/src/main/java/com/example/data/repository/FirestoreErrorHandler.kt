package com.example.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?,
    auth: FirebaseAuth = FirebaseAuth.getInstance()
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val currentUser = auth.currentUser
    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
        }
    } ?: emptyList()

    val trimmedPath = path?.trim()?.trimStart('/')
    val pathSegments = trimmedPath?.split('/')?.filter { it.isNotBlank() }.orEmpty()
    val collectionName = pathSegments.firstOrNull().orEmpty()
    val documentPath = if (pathSegments.size >= 2) trimmedPath else null

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("authenticatedUid", currentUser?.uid)
        put("hasEmail", !currentUser?.email.isNullOrBlank())
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val firestoreCode = (rootError as? FirebaseFirestoreException)?.code?.name
    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("firebaseErrorCode", firestoreCode ?: "none")
        put("operationType", operationType.value)
        put("collection", collectionName)
        put("documentPath", documentPath ?: JSONObject.NULL)
        put("path", path)
        put("authenticatedUid", currentUser?.uid ?: JSONObject.NULL)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

fun Throwable.unwrapFirestoreError(): Throwable {
    val firestoreError = generateSequence(this) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    if (firestoreError != null) return firestoreError
    return this
}
