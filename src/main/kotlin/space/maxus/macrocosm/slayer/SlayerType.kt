package space.maxus.macrocosm.slayer

import space.maxus.macrocosm.registry.Registry
import space.maxus.macrocosm.slayer.zombie.ZombieSlayer
import space.maxus.macrocosm.util.generic.id

enum class SlayerType(val slayer: Slayer) {
    REVENANT_HORROR(ZombieSlayer),

    ;

    companion object {
        fun init() {
            Registry.SLAYER.delegateRegistration(values().map { id(it.slayer.id) to it.slayer })
        }
    }
}