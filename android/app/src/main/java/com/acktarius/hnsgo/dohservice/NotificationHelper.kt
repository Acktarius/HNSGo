package com.acktarius.hnsgo.dohservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * Helper functions for creating notifications and notification channels
 */
object NotificationHelper {
    private const val CHANNEL_ID = "hns"
    private const val CHANNEL_NAME = "HNS Go"

    /**
     * Create notification channel for foreground service
     */
    fun createChannel(context: Context) {
        try {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        } catch (e: Exception) {
        }
    }

    /**
     * Create notification for foreground service
     */
    fun createNotification(context: Context, text: String): Notification {
        // Try to get the icon resource, fallback to system icon if R class not available
        val iconId = try {
            context.resources.getIdentifier("ic_hns", "drawable", context.packageName)
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info // Fallback system icon
        }
        
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("HNS Go")
            .setContentText(text)
            .setSmallIcon(if (iconId != 0) iconId else android.R.drawable.ic_dialog_info)
            .build()
    }
}

