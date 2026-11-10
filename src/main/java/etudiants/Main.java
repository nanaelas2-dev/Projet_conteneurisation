package etudiants;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * API REST de gestion d'étudiants (serveur HTTP du JDK, aucun framework).
 *
 *   GET    /api/etudiants        liste
 *   GET    /api/etudiants/{id}   détail
 *   POST   /api/etudiants        création
 *   PUT    /api/etudiants/{id}   modification
 *   DELETE /api/etudiants/{id}   suppression
 *   GET    /health               état (vérifie MySQL)  -> readiness probe
 *   GET    /live                 l'application répond  -> liveness probe
 *   GET    /                     page web de gestion
 */
public class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/etudiants";
    private static String hote = "inconnu";
    private static EtudiantRepository repo;

    public static void main(String[] args) throws Exception {
        try {
            hote = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignore) {
            // le nom d'hôte reste "inconnu"
        }

        repo = new EtudiantRepository(
                env("DB_HOST", "localhost"), env("DB_PORT", "3306"), env("DB_NAME", "etudiants"),
                env("DB_USER", "etudiants"), env("DB_PASSWORD", "etudiants"));
        repo.initialiser(30, 2000);

        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer serveur = HttpServer.create(new InetSocketAddress(port), 0);
        serveur.setExecutor(Executors.newFixedThreadPool(8));
        serveur.createContext(BASE, Main::etudiants);
        serveur.createContext("/health", Main::sante);
        serveur.createContext("/live", ex -> envoyer(ex, 200, Map.of("status", "UP", "pod", hote)));
        serveur.createContext("/", Main::page);
        serveur.start();
        System.out.println("Application démarrée sur le port " + port + " (pod/hôte : " + hote + ")");
    }

    private static void etudiants(HttpExchange ex) throws IOException {
        try {
            String suite = ex.getRequestURI().getPath().substring(BASE.length());
            if (suite.startsWith("/")) suite = suite.substring(1);

            Integer id = null;
            if (!suite.isEmpty()) {
                try {
                    id = Integer.parseInt(suite);
                } catch (NumberFormatException e) {
                    envoyer(ex, 400, erreur("Identifiant invalide"));
                    return;
                }
            }

            switch (ex.getRequestMethod()) {
                case "GET" -> {
                    if (id == null) {
                        envoyer(ex, 200, repo.lister());
                    } else {
                        Optional<Etudiant> e = repo.trouver(id);
                        if (e.isPresent()) envoyer(ex, 200, e.get());
                        else envoyer(ex, 404, erreur("Étudiant introuvable"));
                    }
                }
                case "POST" -> {
                    if (id != null) {
                        envoyer(ex, 405, erreur("Méthode non autorisée"));
                        return;
                    }
                    Etudiant e = lireCorps(ex);
                    String probleme = e.valider();
                    if (probleme != null) envoyer(ex, 400, erreur(probleme));
                    else envoyer(ex, 201, repo.ajouter(e));
                }
                case "PUT" -> {
                    if (id == null) {
                        envoyer(ex, 405, erreur("Identifiant requis"));
                        return;
                    }
                    Etudiant e = lireCorps(ex);
                    String probleme = e.valider();
                    if (probleme != null) envoyer(ex, 400, erreur(probleme));
                    else if (repo.modifier(id, e)) envoyer(ex, 200, e.avecId(id));
                    else envoyer(ex, 404, erreur("Étudiant introuvable"));
                }
                case "DELETE" -> {
                    if (id == null) {
                        envoyer(ex, 405, erreur("Identifiant requis"));
                        return;
                    }
                    if (repo.supprimer(id)) envoyer(ex, 204, null);
                    else envoyer(ex, 404, erreur("Étudiant introuvable"));
                }
                default -> envoyer(ex, 405, erreur("Méthode non autorisée"));
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            envoyer(ex, 409, erreur("Cet email existe déjà"));
        } catch (SQLException e) {
            System.err.println("Erreur SQL : " + e.getMessage());
            envoyer(ex, 500, erreur("Erreur base de données"));
        } catch (IOException e) {
            envoyer(ex, 400, erreur("Corps JSON invalide"));
        }
    }

    private static void sante(HttpExchange ex) throws IOException {
        boolean bdOk = repo.ping();
        envoyer(ex, bdOk ? 200 : 503,
                Map.of("status", bdOk ? "UP" : "DOWN", "database", bdOk ? "UP" : "DOWN", "pod", hote));
    }

    private static void page(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            envoyer(ex, 404, erreur("Ressource introuvable"));
            return;
        }
        try (InputStream in = Main.class.getResourceAsStream("/index.html")) {
            byte[] html = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            ex.getResponseBody().write(html);
            ex.close();
        }
    }

    private static Etudiant lireCorps(HttpExchange ex) throws IOException {
        return JSON.readValue(ex.getRequestBody(), Etudiant.class);
    }

    private static Map<String, String> erreur(String message) {
        return Map.of("erreur", message);
    }

    private static void envoyer(HttpExchange ex, int statut, Object corps) throws IOException {
        System.out.println("[" + hote + "] " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " -> " + statut);
        if (corps == null) {
            ex.sendResponseHeaders(statut, -1);
        } else {
            byte[] donnees = JSON.writeValueAsBytes(corps);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(statut, donnees.length);
            ex.getResponseBody().write(donnees);
        }
        ex.close();
    }

    private static String env(String nom, String defaut) {
        String v = System.getenv(nom);
        return (v == null || v.isBlank()) ? defaut : v;
    }
}
