package com.cannes94.boostervoiture

/** Décisions pures du récepteur de sessions (testables sans Android). */
object SessionEvents {
    /**
     * Écrit-on le bloc debug ? Oui si une session a été attachée/détachée, ou si
     * l'erreur du moteur a changé (échec d'attachement, erreur effacée). Sinon un
     * heartbeat NewPipe identique ne réveille pas l'UI toutes les 10 s.
     */
    fun shouldWriteDebug(
        attachedOrDetached: Boolean,
        errorBefore: String?,
        errorAfter: String?
    ): Boolean = attachedOrDetached || errorBefore != errorAfter
}
