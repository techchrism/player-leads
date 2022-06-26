package me.techchrism.playerleads

import org.bukkit.*
import org.bukkit.Bukkit.getPluginManager
import org.bukkit.Bukkit.getScheduler
import org.bukkit.Particle.DustOptions
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Entity
import org.bukkit.entity.LeashHitch
import org.bukkit.entity.Player
import org.bukkit.entity.Bat
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.Team
import org.bukkit.util.Vector
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerLeads : JavaPlugin(), Listener {
    private val leashed: HashMap<Player, HashSet<Bat>> = HashMap()
    private lateinit var targetedLeadKey: NamespacedKey

    override fun onEnable() {
        getPluginManager().registerEvents(this, this)
        getScheduler().scheduleSyncRepeatingTask(this, {
            for((player, bats) in leashed) {
                bats.removeIf { !(it.isValid && it.isLeashed) }
                if(bats.size == 0) continue
                
                val distance = 3
                
                val tension = Vector(0, 0, 0)
                for(bat in bats) {
                    val vec = bat.leashHolder.location.toVector().subtract(player.getLeashLocation().toVector())
                    val len = vec.length()
                    if(bats.size > 1) {
                        // Automatically tension leads if attached to more than one object
                        tension.add(vec.normalize().multiply(len))
                    } else if(len > distance) {
                        // If only attached to one object, only tension if past a minimum distance
                        tension.add(vec.normalize().multiply(len - distance))
                    }
                }
                
                tension.multiply(0.1)
                
                if(tension.length() > 0.25) {
                    val newVel = player.velocity.clone().multiply(0.1).add(tension)
                    if(newVel.length() > 0.5) {
                        newVel.normalize().multiply(0.5)
                    }

                    // Check if tension intersects with a block
                    val tensionDirBlock = player.location.block.getRelative(tension.primaryBlockFace())
                    val blockAbove = tensionDirBlock.getRelative(BlockFace.UP)
                    if(!tensionDirBlock.isPassable && blockAbove.isPassable) {
                        newVel.y += tension.length()
                    }
                    
                    player.velocity = newVel
                }
            }
        }, 1L, 1L)
        
        targetedLeadKey = NamespacedKey.fromString("targetedlead", this)!!
        val targetedLeadRecipe = ShapelessRecipe(targetedLeadKey, generateTargetedLead())
        targetedLeadRecipe.addIngredient(Material.LEAD)
        targetedLeadRecipe.addIngredient(Material.REDSTONE)
        Bukkit.addRecipe(targetedLeadRecipe)
    }

    override fun onDisable() {
        Bukkit.removeRecipe(targetedLeadKey)
    }
    
    private fun getTargetedLeadDesc(target: Player? = null) : List<String> {
        val targetStr = (if(target == null) "${ChatColor.GRAY}None" else "${ChatColor.BLUE}${target.name}")
        return listOf(
            "${ChatColor.GOLD} ● Currently targeting: ${targetStr}",
            "${ChatColor.GOLD} ● Click a player with this lead",
            "${ChatColor.GOLD}    in hand to target them",
            "${ChatColor.GOLD} ● When dispensed in front of",
            "${ChatColor.GOLD}    a fence within range of target,",
            "${ChatColor.GOLD}    automatically attaches them",
        )
    }
    
    private fun generateTargetedLead() : ItemStack {
        val item = ItemStack(Material.LEAD, 1)
        val meta = item.itemMeta!!
        meta.setDisplayName("${ChatColor.WHITE}Targeted Lead")
        meta.lore = getTargetedLeadDesc()
        meta.persistentDataContainer.set(targetedLeadKey, PersistentDataType.STRING, "none")
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        item.itemMeta = meta
        return item
    }
    
    private fun drawVector(vector: Vector, location: Location, resolution: Double) {
        val steps = (vector.length() / resolution).roundToInt()
        val unit = vector.clone().multiply(1 / steps.toDouble())
        for(i in 0..steps) {
            var loc = location.clone().add(unit.clone().multiply(i))
            loc.world?.spawnParticle(Particle.REDSTONE, loc, 0, DustOptions(Color.LIME, 1F))
        }
    }
    
    @EventHandler
    private fun onPlayerQuit(event: PlayerQuitEvent) {
        unleashFromAll(event.player)
    }
    
    private fun unleashFromAll(player: Player) {
        leashed[player]?.let {
            for(pairing in it) {
                unleashFrom(player, pairing, false)
            }
        }
        leashed.remove(player)
    }
    
    private fun unleashFrom(player: Player, bat: Bat, remove: Boolean = true) {
        if(bat.isLeashed && !bat.leashHolder.persistentDataContainer.has(targetedLeadKey, PersistentDataType.BYTE)) {
            bat.world.dropItemNaturally(bat.leashHolder.location, ItemStack(Material.LEAD, 1))
        }
        if(remove) {
            leashed[player]?.remove(bat)
        }
        bat.remove()
    }
    
    private fun Player.getLeashLocation(): Location {
        return this.eyeLocation.subtract(0.0, 0.75, 0.0)
    }
    
    private fun Vector.primaryBlockFace(): BlockFace {
        return if(abs(this.z) > abs(this.x)) {
            (if(this.z > 0.0) BlockFace.SOUTH else BlockFace.NORTH)
        } else {
            (if(this.x > 0.0) BlockFace.EAST else BlockFace.WEST)
        }
    }
    
    private fun leash(holder: Entity, victim: Player) {
        val bat = victim.world.spawn(victim.getLeashLocation(), Bat::class.java) { bat: Bat ->
            run {
                with(bat) {
                    isInvulnerable = true
                    //isInvisible = true
                    isSilent = true
                    isAwake = true
                    setAI(false)
                    setGravity(false)
                    setLeashHolder(holder)
                }
            }
        }
        for(player in Bukkit.getOnlinePlayers()) {
            // Give the player a new scoreboard if they have the default one
            if(player.scoreboard == Bukkit.getScoreboardManager()?.mainScoreboard) {
                player.scoreboard = Bukkit.getScoreboardManager()!!.newScoreboard
            }
            
            // Add bat to no collision
            val team = player.scoreboard.getTeam("no-collision") ?: run {
                val newTeam = player.scoreboard.registerNewTeam("no-collision")
                newTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER)
                newTeam
            }
            team.addEntry(bat.uniqueId.toString())
        }
        
        bat.world.playSound(bat.location, Sound.ENTITY_LEASH_KNOT_PLACE, 1.0F, 1.0F)
        leashed.getOrPut(victim) { HashSet() }.add(bat)
    }
    
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    private fun onPlayerInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        val interacted = event.rightClicked
        if(interacted !is Player) return
        
        val interactedBats = leashed[interacted]
        if(event.hand == EquipmentSlot.HAND && interactedBats != null) {
            for(bat in interactedBats) {
                if(bat.isLeashed && bat.leashHolder == event.player) {
                    unleashFrom(interacted, bat, true)
                    return
                }
            }
        }
        
        val item = event.player.inventory.getItem(event.hand) ?: return
        if(item.type != Material.LEAD) return
        
        if(event.player.gameMode != GameMode.CREATIVE) {
            item.amount--
        }
        
        if(item.hasItemMeta()) {
            val uuidStr = item.itemMeta?.persistentDataContainer?.get(targetedLeadKey, PersistentDataType.STRING)
            if(uuidStr != null) {
                if(uuidStr == interacted.uniqueId.toString()) return
                val meta = item.itemMeta!!
                meta.lore = getTargetedLeadDesc(interacted)
                meta.addEnchant(Enchantment.BINDING_CURSE, 1, true)
                meta.persistentDataContainer.set(targetedLeadKey, PersistentDataType.STRING, interacted.uniqueId.toString())
                item.itemMeta = meta
                event.player.playSound(event.player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0F, 1.0F)
                return
            }
        }
        
        leash(event.player, interacted)
    }
    
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    private fun onPlayerMove(event: PlayerMoveEvent) {
        val bats = leashed[event.player] ?: return

        for(bat in bats) {
            bat.teleport(event.player.getLeashLocation())
        }
    }
    
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    private fun onHitchBreak(event: HangingBreakEvent) {
        for((player, bats) in leashed) {
            for(bat in bats) {
                if(bat.leashHolder == event.entity) {
                    unleashFrom(player, bat)
                    return
                }
            }
        }
    }
    
    @EventHandler
    private fun onHitchInteract(event: PlayerInteractAtEntityEvent) {
        if(leashed.containsKey(event.player)) {
            event.isCancelled = true
        }
        val entity = event.rightClicked
        if(entity is LeashHitch) {
            for((player, bats) in leashed) {
                for(bat in bats) {
                    if(bat.leashHolder == entity) {
                        unleashFrom(player, bat)
                        return
                    }
                }
            }
        }
    }
    
    @EventHandler(ignoreCancelled = true)
    private fun onItemDispense(event: BlockDispenseEvent) {
        if(!event.item.hasItemMeta()) return
        val uuidStr = event.item.itemMeta!!.persistentDataContainer.get(targetedLeadKey, PersistentDataType.STRING) ?: return
        
        event.isCancelled = true
        if(uuidStr != "none") {
            val uuid = UUID.fromString(uuidStr)
            val player = Bukkit.getPlayer(uuid)
            if(!(player != null && player.world == event.block.world)) return
            if(player.location.distance(event.block.location) > 50.0) return
            
            val blockInFront = event.block.getRelative((event.block.blockData as Directional).facing)
            if(!blockInFront.type.name.endsWith("_FENCE")) return
            
            // Check for existing hitches
            val entities = blockInFront.world.getNearbyEntities(blockInFront.boundingBox)
            for(entity in entities) {
                if(entity is LeashHitch) {
                    for((victim, bats) in leashed) {
                        for(bat in bats) {
                            if(bat.leashHolder == entity) {
                                entity.remove()
                                unleashFrom(victim, bat)
                                return
                            }
                        }
                    }
                }
            }
            
            val hitch = blockInFront.world.spawn(blockInFront.location, LeashHitch::class.java) {
                it.persistentDataContainer.set(targetedLeadKey, PersistentDataType.BYTE, 1)
            }
            leash(hitch, player)
        }
    }
    
    @EventHandler(ignoreCancelled = true)
    private fun onBlockPlace(event: BlockPlaceEvent) {
        if(leashed.containsKey(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    private fun onBlockBreak(event: BlockBreakEvent) {
        if(leashed.containsKey(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    private fun onInteract(event: PlayerInteractEvent) {
        if(leashed.containsKey(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    private fun onDamage(event: EntityDamageEvent) {
        val player = event.entity
        if(player !is Player) return
        
        if(leashed.containsKey(player)) {
            event.damage = 0.0
        }
    }

    @EventHandler(ignoreCancelled = true)
    private fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if(damager !is Player) return

        if(leashed.containsKey(damager)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    private fun onHitchBreakByPlayer(event: HangingBreakByEntityEvent) {
        val entity = event.entity
        if(entity !is Player) return

        if(leashed.containsKey(entity)) {
            event.isCancelled = true
        }
    }
}