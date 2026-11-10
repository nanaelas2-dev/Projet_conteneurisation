# Gestion d'étudiants : CRUD Java + MySQL, Docker, Docker Compose, Kubernetes

Mini-projet « Conteneurisation et déploiement d'une application CRUD ».

Application CRUD de gestion d'étudiants :
- **Java 17** (serveur HTTP du JDK, sans framework) : API REST et petite page web
- **MySQL 8** via JDBC
- Conteneurisée avec **Docker**, orchestrée avec **Docker Compose**, déployée sur **Kubernetes (Minikube)**

## 1. Architecture

```
Navigateur / curl ──> Service (NodePort 30080) ──> Pods etudiants-app (x2) ──> Service mysql ──> Pod mysql ──> PVC
```

```
gestion-etudiants/
├── pom.xml                      # Maven (driver MySQL + Jackson, jar exécutable)
├── Dockerfile                   # build en deux étapes (Maven puis JRE)
├── docker-compose.yml           # services app + db, réseau, volume
├── k8s/
│   ├── 00-secret.yaml           # identifiants MySQL
│   ├── 01-mysql.yaml            # PVC + Deployment + Service MySQL
│   └── 02-app.yaml              # Deployment (2 réplicas) + Service NodePort
└── src/main/
    ├── java/etudiants/          # Etudiant, EtudiantRepository (JDBC), Main (API REST)
    └── resources/index.html     # interface web
```

### Endpoints

| Méthode | URL | Rôle |
|---|---|---|
| POST | `/api/etudiants` | Créer (Create) |
| GET | `/api/etudiants` | Lister (Read) |
| GET | `/api/etudiants/{id}` | Détail (Read) |
| PUT | `/api/etudiants/{id}` | Modifier (Update) |
| DELETE | `/api/etudiants/{id}` | Supprimer (Delete) |
| GET | `/health` | Disponibilité (vérifie MySQL), utilisé par la *readiness probe* |
| GET | `/live` | Le processus répond, utilisé par la *liveness probe* |
| GET | `/` | Interface web |

Configuration par variables d'environnement : `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `PORT`.
La table `etudiants` est créée automatiquement au démarrage. Tant que MySQL n'est pas joignable, l'application réessaie la connexion (30 tentatives, 2 s d'intervalle).

Exemple de corps JSON :
```json
{"nom": "Dupont", "prenom": "Marie", "email": "marie@univ.fr", "age": 21}
```

## 2. Prérequis

- Docker Desktop (ou Docker Engine) et Docker Compose
- Minikube et `kubectl`
- (Optionnel, pour compiler sans Docker) Java 17 et Maven

Vérification des outils :
```bash
docker --version
docker compose version
kubectl version --client
minikube version
docker info
```

## 3. Étape 1 : Docker

```bash
docker build -t etudiants-app:1.0 .
docker images etudiants-app
```

Test de l'image avec une base MySQL jetable :
```bash
docker network create etudiants-net
docker run -d --name mysql-test --network etudiants-net \
  -e MYSQL_ROOT_PASSWORD=rootpass -e MYSQL_DATABASE=etudiants \
  -e MYSQL_USER=etudiants -e MYSQL_PASSWORD=etudiants mysql:8.0

docker run -d --name app-test --network etudiants-net -p 8080:8080 \
  -e DB_HOST=mysql-test etudiants-app:1.0

docker ps
docker logs -f app-test          # attendre « Connexion MySQL OK »
curl http://localhost:8080/health
```

Arrêt et nettoyage :
```bash
docker stop app-test mysql-test
docker rm app-test mysql-test
docker network rm etudiants-net
```

## 4. Étape 2 : Docker Compose

```bash
docker compose config            # valide le fichier
docker compose up -d --build
docker compose ps
docker compose logs -f
```

Ouvrir http://localhost:8080 et tester le CRUD depuis l'interface, ou en ligne de commande :

```bash
# Create
curl -X POST localhost:8080/api/etudiants -H "Content-Type: application/json" \
  -d '{"nom":"Dupont","prenom":"Marie","email":"marie@univ.fr","age":21}'
# Read
curl localhost:8080/api/etudiants
curl localhost:8080/api/etudiants/1
# Update
curl -X PUT localhost:8080/api/etudiants/1 -H "Content-Type: application/json" \
  -d '{"nom":"Dupont","prenom":"Marie-Claire","email":"marie@univ.fr","age":22}'
# Delete
curl -X DELETE -i localhost:8080/api/etudiants/1
```

Persistance : `docker compose down` (sans `-v`) puis `docker compose up -d` : les données sont conservées grâce au volume `db-data`.

Arrêt :
```bash
docker compose down        # conserve le volume
docker compose down -v     # supprime aussi les données
```

## 5. Étape 3 : Kubernetes avec Minikube

### 5.1 Démarrer le cluster et charger l'image

```bash
minikube start --driver=docker
kubectl get nodes
minikube image load etudiants-app:1.0
```

Les Deployments utilisent l'image locale (`imagePullPolicy: IfNotPresent`).

### 5.2 Déployer

```bash
kubectl apply -f k8s/00-secret.yaml
kubectl apply -f k8s/01-mysql.yaml
kubectl apply -f k8s/02-app.yaml

kubectl get pods -w
kubectl get deployments,services,pvc
```

Les Pods de l'application passent à `1/1 Running` une fois MySQL prêt. Au premier démarrage, MySQL peut être long à s'initialiser : les Pods de l'application peuvent alors redémarrer plusieurs fois avant de se stabiliser (voir Dépannage).

### 5.3 Accéder à l'application

```bash
kubectl port-forward service/etudiants-service 8081:8080
```
Puis ouvrir http://localhost:8081 (la commande reste active dans son terminal). Le port local 8081 évite le conflit avec Docker Compose, qui publie 8080.

Alternative : `minikube service etudiants-service --url`.

## 6. Tests Kubernetes

### 6.1 Disponibilité
```bash
kubectl get pods -o wide
kubectl describe deployment etudiants-app
for i in $(seq 1 10); do curl -s http://localhost:8081/health; echo; done   # le champ "pod" varie
```

### 6.2 Journaux
```bash
kubectl logs deployment/etudiants-app
kubectl logs -l app=etudiants-app --tail=20 --prefix
kubectl logs deployment/mysql
```
Chaque requête est journalisée sous la forme `[nom-du-pod] GET /api/etudiants -> 200`.

### 6.3 Mise à l'échelle
```bash
kubectl scale deployment etudiants-app --replicas=3
kubectl get pods -w
kubectl get deployment etudiants-app

kubectl scale deployment etudiants-app --replicas=2     # retour à 2 (optionnel)
```

### 6.4 Reprise après suppression d'un Pod
```bash
kubectl get pods -l app=etudiants-app
kubectl delete pod <nom-d-un-pod-app>
kubectl get pods -l app=etudiants-app -w     # un Pod de remplacement est créé
curl http://localhost:8081/api/etudiants      # l'application reste accessible
```

Persistance des données (suppression du Pod MySQL) :
```bash
kubectl delete pod -l app=mysql
kubectl get pods -w                           # le Pod MySQL est recréé
curl http://localhost:8081/api/etudiants      # les données sont toujours là (PVC)
```
Pendant le redémarrage de MySQL, `/health` répond 503 et les Pods applicatifs sont retirés du Service (*readiness probe*), puis réintégrés.

Remarque : le `port-forward` est lié à un Pod précis ; s'il est supprimé, relancer la commande.

## 7. Arrêt et nettoyage

```bash
kubectl delete -f k8s/        # supprime aussi le PVC : les données sont perdues
minikube stop                 # arrête le cluster
minikube delete               # supprime le cluster (optionnel)
docker compose down           # si Compose tourne encore
```

## 8. Dépannage

| Symptôme | Piste |
|---|---|
| `ErrImagePull` / `ImagePullBackOff` | L'image n'est pas visible du cluster : `minikube image load etudiants-app:1.0` |
| Pods app en `0/1` ou `CrashLoopBackOff` au premier démarrage | MySQL n'est pas encore prêt : attendre, puis vérifier avec `kubectl logs deployment/etudiants-app`. Les Pods se stabilisent seuls une fois MySQL prêt |
| Port 8080 déjà utilisé | Changer le mapping (`"8081:8080"`) ou arrêter l'autre processus |
| Erreur de connexion MySQL en Compose | Vérifier `docker compose ps` : `db` doit être `healthy` ; l'application réessaie la connexion pendant l'initialisation |
| Avertissement de version de `kubectl` | Sans conséquence ; `minikube kubectl -- <commande>` utilise la version adaptée |

> Remarque : les mots de passe du Secret et du Compose sont des valeurs de démonstration. En production, utiliser des valeurs fortes et ne pas les versionner.
