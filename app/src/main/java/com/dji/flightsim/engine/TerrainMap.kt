package com.dji.flightsim.engine

import kotlin.math.*
import kotlin.random.Random

/** Obstacle on the map for collision detection */
data class Obstacle(
    val x: Float,
    val y: Float,
    val radius: Float,    // collision radius in meters
    val height: Float,    // height in meters
    val type: ObstacleType
)

enum class ObstacleType {
    BUILDING, TREE, TOWER, LANDING_PAD
}

/** Waypoint for guided flight */
data class Waypoint(
    val x: Float,
    val y: Float,
    val altitude: Float,
    val reached: Boolean = false
)

/** Represents a terrain map with obstacles */
class TerrainMap(
    val width: Float = 1000f,   // meters
    val height: Float = 1000f,
    val obstacles: List<Obstacle> = generateDefaultObstacles(),
    val waypoints: List<Waypoint> = emptyList()
) {
    companion object {
        fun generateDefaultObstacles(): List<Obstacle> {
            val rng = Random(42)
            val obstacles = mutableListOf<Obstacle>()

            // Buildings cluster (city area)
            for (i in 0 until 12) {
                obstacles.add(
                    Obstacle(
                        x = 100f + rng.nextFloat() * 200f,
                        y = 100f + rng.nextFloat() * 200f,
                        radius = 8f + rng.nextFloat() * 15f,
                        height = 20f + rng.nextFloat() * 60f,
                        type = ObstacleType.BUILDING
                    )
                )
            }

            // Trees (park area)
            for (i in 0 until 20) {
                obstacles.add(
                    Obstacle(
                        x = -200f + rng.nextFloat() * 150f,
                        y = 50f + rng.nextFloat() * 200f,
                        radius = 3f + rng.nextFloat() * 4f,
                        height = 8f + rng.nextFloat() * 15f,
                        type = ObstacleType.TREE
                    )
                )
            }

            // Communication towers
            obstacles.add(Obstacle(x = 300f, y = -100f, radius = 3f, height = 80f, type = ObstacleType.TOWER))
            obstacles.add(Obstacle(x = -150f, y = -200f, radius = 3f, height = 60f, type = ObstacleType.TOWER))

            // Landing pad at home
            obstacles.add(Obstacle(x = 0f, y = 0f, radius = 2f, height = 0.1f, type = ObstacleType.LANDING_PAD))

            return obstacles
        }

        fun createTrainingCourse(): TerrainMap {
            val waypoints = listOf(
                Waypoint(50f, 0f, 30f),
                Waypoint(100f, 50f, 40f),
                Waypoint(100f, 150f, 50f),
                Waypoint(50f, 200f, 30f),
                Waypoint(0f, 150f, 20f),
                Waypoint(0f, 0f, 5f)
            )
            return TerrainMap(waypoints = waypoints)
        }
    }

    fun checkCollision(state: DroneState): Obstacle? {
        for (obs in obstacles) {
            if (obs.type == ObstacleType.LANDING_PAD) continue
            val dist = sqrt((state.x - obs.x).pow(2) + (state.y - obs.y).pow(2))
            val droneRadius = 0.5f // approximate drone size
            if (dist < obs.radius + droneRadius && state.altitude < obs.height) {
                return obs
            }
        }
        return null
    }

    fun checkWaypointReached(state: DroneState, waypointIndex: Int): Boolean {
        if (waypointIndex >= waypoints.size) return false
        val wp = waypoints[waypointIndex]
        val hDist = sqrt((state.x - wp.x).pow(2) + (state.y - wp.y).pow(2))
        val vDist = abs(state.altitude - wp.altitude)
        return hDist < 5f && vDist < 3f
    }
}
