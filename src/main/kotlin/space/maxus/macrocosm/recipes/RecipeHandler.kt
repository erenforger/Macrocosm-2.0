package space.maxus.macrocosm.recipes

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import space.maxus.macrocosm.players.MacrocosmPlayer
import space.maxus.macrocosm.registry.Registry
import java.util.concurrent.ConcurrentLinkedQueue

object RecipeHandler {
    fun matchingRecipes(
        ctx: Inventory,
        player: MacrocosmPlayer
    ): List<Pair<MacrocosmRecipe, HashMap<Int, Pair<ItemStack, Int>>>> {
        val matching = ConcurrentLinkedQueue<Pair<MacrocosmRecipe, HashMap<Int, Pair<ItemStack, Int>>>>()
        for ((_, recipe) in Registry.RECIPE.iter().toList().parallelStream()) {
            val (matches, indices) = recipe.matches(player, ctx, false)
            if (matches) {
                matching.add(Pair(recipe, indices))
            }
        }
        return matching.toList()
    }
}
