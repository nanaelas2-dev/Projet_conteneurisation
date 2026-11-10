package etudiants;

/** Modèle d'un étudiant (l'id est généré par MySQL). */
public record Etudiant(Integer id, String nom, String prenom, String email, Integer age) {

    /** Retourne le message d'erreur de validation, ou null si les données sont valides. */
    public String valider() {
        if (nom == null || nom.isBlank()) return "Le nom est obligatoire";
        if (prenom == null || prenom.isBlank()) return "Le prénom est obligatoire";
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) return "Email invalide";
        if (age == null || age < 16 || age > 99) return "L'âge doit être compris entre 16 et 99";
        return null;
    }

    public Etudiant avecId(int nouvelId) {
        return new Etudiant(nouvelId, nom, prenom, email, age);
    }
}
