package fr.phylisium.firebaul.api;

import org.bukkit.entity.Player;

/**
 * Service principal pour l'interaction avec l'IA.
 * Permet d'envoyer des requêtes à l'IA et de gérer les interactions.
 */
public interface AiService {
    /**
     * Pose une question à l'IA de manière asynchrone pour un joueur donné.
     * Le résultat sera envoyé directement au joueur (chat ou action bar).
     *
     * @param player Le joueur qui pose la question.
     * @param prompt La question ou le message du joueur.
     */
    void askAsync(Player player, String prompt);
    
    /**
     * Vérifie si le service est prêt à recevoir des requêtes.
     * @return true si le service est disponible.
     */
    boolean isReady();
}

