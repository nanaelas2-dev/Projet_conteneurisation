package etudiants;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Accès à la table `etudiants` de MySQL via JDBC (opérations CRUD). */
public class EtudiantRepository {
    private final String url;
    private final String user;
    private final String password;

    public EtudiantRepository(String host, String port, String base, String user, String password) {
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + base
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=UTC";
        this.user = user;
        this.password = password;
    }

    private Connection connexion() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** Crée la table si besoin. Réessaie tant que MySQL n'est pas prêt (démarrage Docker/Kubernetes). */
    public void initialiser(int tentativesMax, long pauseMs) throws InterruptedException {
        for (int i = 1; i <= tentativesMax; i++) {
            try (Connection c = connexion(); Statement st = c.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS etudiants (
                            id INT AUTO_INCREMENT PRIMARY KEY,
                            nom VARCHAR(100) NOT NULL,
                            prenom VARCHAR(100) NOT NULL,
                            email VARCHAR(150) NOT NULL UNIQUE,
                            age INT NOT NULL
                        ) CHARACTER SET utf8mb4
                        """);
                System.out.println("Connexion MySQL OK, table 'etudiants' prête.");
                return;
            } catch (SQLException e) {
                System.out.println("MySQL indisponible (tentative " + i + "/" + tentativesMax + ") : " + e.getMessage());
                Thread.sleep(pauseMs);
            }
        }
        throw new IllegalStateException("Impossible de se connecter à MySQL");
    }

    public boolean ping() {
        try (Connection c = connexion()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    // CREATE
    public Etudiant ajouter(Etudiant e) throws SQLException {
        String sql = "INSERT INTO etudiants (nom, prenom, email, age) VALUES (?, ?, ?, ?)";
        try (Connection c = connexion();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.nom().trim());
            ps.setString(2, e.prenom().trim());
            ps.setString(3, e.email().trim());
            ps.setInt(4, e.age());
            ps.executeUpdate();
            try (ResultSet cles = ps.getGeneratedKeys()) {
                cles.next();
                return new Etudiant(cles.getInt(1), e.nom().trim(), e.prenom().trim(), e.email().trim(), e.age());
            }
        }
    }

    // READ (tous)
    public List<Etudiant> lister() throws SQLException {
        List<Etudiant> liste = new ArrayList<>();
        try (Connection c = connexion();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, nom, prenom, email, age FROM etudiants ORDER BY id")) {
            while (rs.next()) liste.add(depuis(rs));
        }
        return liste;
    }

    // READ (un seul)
    public Optional<Etudiant> trouver(int id) throws SQLException {
        try (Connection c = connexion();
             PreparedStatement ps = c.prepareStatement("SELECT id, nom, prenom, email, age FROM etudiants WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(depuis(rs)) : Optional.empty();
            }
        }
    }

    // UPDATE
    public boolean modifier(int id, Etudiant e) throws SQLException {
        String sql = "UPDATE etudiants SET nom = ?, prenom = ?, email = ?, age = ? WHERE id = ?";
        try (Connection c = connexion(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, e.nom().trim());
            ps.setString(2, e.prenom().trim());
            ps.setString(3, e.email().trim());
            ps.setInt(4, e.age());
            ps.setInt(5, id);
            return ps.executeUpdate() > 0;
        }
    }

    // DELETE
    public boolean supprimer(int id) throws SQLException {
        try (Connection c = connexion();
             PreparedStatement ps = c.prepareStatement("DELETE FROM etudiants WHERE id = ?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    private static Etudiant depuis(ResultSet rs) throws SQLException {
        return new Etudiant(rs.getInt("id"), rs.getString("nom"), rs.getString("prenom"),
                rs.getString("email"), rs.getInt("age"));
    }
}
