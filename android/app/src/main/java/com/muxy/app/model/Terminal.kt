package com.muxy.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class TakeOverPaneParams(val paneID: String, val cols: Int, val rows: Int)
@Serializable data class ReleasePaneParams(val paneID: String)
@Serializable data class TerminalInputParams(val paneID: String, val bytes: String) // base64
@Serializable data class TerminalResizeParams(val paneID: String, val cols: Int, val rows: Int)
@Serializable data class TerminalScrollParams(val paneID: String, val deltaX: Double, val deltaY: Double, val precise: Boolean)

@Serializable data class TerminalOutputEventDTO(val paneID: String, val bytes: String)

@Serializable data class GetTerminalContentParams(val paneID: String)

/**
 * Mirrors Swift's TerminalContentDTO — full PTY-bytes snapshot of a pane, used
 * to refetch scrollback after a reconnect.
 */
@Serializable data class TerminalContentDTO(
    val paneID: String,
    val content: String, // base64-encoded PTY bytes
    val cols: Int,
    val rows: Int,
)

@Serializable data class TerminalCellDTO(
    val codepoint: Long,
    val fg: Long,
    val bg: Long,
    val flags: Int,
)

/**
 * Mirrors Swift's TerminalCellsDTO — structured cell-by-cell snapshot. Not
 * currently consumed by the Android renderer (we use [TerminalContentDTO]
 * bytes), but decoded so server-sent payloads don't fail parsing.
 */
@Serializable data class TerminalCellsDTO(
    val paneID: String,
    val cols: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val defaultFg: Long,
    val defaultBg: Long,
    val cells: List<TerminalCellDTO>,
    val altScreen: Boolean,
    val cursorKeys: Boolean,
    val bracketedPaste: Boolean,
    val focusEvent: Boolean,
    val mouseEvent: Int,
    val mouseFormat: Int,
)

private inline fun <reified T> tagged(typeName: String, params: T, ser: kotlinx.serialization.KSerializer<T>) =
    TaggedValue(type = typeName, value = MuxyJson.encodeToJsonElement(ser, params))

private fun req(id: String, method: String, params: TaggedValue?) =
    MuxyMessage.Request(MuxyRequest(id = id, method = method, params = params))

fun takeOverPaneRequest(id: String, params: TakeOverPaneParams) =
    req(id, "takeOverPane", tagged("takeOverPane", params, TakeOverPaneParams.serializer()))

fun releasePaneRequest(id: String, params: ReleasePaneParams) =
    req(id, "releasePane", tagged("releasePane", params, ReleasePaneParams.serializer()))

fun terminalInputRequest(id: String, params: TerminalInputParams) =
    req(id, "terminalInput", tagged("terminalInput", params, TerminalInputParams.serializer()))

fun terminalResizeRequest(id: String, params: TerminalResizeParams) =
    req(id, "terminalResize", tagged("terminalResize", params, TerminalResizeParams.serializer()))

fun terminalScrollRequest(id: String, params: TerminalScrollParams) =
    req(id, "terminalScroll", tagged("terminalScroll", params, TerminalScrollParams.serializer()))

fun getTerminalContentRequest(id: String, paneID: String) =
    req(id, "getTerminalContent", tagged("getTerminalContent", GetTerminalContentParams(paneID), GetTerminalContentParams.serializer()))

fun decodeTerminalContent(result: TaggedValue?): TerminalContentDTO? {
    if (result?.type != "terminalContent" || result.value == null) return null
    return MuxyJson.decodeFromJsonElement(TerminalContentDTO.serializer(), result.value)
}

fun decodeTerminalCells(result: TaggedValue?): TerminalCellsDTO? {
    if (result?.type != "terminalCells" || result.value == null) return null
    return MuxyJson.decodeFromJsonElement(TerminalCellsDTO.serializer(), result.value)
}

fun decodeTerminalOutput(data: TaggedValue?): TerminalOutputEventDTO? {
    if (data == null || data.value == null) return null
    if (data.type != "terminalOutput" && data.type != "terminalSnapshot") return null
    return MuxyJson.decodeFromJsonElement(TerminalOutputEventDTO.serializer(), data.value)
}
