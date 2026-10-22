package com.cannes94.boostervoiture

class SessionRegistry(private val maxSessions: Int = 8) {
    private val order = LinkedHashMap<Int, String?>()
    /** Sessions dont l'attachement a échoué : inutile de réessayer avant leur CLOSE. */
    private val failed = HashSet<Int>()

    fun has(sessionId: Int): Boolean = order.containsKey(sessionId)

    fun isFailed(sessionId: Int): Boolean = failed.contains(sessionId)

    fun markFailed(sessionId: Int) {
        failed.add(sessionId)
    }

    fun add(sessionId: Int, packageName: String?): List<Int> {
        if (order.containsKey(sessionId)) return emptyList()
        failed.remove(sessionId)
        val evicted = ArrayList<Int>()
        while (order.size >= maxSessions) {
            val oldest = order.keys.first()
            order.remove(oldest)
            evicted.add(oldest)
        }
        order[sessionId] = packageName
        return evicted
    }

    fun remove(sessionId: Int): Boolean {
        failed.remove(sessionId)
        return order.keys.remove(sessionId)
    }

    fun packages(): List<String> = order.entries.map { it.value ?: "#${it.key}" }

    fun size(): Int = order.size
}
