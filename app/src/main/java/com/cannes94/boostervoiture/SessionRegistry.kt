package com.cannes94.boostervoiture

class SessionRegistry(private val maxSessions: Int = 8) {
    private val order = LinkedHashMap<Int, String?>()

    fun has(sessionId: Int): Boolean = order.containsKey(sessionId)

    fun add(sessionId: Int, packageName: String?): List<Int> {
        if (order.containsKey(sessionId)) return emptyList()
        val evicted = ArrayList<Int>()
        while (order.size >= maxSessions) {
            val oldest = order.keys.first()
            order.remove(oldest)
            evicted.add(oldest)
        }
        order[sessionId] = packageName
        return evicted
    }

    fun remove(sessionId: Int): Boolean = order.keys.remove(sessionId)

    fun packages(): List<String> = order.entries.map { it.value ?: "#${it.key}" }

    fun size(): Int = order.size
}
