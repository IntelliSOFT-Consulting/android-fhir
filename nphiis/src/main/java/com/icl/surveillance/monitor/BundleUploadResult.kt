package com.icl.surveillance.monitor


/**
 * Result of a bundle upload operation
 * @param totalResources Total number of resources in the bundle
 * @param successfulUploads List of resource IDs that were successfully uploaded
 * @param failedUploads List of failed uploads with error information
 */
data class BundleUploadResult(
    val totalResources: Int,
    val successfulUploads: List<String>,
    val failedUploads: List<FailedUpload>
) {

    /**
     * Check if the entire bundle upload was successful
     */
    val isCompleteSuccess: Boolean
        get() = failedUploads.isEmpty() && successfulUploads.size == totalResources

    /**
     * Check if the entire bundle upload failed
     */
    val isCompleteFailure: Boolean
        get() = successfulUploads.isEmpty()

    /**
     * Get the success rate as a percentage
     */
    val successRate: Double
        get() = if (totalResources == 0) 0.0
        else (successfulUploads.size.toDouble() / totalResources) * 100

    /**
     * Get a summary message for the upload result
     */
    fun getSummaryMessage(): String {
        return when {
            isCompleteSuccess -> "All $totalResources resources uploaded successfully"
            isCompleteFailure -> "All $totalResources resources failed to upload"
            else -> "$successfulUploads/${totalResources} resources uploaded successfully (${
                "%.1f".format(
                    successRate
                )
            }%)"
        }
    }
}

/**
 * Represents a failed resource upload with error information
 * @param resourceId The ID of the resource that failed to upload
 * @param error The error message describing why the upload failed
 */
data class FailedUpload(
    val resourceId: String,
    val error: String
)
