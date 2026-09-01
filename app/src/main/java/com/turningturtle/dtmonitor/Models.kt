package com.turningturtle.dtmonitor

data class Target(val name: String, val id: String)

data class DtResult(
    val state: State,
    val shardId: Int? = null,
    val startedAt: Long? = null,
    val remainingMs: Long = 0,
    val reason: String? = null
) {
    enum class State { ACTIVE, INACTIVE, UNAVAILABLE }
}

data class ActiveTarget(val target: Target, val result: DtResult)
