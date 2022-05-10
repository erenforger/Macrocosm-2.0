package space.maxus.macrocosm.entity.loot

import net.minecraft.util.Mth
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import space.maxus.macrocosm.collections.CollectionType
import space.maxus.macrocosm.collections.Section
import space.maxus.macrocosm.item.ItemRegistry
import space.maxus.macrocosm.item.macrocosm
import space.maxus.macrocosm.players.MacrocosmPlayer
import kotlin.random.Random

class LootPool private constructor(private val drops: List<EntityDrop>) {
    companion object {
        fun of(vararg drops: EntityDrop) = LootPool(drops.toList())
    }

    fun roll(mf: Float = 0f) = drops.filter { drop ->
        val mfMod = mf / 100.0
        val chance = drop.chance * (1 + mfMod)
        Random.nextDouble() <= chance
    }

    fun roll(player: MacrocosmPlayer?): List<ItemStack?> {
        val stats = player?.calculateStats()
        val roll = roll(stats?.magicFind ?: 0f)
        return roll.map {
            if (it.item.namespace == "minecraft") {
                val mat = Material.valueOf(it.item.path.uppercase())
                var amount = it.amount.random()
                val collType = CollectionType.from(mat)
                if (collType != null) {
                    player?.addCollectionAmount(collType, amount)
                    var boost = 1
                    var fortune = when (collType.inst.section) {
                        Section.FARMING -> stats?.farmingFortune
                        Section.MINING -> stats?.miningFortune
                        Section.FORAGING -> stats?.foragingFortune
                        Section.EXCAVATING -> stats?.excavatingFortune
                        else -> null
                    } ?: 0f

                    while (fortune >= 100f) {
                        boost += 1
                        fortune -= 100f
                    }
                    amount = Mth.floor((amount * (boost + fortune / 100f)))
                }
                val item = ItemStack(mat, amount)
                if (player?.paper != null) {
                    it.rarity.announceEntityDrop(player.paper!!, item.macrocosm ?: return@map null)
                }
                item.macrocosm?.build() ?: return@map null
            } else {
                val item = ItemRegistry.find(it.item)
                var amount = it.amount.random()
                val collType = CollectionType.from(it.item)
                if (collType != null) {
                    player?.addCollectionAmount(collType, amount)
                    var boost = 1
                    var fortune = when (collType.inst.section) {
                        Section.FARMING -> stats?.farmingFortune
                        Section.MINING -> stats?.miningFortune
                        Section.FORAGING -> stats?.foragingFortune
                        Section.EXCAVATING -> stats?.excavatingFortune
                        else -> null
                    } ?: 0f

                    while (fortune >= 100f) {
                        boost += 1
                        fortune -= 100f
                    }
                    amount = Mth.floor((amount * (boost + fortune / 100f)))
                }
                if (player?.paper != null) {
                    it.rarity.announceEntityDrop(player.paper!!, item)
                }
                (item.build() ?: return@map null).apply { this.amount = amount }
            }
        }
    }

    fun rollRaw(mf: Float = 0f) = roll(mf).map {
        if (it.item.path == "minecraft")
            ItemStack(Material.valueOf(it.item.path.uppercase()), it.amount.random())
        else
            ItemRegistry.find(it.item).build()!!.apply { amount = it.amount.random() }
    }
}
