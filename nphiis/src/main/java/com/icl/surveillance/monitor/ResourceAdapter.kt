package com.icl.surveillance.monitor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.icl.surveillance.R

class ResourceAdapter(
    private val onUploadClick: (String) -> Unit,
    private val onRetryClick: (String) -> Unit
) : ListAdapter<ResourceWithSyncStatus, ResourceAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val resourceType: TextView = itemView.findViewById(R.id.tvResourceType)
        private val resourceId: TextView = itemView.findViewById(R.id.tvResourceId)
        private val syncStatusBanner: MaterialCardView =
            itemView.findViewById(R.id.syncStatusBanner)
        private val syncStatusText: TextView = itemView.findViewById(R.id.tvSyncStatus)
        private val syncProgress: CircularProgressIndicator =
            itemView.findViewById(R.id.syncProgress)
        private val syncStatusIcon: ImageView = itemView.findViewById(R.id.syncStatusIcon)
        private val btnRetry: MaterialButton = itemView.findViewById(R.id.btnRetry)
        private val btnCornerUpload: MaterialButton = itemView.findViewById(R.id.btnCornerRetry)

        fun bind(
            resource: ResourceWithSyncStatus,
            onUploadClick: (String) -> Unit,
            onRetryClick: (String) -> Unit
        ) {
            // Set basic resource info
            resourceType.text = resource.resource.resourceType.name
            resourceId.text = "ID: ${resource.resource.logicalId}"

            // Setup sync status UI
            setupSyncStatus(resource)

            // Setup button click listeners
            btnRetry.setOnClickListener {
                onRetryClick(resource.resource.logicalId)
            }

            btnCornerUpload.setOnClickListener {
                onUploadClick(resource.resource.logicalId)
            }

            // Set different text/icon based on sync status
            updateCornerButton(resource.syncStatus)
        }

        private fun setupSyncStatus(resource: ResourceWithSyncStatus) {
            when (resource.syncStatus) {
                SyncStatus.PENDING -> {
                    showSyncStatus(
                        backgroundColor = R.color.sync_pending,
                        text = "Pending Upload",
                        showProgress = false,
                        showRetry = false,
                        showBanner = true
                    )
                }

                SyncStatus.SYNCING -> {
                    showSyncStatus(
                        backgroundColor = R.color.sync_syncing,
                        text = "Uploading...",
                        showProgress = true,
                        showRetry = false,
                        showBanner = true
                    )
                }

                SyncStatus.FAILED -> {
                    showSyncStatus(
                        backgroundColor = R.color.sync_failed,
                        text = "Upload Failed::  ${resource.errorMessage}",
                        showProgress = false,
                        showRetry = true,
                        showBanner = true
                    )
                }

                SyncStatus.RETRYING -> {
                    showSyncStatus(
                        backgroundColor = R.color.sync_syncing,
                        text = "Retrying Upload...",
                        showProgress = true,
                        showRetry = false,
                        showBanner = true
                    )
                }

                SyncStatus.SYNCED -> {
                    showSyncStatus(
                        backgroundColor = R.color.sync_success,
                        text = "Uploaded Successfully",
                        showProgress = false,
                        showRetry = false,
                        showBanner = true
                    )
                }
            }
        }

        private fun showSyncStatus(
            backgroundColor: Int,
            text: String,
            showProgress: Boolean,
            showRetry: Boolean,
            showBanner: Boolean
        ) {
            syncStatusBanner.setCardBackgroundColor(
                ContextCompat.getColor(
                    itemView.context,
                    backgroundColor
                )
            )
            syncStatusText.text = text
            syncProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
            syncStatusIcon.visibility = if (showProgress) View.GONE else View.VISIBLE
            btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
            syncStatusBanner.visibility = if (showBanner) View.VISIBLE else View.GONE
        }

        private fun updateCornerButton(syncStatus: SyncStatus) {
            when (syncStatus) {
                SyncStatus.PENDING -> {
                    btnCornerUpload.text = "Upload"
                    btnCornerUpload.setIconResource(R.drawable.baseline_cloud_upload_24)
                    btnCornerUpload.visibility = View.VISIBLE
                }

                SyncStatus.SYNCING, SyncStatus.RETRYING -> {
                    btnCornerUpload.text = "Uploading"
                    btnCornerUpload.setIconResource(R.drawable.baseline_sync_24)
                    btnCornerUpload.isEnabled = false
                    btnCornerUpload.visibility = View.VISIBLE
                }

                SyncStatus.FAILED -> {
                    btnCornerUpload.text = "Retry"
                    btnCornerUpload.setIconResource(R.drawable.baseline_refresh_24)
                    btnCornerUpload.isEnabled = true
                    btnCornerUpload.visibility = View.VISIBLE
                }

                SyncStatus.SYNCED -> {
                    btnCornerUpload.text = "Uploaded"
                    btnCornerUpload.setIconResource(R.drawable.baseline_check_circle_24)
                    btnCornerUpload.isEnabled = false
                    btnCornerUpload.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_resource_simple, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onUploadClick, onRetryClick)
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ResourceWithSyncStatus>() {
            override fun areItemsTheSame(
                oldItem: ResourceWithSyncStatus,
                newItem: ResourceWithSyncStatus
            ): Boolean {
                return oldItem.resource.logicalId == newItem.resource.logicalId
            }

            override fun areContentsTheSame(
                oldItem: ResourceWithSyncStatus,
                newItem: ResourceWithSyncStatus
            ): Boolean {
                return oldItem == newItem
            }
        }
    }
}