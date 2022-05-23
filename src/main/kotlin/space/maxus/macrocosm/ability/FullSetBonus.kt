package space.maxus.macrocosm.ability

import net.axay.kspigot.extensions.bukkit.toComponent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor
import space.maxus.macrocosm.chat.isBlankOrEmpty
import space.maxus.macrocosm.chat.noitalic
import space.maxus.macrocosm.chat.reduceToList
import space.maxus.macrocosm.players.MacrocosmPlayer
import space.maxus.macrocosm.text.comp

/**
 * A wrapper for abstract [AbilityBase], which provides some minor features. Can also be inherited for more abstraction.
 *
 * Note that this ability is also rendered as `Full Set Bonus: <name>`, and does not accept ability types, so make
 * sure to inherit it and override the [buildLore] method to inject your own type.
 *
 * @constructor Creates a new full set bonus ability
 *
 * @param name Name of the ability. Supports MM tags.
 * @param description Description of this ability. Will be partitioned to 25 chars per line, not including MM tags
 */
open class FullSetBonus(name: String, description: String) : AbilityBase(AbilityType.PASSIVE, name, description) {
    /**
     * Ensures that the provided [player] has set with this ability
     *
     * @param player Player to be checked against
     * @return True if all checks passed, false otherwise
     */
    protected fun ensureSetRequirement(player: MacrocosmPlayer): Boolean {
        return listOf(
            player.helmet,
            player.chestplate,
            player.leggings,
            player.boots
        ).map { it != null && it.abilities.contains(this) }.all { it }
    }

    /**
     * Builds and inserts the ability name and descriptions in provided [lore] list
     *
     * @param lore List to be used for lore storage
     * @param player Player to be used for lore building. Used so that some values inside can be updated for players stats, etc.
     */
    override fun buildLore(lore: MutableList<Component>, player: MacrocosmPlayer?) {
        val tmp = mutableListOf<Component>()
        tmp.add(comp("<gold>Full Set Bonus: $name").noitalic())
        for (desc in description.reduceToList()) {
            tmp.add(comp("<gray>$desc</gray>").noitalic())
        }
        tmp.removeIf {
            ChatColor.stripColor(LegacyComponentSerializer.legacySection().serialize(it))!!.isBlankOrEmpty()
        }
        lore.addAll(tmp)
        cost?.buildLore(lore)

        lore.add("".toComponent())
    }
}
