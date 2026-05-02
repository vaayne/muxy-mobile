package com.muxy.app.model

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private const val TAG = "MuxyEvents"

/**
 * Exhaustive sealed model of every server-sent event we can receive. Mirrors
 * Swift's `MuxyEventKind` + `MuxyEventData` (see ios/MuxyShared/MuxyProtocol.swift).
 *
 * Routing through this sealed type — instead of string matching on
 * `MuxyEvent.event` — forces every new event added to the wire protocol to be
 * handled (or explicitly ignored) at the call site. Drift between Swift and
 * Kotlin will surface as an `Unknown` arm at compile time of the `when`.
 */
sealed class MuxyEventKind {
    data class WorkspaceChanged(val workspace: WorkspaceDTO) : MuxyEventKind()
    data class TabChanged(val tab: TabChangeEventDTO) : MuxyEventKind()
    data class TerminalOutput(val output: TerminalOutputEventDTO) : MuxyEventKind()
    data class TerminalSnapshot(val output: TerminalOutputEventDTO) : MuxyEventKind()
    data class NotificationReceived(val notification: NotificationDTO) : MuxyEventKind()
    data class ProjectsChanged(val projects: List<ProjectDTO>) : MuxyEventKind()
    data class PaneOwnershipChanged(val ownership: PaneOwnershipEventDTO) : MuxyEventKind()
    data class ThemeChanged(val theme: DeviceThemeEventDTO) : MuxyEventKind()

    /** Server sent an event name we don't model yet, or the data payload failed to decode. */
    data class Unknown(val name: String, val reason: String) : MuxyEventKind()
}

/**
 * Decode a raw [MuxyEvent] into a typed [MuxyEventKind]. Decode failures and
 * unknown event names are surfaced as [MuxyEventKind.Unknown] (logged) so they
 * never silently disappear.
 */
fun MuxyEvent.toKind(): MuxyEventKind {
    val data = data
    return when (event) {
        "workspaceChanged" -> decode("workspace", data, WorkspaceDTO.serializer())
            ?.let(MuxyEventKind::WorkspaceChanged) ?: unknown(event, data)
        "tabChanged" -> decode("tab", data, TabChangeEventDTO.serializer())
            ?.let(MuxyEventKind::TabChanged) ?: unknown(event, data)
        "terminalOutput" -> decodeTerminalOutput(data)
            ?.let(MuxyEventKind::TerminalOutput) ?: unknown(event, data)
        "terminalSnapshot" -> decodeTerminalOutput(data)
            ?.let(MuxyEventKind::TerminalSnapshot) ?: unknown(event, data)
        "notificationReceived" -> decode("notification", data, NotificationDTO.serializer())
            ?.let(MuxyEventKind::NotificationReceived) ?: unknown(event, data)
        "projectsChanged" -> decodeListOrNull("projects", data, ProjectDTO.serializer())
            ?.let(MuxyEventKind::ProjectsChanged) ?: unknown(event, data)
        "paneOwnershipChanged" -> decodeEventPaneOwnership(data)
            ?.let(MuxyEventKind::PaneOwnershipChanged) ?: unknown(event, data)
        "themeChanged" -> decode("deviceTheme", data, DeviceThemeEventDTO.serializer())
            ?.let(MuxyEventKind::ThemeChanged) ?: unknown(event, data)
        else -> {
            Log.w(TAG, "Unknown event name from server: $event (data type=${data?.type})")
            MuxyEventKind.Unknown(event, "unknown event name")
        }
    }
}

private fun unknown(name: String, data: TaggedValue?): MuxyEventKind.Unknown {
    val reason = "decode failed (data type=${data?.type})"
    Log.w(TAG, "Failed to decode event $name: $reason")
    return MuxyEventKind.Unknown(name, reason)
}

private fun <T> decode(
    expectedType: String,
    data: TaggedValue?,
    serializer: kotlinx.serialization.KSerializer<T>,
): T? {
    if (data?.type != expectedType || data.value == null) return null
    return runCatching { MuxyJson.decodeFromJsonElement(serializer, data.value) }.getOrNull()
}

private fun <T> decodeListOrNull(
    expectedType: String,
    data: TaggedValue?,
    elementSerializer: kotlinx.serialization.KSerializer<T>,
): List<T>? {
    if (data?.type != expectedType || data.value == null) return null
    return runCatching {
        MuxyJson.decodeFromJsonElement(ListSerializer(elementSerializer), data.value)
    }.getOrNull()
}

// ---------- DTOs not previously modelled on Android ----------

@Serializable
data class NotificationDTO(
    val id: String,
    val paneID: String,
    val projectID: String,
    val worktreeID: String,
    val areaID: String,
    val tabID: String,
    val source: SourceDTO,
    val title: String,
    val body: String,
    val timestamp: String,
    val isRead: Boolean,
) {
    /**
     * Mirrors Swift's `SourceDTO` enum-with-associated-values. Swift's default
     * Codable representation: `"osc"`, `"socket"`, or `{"aiProvider": "claude"}`.
     */
    @Serializable(with = SourceDTOSerializer::class)
    sealed class SourceDTO {
        @Serializable @SerialName("osc") data object Osc : SourceDTO()
        @Serializable @SerialName("socket") data object Socket : SourceDTO()
        @Serializable @SerialName("aiProvider") data class AiProvider(val name: String) : SourceDTO()
    }
}

internal object SourceDTOSerializer : kotlinx.serialization.KSerializer<NotificationDTO.SourceDTO> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("SourceDTO")

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: NotificationDTO.SourceDTO) {
        val element: kotlinx.serialization.json.JsonElement = when (value) {
            NotificationDTO.SourceDTO.Osc -> kotlinx.serialization.json.JsonPrimitive("osc")
            NotificationDTO.SourceDTO.Socket -> kotlinx.serialization.json.JsonPrimitive("socket")
            is NotificationDTO.SourceDTO.AiProvider -> kotlinx.serialization.json.JsonObject(
                mapOf("aiProvider" to kotlinx.serialization.json.JsonPrimitive(value.name))
            )
        }
        encoder.encodeSerializableValue(kotlinx.serialization.json.JsonElement.serializer(), element)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): NotificationDTO.SourceDTO {
        val element = decoder.decodeSerializableValue(kotlinx.serialization.json.JsonElement.serializer())
        (element as? kotlinx.serialization.json.JsonPrimitive)?.let { prim ->
            return when (prim.content) {
                "osc" -> NotificationDTO.SourceDTO.Osc
                "socket" -> NotificationDTO.SourceDTO.Socket
                else -> error("Unknown SourceDTO primitive: ${prim.content}")
            }
        }
        val obj = (element as? kotlinx.serialization.json.JsonObject)
            ?: error("Unknown SourceDTO shape: $element")
        obj["aiProvider"]?.let { name ->
            return NotificationDTO.SourceDTO.AiProvider(
                (name as kotlinx.serialization.json.JsonPrimitive).content
            )
        }
        error("Unknown SourceDTO object keys: ${obj.keys}")
    }
}
