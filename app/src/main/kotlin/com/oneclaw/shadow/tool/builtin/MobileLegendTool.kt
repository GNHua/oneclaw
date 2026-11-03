package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import org.json.JSONObject

class MobileLegendTool : Tool {

    override val definition = ToolDefinition(
        name = "play_mobile_legends",
        description = "Analyze Mobile Legends gameplay and recommend tactical actions based on HP, mana, enemies, allies, gold and level. This tool does not perform automatic taps.",
        parametersSchema = mapOf(
            "action" to ToolParameter(
                type = "string",
                description = "Intended action: move, skill1, skill2, skill3, ultimate, attack, retreat, recall, buy_item",
                required = true
            ),
            "x" to ToolParameter(
                type = "number",
                description = "X coordinate from 0 to 1000",
                required = false
            ),
            "y" to ToolParameter(
                type = "number",
                description = "Y coordinate from 0 to 1000",
                required = false
            ),
            "game_state" to ToolParameter(
                type = "string",
                description = "JSON game state containing hp, mana, enemy_count, ally_count, gold and level",
                required = false
            )
        )
    )

    enum class Phase {
        LANING,
        FARMING,
        ROTATION,
        GANK,
        WAR,
        PUSH,
        RETREAT
    }

    data class GameState(
        val hpPercent: Int = 100,
        val manaPercent: Int = 100,
        val enemyCount: Int = 0,
        val allyCount: Int = 1,
        val gold: Int = 0,
        val level: Int = 1
    )

    private fun decidePhase(state: GameState): Phase {
        return when {
            state.hpPercent <= 20 -> Phase.RETREAT
            state.enemyCount >= 3 -> Phase.WAR
            state.enemyCount == 1 && state.allyCount >= 2 -> Phase.GANK
            state.gold < 500 -> Phase.FARMING
            state.enemyCount == 0 && state.allyCount >= 2 -> Phase.PUSH
            state.level < 4 -> Phase.LANING
            else -> Phase.ROTATION
        }
    }

    private fun parseGameState(raw: String?): GameState {
        if (raw.isNullOrBlank()) return GameState()

        return try {
            val json = JSONObject(raw)

            GameState(
                hpPercent = json.optInt("hp", 100).coerceIn(0, 100),
                manaPercent = json.optInt("mana", 100).coerceIn(0, 100),
                enemyCount = json.optInt("enemy_count", 0).coerceAtLeast(0),
                allyCount = json.optInt("ally_count", 1).coerceAtLeast(0),
                gold = json.optInt("gold", 0).coerceAtLeast(0),
                level = json.optInt("level", 1).coerceIn(1, 15)
            )
        } catch (_: Exception) {
            GameState()
        }
    }

    private fun buildStrategy(
        phase: Phase,
        state: GameState,
        action: String
    ): String {
        return when (phase) {
            Phase.LANING ->
                "Stay in lane, prioritize last-hitting minions, avoid unnecessary trades and conserve skills."

            Phase.FARMING ->
                "Prioritize safe lane or jungle farming. Avoid unnecessary fights until you have enough gold for core items."

            Phase.ROTATION ->
                "Check the minimap and rotate toward objectives or teammates who need assistance."

            Phase.GANK ->
                "Coordinate with nearby allies, approach from a safe angle and focus an isolated enemy."

            Phase.WAR ->
                "Teamfight situation. Stay with your team, avoid engaging alone and prioritize vulnerable enemy targets."

            Phase.PUSH ->
                "Consider pushing the nearest tower when the enemy team is away. Check the minion wave and map before advancing."

            Phase.RETREAT ->
                "HP is critically low. Disengage immediately, move toward a safe area and recall when possible."
        } + when {
            state.manaPercent < 20 ->
                " Mana is low; avoid wasting skills."
            else -> ""
        } + " Requested action: $action."
    }

    override suspend fun execute(
        parameters: Map<String, Any?>
    ): ToolResult {
        return try {
            val action = parameters["action"]
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: "move"

            val x = (parameters["x"] as? Number)
                ?.toInt()
                ?.coerceIn(0, 1000)
                ?: 500

            val y = (parameters["y"] as? Number)
                ?.toInt()
                ?.coerceIn(0, 1000)
                ?: 500

            val state = parseGameState(
                parameters["game_state"]?.toString()
            )

            val phase = decidePhase(state)
            val strategy = buildStrategy(phase, state, action)

            val result = JSONObject().apply {
                put("phase", phase.name)
                put("hp", state.hpPercent)
                put("mana", state.manaPercent)
                put("enemy_count", state.enemyCount)
                put("ally_count", state.allyCount)
                put("gold", state.gold)
                put("level", state.level)
                put("suggested_action", action)
                put("position", JSONObject().apply {
                    put("x", x)
                    put("y", y)
                })
                put("strategy", strategy)
                put(
                    "note",
                    "This tool provides gameplay recommendations only. It does not perform automatic taps or control the game."
                )
            }

            ToolResult.success(result.toString())

        } catch (e: Exception) {
            ToolResult.error(
                "EXECUTION_ERROR",
                "Mobile Legends analysis failed: ${e.message ?: "Unknown error"}"
            )
        }
    }
}
