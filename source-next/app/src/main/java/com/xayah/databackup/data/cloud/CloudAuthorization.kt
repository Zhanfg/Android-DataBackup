package com.xayah.databackup.data.cloud

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface CloudTokenResult {
    data class Authorized(val accessToken: String) : CloudTokenResult
    data object Cancelled : CloudTokenResult
}

sealed interface GoogleDriveAuthorizationResult {
    data class Authorized(val accessToken: String) : GoogleDriveAuthorizationResult
    data class ResolutionRequired(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationResult
}

/**
 * Client-side Google Drive authorization. No refresh token or server is required.
 * Calling [authorize] again lets Google Play services return a cached/granted token when possible.
 */
class GoogleDriveAuthorizationClient(context: Context) {
    private val mClient = Identity.getAuthorizationClient(context.applicationContext)
    private val mRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(CloudAuthorizationScopes.GOOGLE_DRIVE_FILE)))
        .build()

    suspend fun authorize(): GoogleDriveAuthorizationResult =
        mClient.authorize(mRequest).await().toOutcome()

    fun finishResolution(data: Intent): GoogleDriveAuthorizationResult.Authorized =
        mClient.getAuthorizationResultFromIntent(data).toAuthorized()

    private fun AuthorizationResult.toOutcome(): GoogleDriveAuthorizationResult {
        if (hasResolution()) {
            return GoogleDriveAuthorizationResult.ResolutionRequired(
                checkNotNull(pendingIntent) { "Google authorization resolution is missing." }
            )
        }
        return toAuthorized()
    }

    private fun AuthorizationResult.toAuthorized(): GoogleDriveAuthorizationResult.Authorized {
        val token = accessToken?.takeIf(String::isNotBlank)
            ?: error("Google authorization returned no access token.")
        return GoogleDriveAuthorizationResult.Authorized(token)
    }
}

/**
 * Microsoft Graph authorization for OneDrive App Folder.
 *
 * The configuration resource is deliberately supplied by the caller because its client id and
 * redirect URI depend on the final registered Android application/signing certificate.
 */
class MicrosoftOneDriveAuthorizationClient private constructor(
    private val mApplication: IPublicClientApplication,
) {
    suspend fun acquireToken(activity: Activity): CloudTokenResult =
        suspendCancellableCoroutine { continuation ->
            val parameters = AcquireTokenParameters.Builder()
                .withScopes(listOf(CloudAuthorizationScopes.ONEDRIVE_APP_FOLDER))
                .startAuthorizationFromActivity(activity)
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        if (continuation.isActive) {
                            continuation.resume(CloudTokenResult.Authorized(authenticationResult.accessToken))
                        }
                    }

                    override fun onError(exception: MsalException) {
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }

                    override fun onCancel() {
                        if (continuation.isActive) continuation.resume(CloudTokenResult.Cancelled)
                    }
                })
                .build()
            mApplication.acquireToken(parameters)
        }

    companion object {
        const val REDIRECT_URI = "msauth://com.xayah.databackup/auth"
        private const val AUTHORITY = "https://login.microsoftonline.com/common"

        suspend fun create(context: Context, clientId: String): MicrosoftOneDriveAuthorizationClient {
            require(clientId.isNotBlank()) { "Microsoft client id must not be blank." }
            return suspendCancellableCoroutine { continuation ->
                PublicClientApplication.create(
                    context.applicationContext,
                    clientId.trim(),
                    AUTHORITY,
                    REDIRECT_URI,
                    object : IPublicClientApplication.ApplicationCreatedListener {
                        override fun onCreated(application: IPublicClientApplication) {
                            if (continuation.isActive) {
                                continuation.resume(MicrosoftOneDriveAuthorizationClient(application))
                            }
                        }

                        override fun onError(exception: MsalException) {
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                    },
                )
            }
        }
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
