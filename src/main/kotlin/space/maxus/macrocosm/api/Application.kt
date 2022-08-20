package space.maxus.macrocosm.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.papermc.paper.adventure.PaperAdventure
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import org.bukkit.Bukkit
import space.maxus.macrocosm.InternalMacrocosmPlugin
import space.maxus.macrocosm.Macrocosm
import space.maxus.macrocosm.api.KeyManager.validateKey
import space.maxus.macrocosm.pack.PackProvider
import space.maxus.macrocosm.players.MacrocosmPlayer
import space.maxus.macrocosm.registry.Identifier
import space.maxus.macrocosm.registry.Registry
import space.maxus.macrocosm.text.str
import space.maxus.macrocosm.util.GSON
import space.maxus.macrocosm.util.data.ExpiringContainer
import space.maxus.macrocosm.util.data.MutableContainer
import space.maxus.macrocosm.util.general.SuspendConditionalCallback
import space.maxus.macrocosm.util.general.getId
import space.maxus.macrocosm.util.general.putId
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.*

@Suppress("DeferredResultUnused")
suspend fun spinApi() {
    coroutineScope {
        async {
            serverSpin()
        }
    }
}

private val offlineInventoryCompoundCache = MutableContainer.empty<String>()
private val onlineInventoryCompoundCache = ExpiringContainer.empty<String>(Duration.ofMinutes(5).toMillis())

fun Application.module() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondJson(object {
                val success = false
                val error = "Internal Server Error: ${cause.message}"
            }, HttpStatusCode.InternalServerError)
        }

        status(HttpStatusCode.NotFound) { call, status ->
            call.respondJson(object {
                val success = false
                val error = "Not Found"
            }, status)
        }
    }

    routing {
        route("/") {
            get {
                call.respondMacrocosmResource("index.html", "doc")
            }

            get("{static...}") {
                val joined = call.parameters.getAll("static")?.joinToString(File.separator) ?: return@get
                call.respondMacrocosmResource(joined, "doc")
            }
        }

        get("/status") {
            call.respondJson(object {
                val success = true
                val status = "WORKING"
                val inDevEnv = Macrocosm.isInDevEnvironment
                val apiVersion = InternalMacrocosmPlugin.API_VERSION
                val version = InternalMacrocosmPlugin.VERSION
            })
        }

        // pack
        get("/pack") {
            call.respondFile(PackProvider.packZip)
        }

        // resources
        route("/resources") {
            get {
                call.respondJson(object {
                    val success = true
                    val availableRegistries = Registry.iter().filter { reg -> reg.value.shouldBeExposed }.keys.map { k -> k.path }
                })
            }

            route("{registry}") {
                get {
                    val reg = Registry.findOrNull(Identifier.parse(call.parameters["registry"] ?: return@get call.respondJson(object {
                        val success = false
                        val error = "Registry not specified!"
                    }, HttpStatusCode.BadRequest))) ?: return@get call.respondJson(object {
                        val success = false
                        val error = "Could not find registry '${call.parameters["registry"]}'!"
                    }, HttpStatusCode.NotFound)
                    if(!reg.shouldBeExposed)
                        return@get call.respondJson(object {
                            val success = false
                            val error = "This registry can not be queried!"
                        }, HttpStatusCode.BadRequest)
                    call.respondJson(object {
                        val success = true
                        val registry = reg.iter().toMap()
                    })
                }
                get("{element}") {
                    val regParam = call.parameters["registry"] ?: return@get call.respondJson(object {
                        val success = false
                        val error = "Registry not specified!"
                    }, HttpStatusCode.BadRequest)
                    val reg = Registry.findOrNull(Identifier.parse(regParam)) ?: return@get call.respondJson(object {
                        val success = false
                        val error = "Could not find registry '$regParam'!"
                    }, HttpStatusCode.NotFound)
                    if(!reg.shouldBeExposed)
                        return@get call.respondJson(object {
                            val success = false
                            val error = "This registry can not be queried!"
                        }, HttpStatusCode.BadRequest)
                    val element = call.parameters["element"] ?: return@get call.respondJson(object {
                        val success = false
                        val error = "No element provided!"
                    }, HttpStatusCode.BadRequest)
                    val item = reg.findOrNull(Identifier.parse(element)) ?: return@get call.respondJson(object {
                        val success = false
                        val error = "Could not find element $element in '${regParam}' registry!"
                    }, HttpStatusCode.NotFound)
                    call.respondJson(object {
                        val success = true
                        val element = item
                    })
                }
            }
        }

        // players
        route("/players") {
            getWithKey {
                call.respondJson(object {
                    val success = true
                    val onlinePlayers = Bukkit.getOnlinePlayers().associate { p -> p.name to p.uniqueId }
                })
            }

            getWithKey("/{player}/status") {
                val playerParam = call.parameters["player"] ?: return@getWithKey call.respondJson(object {
                    val success = false
                    val error = "Player not provided!"
                }, HttpStatusCode.BadRequest)
                val player = tryRetrievePlayer(playerParam) ?: return@getWithKey call.respondJson(object {
                    val success = true
                    val foundPlayer = false
                    val message = "Player has never joined the server before!"
                }, HttpStatusCode.NoContent)
                call.respondJson(object {
                    val success = true
                    val foundPlayer = true
                    val uuid = player.ref
                    val isOnline = player.paper != null
                })
            }

            getWithKey("/{player}/inventory") {
                val playerParam = call.parameters["player"] ?: return@getWithKey call.respondJson(object {
                    val success = false
                    val error = "Player not provided!"
                }, HttpStatusCode.BadRequest)
                val player = tryRetrievePlayer(playerParam) ?: return@getWithKey call.respondJson(object {
                    val success = false
                    val error = "Player has never joined the server before!"
                }, HttpStatusCode.NoContent)

                var inventoryData = "null"
                val online = player.paper
                if(online == null) {
                    offlineInventoryCompoundCache.take(player.ref) {
                        inventoryData = it
                    }.otherwise {
                        val dataCompound =
                            MinecraftServer.getServer().playerDataStorage.getPlayerData(player.ref.toString())
                        val inventoryTag = dataCompound.getList("Inventory", CompoundTag.TAG_COMPOUND.toInt())
                        inventoryData = cacheInventory(player.ref, inventoryTag)
                    }.call()
                } else {
                    onlineInventoryCompoundCache.trySetExpiring(player.ref) {
                        val dataCompound =
                            MinecraftServer.getServer().playerDataStorage.getPlayerData(player.ref.toString())
                        val inventoryTag = dataCompound.getList("Inventory", CompoundTag.TAG_COMPOUND.toInt())
                        inventoryData = cacheInventory(player.ref, inventoryTag)
                        inventoryData
                    }.otherwise {
                        onlineInventoryCompoundCache.take(player.ref) {
                            inventoryData = it
                        }.otherwise {
                            val dataCompound =
                                MinecraftServer.getServer().playerDataStorage.getPlayerData(player.ref.toString())
                            val inventoryTag = dataCompound.getList("Inventory", CompoundTag.TAG_COMPOUND.toInt())
                            inventoryData = cacheInventory(player.ref, inventoryTag)
                            onlineInventoryCompoundCache[player.ref] = inventoryData
                        }.call()
                    }.call()
                }
                call.respondJson(object {
                    val success = inventoryData != "null"
                    val inventory = inventoryData
                })
            }

            getWithKey("/{player}/balance") {
                val playerParam = call.parameters["player"] ?: return@getWithKey call.respondJson(object {
                    val success = false
                    val error = "Player not provided!"
                }, HttpStatusCode.BadRequest)
                val player = tryRetrievePlayer(playerParam) ?: return@getWithKey call.respondJson(object {
                    val success = false
                    val error = "Player has never joined the server before!"
                }, HttpStatusCode.NoContent)
                call.respondJson(object {
                    val success = true
                    val bank = player.bank
                    val purse = player.purse
                })
            }

            getWithKey("/{player}/skills") {
                val playerParam = call.parameters["player"] ?: return@getWithKey call.respondJson(object {
                    val success = false
                    val error = "Player not provided!"
                }, HttpStatusCode.BadRequest)
                val player = tryRetrievePlayer(playerParam) ?: return@getWithKey call.respondJson(object {
                    val success = false
                    val error = "Player has never joined the server before!"
                }, HttpStatusCode.NoContent)
                call.respondJson(object {
                    val success = true
                    val skills = player.skills.skillExp
                })
            }

            getWithKey("/{player}/collections") {
                val playerParam = call.parameters["player"] ?: return@getWithKey call.respondJson(object {
                    val success = false
                    val error = "Player not provided!"
                }, HttpStatusCode.BadRequest)
                val player = tryRetrievePlayer(playerParam) ?: return@getWithKey call.respondJson(object {
                    val success = false
                    val error = "Player has never joined the server before!"
                }, HttpStatusCode.NoContent)
                call.respondJson(object {
                    val success = true
                    val collections = player.collections.colls
                })
            }
        }
    }
}

fun serverSpin() {
    embeddedServer(Netty, applicationEngineEnvironment {
        log = Macrocosm.slF4JLogger
//        classLoader = Macrocosm.javaClass.classLoader

        connector {
            port = 6060
            host = "127.0.0.1"
        }

        module(Application::module)
    }).start(true)
}

private fun cacheInventory(player: UUID, inventory: ListTag): String {
    val resultList = ListTag()
    inventory.forEach { itemTag ->
        val cmp = itemTag as CompoundTag
        val resultCompound = CompoundTag()
        resultCompound.putId("ItemID", cmp.getId("id"))
        resultCompound.putInt("Amount", cmp.getByte("Count").toInt())
        if (cmp.contains("tag")) {
            val tagCompound = cmp.getCompound("tag")
            resultCompound.put("MacrocosmValues", tagCompound.getCompound("MacrocosmValues"))

            val displayCmp = tagCompound.getCompound("display")

            val nameStr = displayCmp.getString("Name")
            resultCompound.putString(
                "Name",
                PaperAdventure.asAdventure(Component.Serializer.fromJson(nameStr)).str()
            )
            val loreList = ListTag()
            displayCmp.getList("Lore", StringTag.TAG_STRING.toInt()).forEach { ele ->
                val str = ele.asString
                val component = PaperAdventure.asAdventure(Component.Serializer.fromJson(str))
                if(component != net.kyori.adventure.text.Component.empty())
                    loreList.add(StringTag.valueOf(component.str()))
            }
            resultCompound.put("Lore", loreList)
        }
        resultList.add(resultCompound)
    }
    val out = ByteArrayOutputStream()
    val outCmp = CompoundTag()
    outCmp.put("Inventory", resultList)
    NbtIo.writeCompressed(outCmp, out)
    val encodedBytes = Base64.getEncoder().encode(out.toByteArray())
    val str = String(encodedBytes, Charsets.UTF_8)
    offlineInventoryCompoundCache[player] = str
    return str
}

private fun tryRetrievePlayer(param: String): MacrocosmPlayer? {
    val online = try {
        val id = UUID.fromString(param)
        if(Macrocosm.loadedPlayers.containsKey(id))
            return Macrocosm.loadedPlayers[id]
        else Bukkit.getPlayer(id)
    } catch(e: IllegalArgumentException) {
        Bukkit.getPlayer(param)
    } ?: run {
        val offline = try {
            Bukkit.getOfflinePlayer(UUID.fromString(param))
        } catch(e: IllegalArgumentException) {
            Bukkit.getOfflinePlayer(param)
        }
        if(Macrocosm.loadedPlayers.containsKey(offline.uniqueId))
            return Macrocosm.loadedPlayers[offline.uniqueId]
        if(!Macrocosm.playersLazy.contains(offline.uniqueId)) {
            return null
        }
        val loaded = MacrocosmPlayer.readPlayer(offline.uniqueId) ?: return null
        Macrocosm.loadedPlayers[offline.uniqueId] = loaded
        return loaded
    }
    return Macrocosm.loadedPlayers[online.uniqueId]
}

private suspend fun ApplicationCall.respondJson(obj: Any?, code: HttpStatusCode = HttpStatusCode.OK) {
    this.respondText(
        if (obj == null) "{\"error\": \"Internal Server Error\"}" else GSON.toJson(obj),
        ContentType.Application.Json,
        if (obj == null) HttpStatusCode.InternalServerError else code
    )
}

private suspend fun ApplicationCall.respondMacrocosmResource(path: String, base: String) {
    val res = resolveResource(path, classLoader = Macrocosm.javaClass.classLoader, resourcePackage = base)
    if(res != null) {
        respond(res)
    }
}

@KtorDsl
private fun Route.getWithKey(body: PipelineInterceptor<Unit, ApplicationCall>) {
    method(HttpMethod.Get) {
        handle {
            call.requireKey().then {
                body(it)
            }.call()
        }
    }
}

@KtorDsl
private fun Route.getWithKey(path: String, body: PipelineInterceptor<Unit, ApplicationCall>) {
    route(path, HttpMethod.Get) {
        handle {
            call.requireKey().then {
                body(it)
            }.call()
        }
    }
}

private suspend fun ApplicationCall.requireKey(): SuspendConditionalCallback {
    when(val result = validateKey()) {
        KeyManager.ValidationResult.SUCCESS -> return SuspendConditionalCallback.suspendSuccess()
        KeyManager.ValidationResult.NO_KEY_PROVIDED -> {
            respondJson(object {
                val success = false
                val error = "${result.name}: Provide API key for this endpoint"
            }, HttpStatusCode.Forbidden)
            return SuspendConditionalCallback.suspendFail()
        }
        KeyManager.ValidationResult.INVALID_KEY -> {
            respondJson(object {
                val success = false
                val error = "${result.name}: The API key you provided was invalid"
            }, HttpStatusCode.Forbidden)
            return SuspendConditionalCallback.suspendFail()
        }
        KeyManager.ValidationResult.KEY_THROTTLE -> {
            respondJson(object {
                val success = false
                val error = "${result.name}: API key throttle, max amount of requests reached (100)"
            }, HttpStatusCode.TooManyRequests)
            return SuspendConditionalCallback.suspendFail()
        }
    }
}
