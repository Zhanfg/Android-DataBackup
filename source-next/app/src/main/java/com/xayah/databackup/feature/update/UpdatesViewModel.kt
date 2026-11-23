package com.xayah.databackup.feature.update

import com.xayah.databackup.App
import com.xayah.databackup.BuildConfig
import com.xayah.databackup.R
import com.xayah.databackup.data.GitHubApiErrorKind
import com.xayah.databackup.data.GitHubApiException
import com.xayah.databackup.data.GitHubReleaseRepository
import com.xayah.databackup.util.BaseViewModel
import com.xayah.databackup.util.LogHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Date

sealed interface UpdatesStatus {
    data object Loading : UpdatesStatus
    data object Refreshing : UpdatesStatus
    data object UpToDate : UpdatesStatus
    data object UpdateAvailable : UpdatesStatus
    data class Error(
        val message: String,
        val rateLimitResetAtMillis: Long? = null,
        val rateLimitResetLabel: String? = null,
    ) : UpdatesStatus
}

data class UpdatesUiState(
    val status: UpdatesStatus = UpdatesStatus.Loading,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val latestVersion: String = "",
    val latestNotes: String = "",
)

class UpdatesViewModel(
    private val mGitHubReleaseRepo: GitHubReleaseRepository,
) : BaseViewModel() {
    companion object {
        private const val TAG = "UpdatesViewModel"
        private const val MAX_ERROR_MESSAGE_LENGTH = 240
    }

    private val _uiState = MutableStateFlow(UpdatesUiState())
    val uiState: StateFlow<UpdatesUiState> = _uiState.asStateFlow()

    fun initialize() {
        if (_uiState.value.status != UpdatesStatus.Loading) return
        refresh()
    }

    fun refresh() {
        withLock(Dispatchers.IO) {
            val isInitialLoad = _uiState.value.latestVersion.isEmpty()
            _uiState.update {
                it.copy(
                    status = if (isInitialLoad) UpdatesStatus.Loading else UpdatesStatus.Refreshing,
                    latestNotes = "",
                )
            }

            runCatching {
                val latestRelease = mGitHubReleaseRepo.getLatestRelease()
                val latestVersion = latestRelease.tagName
                val updateAvailable = if (latestVersion.isEmpty()) {
                    false
                } else {
                    compareVersionNames(latestVersion, BuildConfig.VERSION_NAME) > 0
                }

                _uiState.update {
                    it.copy(
                        status = if (updateAvailable) {
                            UpdatesStatus.UpdateAvailable
                        } else {
                            UpdatesStatus.UpToDate
                        },
                        latestNotes = latestRelease.body.orEmpty().trim(),
                        latestVersion = latestVersion,
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                LogHelper.e(TAG, "refresh", "Failed to fetch releases.", throwable)
                val app = App.application
                val apiError = throwable as? GitHubApiException
                if (apiError?.kind == GitHubApiErrorKind.RATE_LIMITED) {
                    val resetLabel = apiError.rateLimitResetAtMillis?.let { formatTime(it) }
                    val message = if (resetLabel != null) {
                        app.getString(R.string.github_api_rate_limited, resetLabel)
                    } else {
                        val errorMessage = apiError.message.takeIf { msg -> msg.isNotBlank() }
                            ?: throwable.message.takeIf { msg -> msg.isNotBlank() }
                            ?: app.getString(R.string.loading_failed)
                        sanitizeErrorMessage(errorMessage)
                    }
                    _uiState.update {
                        it.copy(
                            status = UpdatesStatus.Error(
                                message = message,
                                rateLimitResetAtMillis = apiError.rateLimitResetAtMillis,
                                rateLimitResetLabel = resetLabel,
                            ),
                            latestNotes = "",
                        )
                    }
                } else {
                    val errorMessage = apiError?.message?.takeIf { msg -> msg.isNotBlank() }
                        ?: throwable.message?.takeIf { msg -> msg.isNotBlank() }
                        ?: app.getString(R.string.loading_failed)
                    _uiState.update {
                        it.copy(
                            status = UpdatesStatus.Error(message = sanitizeErrorMessage(errorMessage)),
                            latestNotes = "",
                        )
                    }
                }
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        val formatter = java.text.SimpleDateFormat(
            App.application.getString(R.string.time_format_pattern),
            java.util.Locale.getDefault()
        )
        return formatter.format(Date(timestamp))
    }

    private fun sanitizeErrorMessage(message: String): String {
        val normalized = message
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isEmpty()) {
            return App.application.getString(R.string.loading_failed)
        }

        return if (normalized.length > MAX_ERROR_MESSAGE_LENGTH) {
            normalized.take(MAX_ERROR_MESSAGE_LENGTH).trimEnd() + "..."
        } else {
            normalized
        }
    }
}


internal fun compareVersionNames(left: String, right: String): Int {
    val a = parseVersionName(left)
    val b = parseVersionName(right)

    val coreLength = maxOf(a.core.size, b.core.size, 3)
    for (index in 0 until coreLength) {
        val leftPart = a.core.getOrElse(index) { 0 }
        val rightPart = b.core.getOrElse(index) { 0 }
        if (leftPart != rightPart) return leftPart.compareTo(rightPart)
    }

    if (a.preRelease == null && b.preRelease == null) return 0
    if (a.preRelease == null) return 1
    if (b.preRelease == null) return -1

    val count = maxOf(a.preRelease.size, b.preRelease.size)
    for (index in 0 until count) {
        val leftPart = a.preRelease.getOrNull(index) ?: return -1
        val rightPart = b.preRelease.getOrNull(index) ?: return 1
        val leftNumber = leftPart.toIntOrNull()
        val rightNumber = rightPart.toIntOrNull()
        val comparison = when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> leftPart.compareTo(rightPart, ignoreCase = true)
        }
        if (comparison != 0) return comparison
    }
    return 0
}

private data class ParsedVersionName(
    val core: List<Int>,
    val preRelease: List<String>?,
)

private fun parseVersionName(version: String): ParsedVersionName {
    val normalized = version.trim().removePrefix("v").removePrefix("V")
    val withoutBuild = normalized.substringBefore('+')
    val corePart = withoutBuild.substringBefore('-')
    val preReleasePart = withoutBuild.substringAfter('-', missingDelimiterValue = "")
    val core = corePart.split('.').map { part ->
        part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
    }.ifEmpty { listOf(0) }
    val preRelease = preReleasePart
        .takeIf(String::isNotBlank)
        ?.split('.', '-', '_')
        ?.filter(String::isNotBlank)
    return ParsedVersionName(core, preRelease)
}
