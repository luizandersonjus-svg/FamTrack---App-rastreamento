package com.famtrack.app.data.remote

import com.famtrack.app.data.model.Notification
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.Serializable

@Serializable
private data class NotifyFamilyParams(
    val p_family_id: String,
    val p_sender_user_id: String,
    val p_title: String,
    val p_message: String,
    val p_type: String
)

class NotificationRepository {

    private val client = SupabaseClient.getInstance()

    suspend fun createNotification(notification: Notification) {
        client.from("notifications").insert(notification)
    }

    suspend fun notifyFamily(
        familyId: String,
        senderUserId: String,
        title: String,
        message: String,
        type: String
    ) {
        client.postgrest.rpc(
            "insert_family_notifications",
            NotifyFamilyParams(familyId, senderUserId, title, message, type)
        )
    }

    suspend fun getUserNotifications(userId: String): List<Notification> {
        return client.from("notifications")
            .select {
                filter {
                    eq("user_id", userId)
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Notification>()
    }

    suspend fun markAsRead(notificationId: String) {
        client.from("notifications")
            .update(mapOf("read" to true)) {
                filter {
                    eq("id", notificationId)
                }
            }
    }

    suspend fun markAllAsRead(userId: String) {
        client.from("notifications")
            .update(mapOf("read" to true)) {
                filter {
                    eq("user_id", userId)
                    eq("read", false)
                }
            }
    }
}
