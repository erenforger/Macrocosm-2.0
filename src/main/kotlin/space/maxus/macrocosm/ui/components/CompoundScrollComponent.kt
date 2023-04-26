package space.maxus.macrocosm.ui.components

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import space.maxus.macrocosm.ui.UIClickData

/**
 * CompoundScrollComponent is a component that will scroll a compound component
 *
 * @param space The space of the component
 * @param compound The compound component to scroll
 * @param amount The amount to scroll by
 * @param item The item to display
 */
class CompoundScrollComponent(
    space: ComponentSpace,
    val compound: CompoundComponent<*>,
    val amount: Int,
    val item: ItemStack,
) : SpacedComponent(space) {

    override fun handleClick(click: UIClickData) {
        click.bukkit.isCancelled = true
        compound.scroll(amount)
        compound.render(click.inventory, click.instance.base)
        click.paper.updateInventory()
    }

    override fun render(inv: Inventory): ItemStack = item
}
