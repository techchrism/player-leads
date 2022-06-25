package me.techchrism.playerleads

import org.bukkit.*
import org.bukkit.Bukkit.getPluginManager
import org.bukkit.Bukkit.getScheduler
import org.bukkit.Particle.DustOptions
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.LeashHitch
import org.bukkit.entity.Player
import org.bukkit.entity.Turtle
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.Team
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerLeads : JavaPlugin(), Listener {
    private val leashed: HashMap<Player, HashSet<Turtle>> = HashMap()

    override fun onEnable() {
        getPluginManager().registerEvents(this, this)
        getScheduler().scheduleSyncRepeatingTask(this, {
            for((player, turtles) in leashed) {
                turtles.removeIf { !(it.isValid && it.isLeashed) }
                if(turtles.size == 0) continue
                
                val distance = 3
                
                val tension = Vector(0, 0, 0)
                for(turtle in turtles) {
                    val vec = turtle.leashHolder.location.toVector().subtract(player.getLeashLocation().toVector())
                    val len = vec.length()
                    if(turtles.size > 1) {
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
    
    private fun unleashFrom(player: Player, turtle: Turtle, remove: Boolean = true) {
        turtle.world.dropItemNaturally(turtle.leashHolder.location, ItemStack(Material.LEAD, 1))
        if(remove) {
            leashed[player]?.remove(turtle)
        }
        turtle.remove()
    }
    
    private fun Player.getLeashLocation(): Location {
        return this.eyeLocation.subtract(0.0, 0.3, 0.0)
    }
    
    private fun Vector.primaryBlockFace(): BlockFace {
        return if(abs(this.z) > abs(this.x)) {
            (if(this.z > 0.0) BlockFace.SOUTH else BlockFace.NORTH)
        } else {
            (if(this.x > 0.0) BlockFace.EAST else BlockFace.WEST)
        }
    }
    
    private fun leash(holder: Entity, victim: Player) {
        val turtle = victim.world.spawn(victim.getLeashLocation(), Turtle::class.java) { turtle: Turtle ->
            run {
                with(turtle) {
                    isInvulnerable = true
                    setBaby()
                    isInvisible = true
                    isSilent = true
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
            
            // Add turtle to no collision
            val team = player.scoreboard.getTeam("no-collision") ?: run {
                val newTeam = player.scoreboard.registerNewTeam("no-collision")
                newTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER)
                newTeam
            }
            team.addEntry(turtle.uniqueId.toString())
        }
        
        turtle.world.playSound(turtle.location, Sound.ENTITY_LEASH_KNOT_PLACE, 1.0F, 1.0F)
        leashed.getOrPut(victim) { HashSet() }.add(turtle)
    }
    
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    private fun onPlayerInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        val interacted = event.rightClicked
        val item = event.player.inventory.getItem(event.hand) ?: return
        if(!(interacted is Player && item.type == Material.LEAD)) return
        
        if(event.player.gameMode != GameMode.CREATIVE) {
            item.amount--
        }
        
        leash(event.player, interacted)
    }
    
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    private fun onPlayerMove(event: PlayerMoveEvent) {
        val turtles = leashed[event.player] ?: return

        for(turtle in turtles) {
            turtle.teleport(event.player.getLeashLocation())
        }
    }
    
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    private fun onHitchBreak(event: HangingBreakEvent) {
        for((player, turtles) in leashed) {
            for(turtle in turtles) {
                if(turtle.leashHolder == event.entity) {
                    unleashFrom(player, turtle)
                    return
                }
            }
        }
    }
    
    @EventHandler
    private fun onHitchInteract(event: PlayerInteractAtEntityEvent) {
        val entity = event.rightClicked
        if(entity is LeashHitch) {
            for((player, turtles) in leashed) {
                for(turtle in turtles) {
                    if(turtle.leashHolder == entity) {
                        unleashFrom(player, turtle)
                        return
                    }
                }
            }
        }
    }
}