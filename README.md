# Système de gestion d'un parking

Application full-stack **Spring Boot 3.3 / Thymeleaf / MySQL 8** qui informatise la gestion des places,
des abonnés, des réservations, des entrées/sorties et de la tarification d'un parking privé.

Le projet expose **deux modes d'accès sur exactement la même couche métier** :

- une **API REST** (`/api/**`, authentification HTTP Basic) ;
- une **application MVC Thymeleaf** (navigateur → `@Controller` → `@Service` → `@Repository`).

---

## 1. Stack technique

| Composant | Version |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.5 (Web, Data JPA, Security, Validation, Thymeleaf) |
| Base de données | MySQL 8.4 |
| Documentation API | springdoc-openapi 2.6 (Swagger UI) |
| Build | Maven 3.9 |
| Tests | JUnit 5 + AssertJ |

---

## 2. Architecture

```
com.example.parking
│
├── config              ParkingProperties, SecurityConfig, OpenApiConfig,
│                       DataInitializer, GlobalModelAttributes
├── controller
│   ├── rest            9 contrôleurs REST (DTO uniquement)
│   └── web             11 contrôleurs MVC Thymeleaf
│
├── dto
│   ├── request         CreateXxxRequest / UpdateXxxRequest + validation
│   └── response        XxxResponse (records), PageResponse, ErrorResponse
│
├── entity              User, Vehicle, ParkingSpace, Reservation,
│   └── enums           Subscription, ParkingSession, Invoice, TariffRule
│
├── repository          Spring Data JPA
│   └── specification   Filtres dynamiques (recherche / filtres combinables)
│
├── service             Interfaces métier
│   └── impl            Implémentations transactionnelles
│
├── exception           ResourceNotFoundException, BusinessException,
│                       InvalidOperationException, ConflictException,
│                       GlobalRestExceptionHandler, WebExceptionHandler
├── mapper              Entité ↔ DTO (aucune entité JPA exposée par l'API)
├── security            SecurityUser, CustomUserDetailsService, CurrentUserProvider
└── util                PricingCalculator, ReferenceGenerator, DateTimeUtils,
                        WebQueryUtils
```

Les deux familles de contrôleurs appellent les **mêmes services** : aucune logique métier n'est dupliquée.

---

## 3. Prérequis

- JDK 21
- Maven 3.9+
- MySQL 8 démarré sur `localhost:3306`

La base `parking_db` est créée automatiquement au démarrage
(`createDatabaseIfNotExist=true`), et le schéma est généré par Hibernate (`ddl-auto: update`).

### Identifiants par défaut

| Paramètre | Valeur | Variable d'environnement |
|---|---|---|
| Hôte | `localhost` | `DB_HOST` |
| Port | `3306` | `DB_PORT` |
| Base | `parking_db` | `DB_NAME` |
| Utilisateur | `root` | `DB_USER` |
| Mot de passe | `root` | `DB_PASSWORD` |
| Port HTTP | `8080` | `SERVER_PORT` |

---

## 4. Lancement

```bash
mvn spring-boot:run
```

Puis ouvrir <http://localhost:8080>.

Documentation interactive de l'API : <http://localhost:8080/swagger-ui.html> (rôle ADMIN).

Pour lancer les tests :

```bash
mvn test
```

---

## 5. Comptes de démonstration

Créés automatiquement au premier démarrage (désactivable avec `parking.seed-demo-data: false`).

| Identifiant | Mot de passe | Rôle | Droits |
|---|---|---|---|
| `admin` | `admin123` | ADMIN | Configuration : places, tarifs, utilisateurs |
| `manager` | `manager123` | MANAGER | Gestion métier : abonnements, facturation, statistiques |
| `operator` | `operator123` | OPERATOR | Opérations quotidiennes : entrées / sorties, véhicules |
| `client` | `client123` | USER | Consultation et ses propres réservations |
| `vip` | `vip123` | USER (catégorie VIP) | Idem, places VIP prioritaires |
| `pmr` | `pmr123` | USER (catégorie PMR) | Idem, places PMR prioritaires |

Le jeu de données initial contient **46 places** (zones A, B, C — types STANDARD, PMR, VIP, ABONNÉ),
6 véhicules et 5 règles tarifaires.

---

## 6. Droits par rôle

| Ressource | ADMIN | MANAGER | OPERATOR | USER |
|---|:---:|:---:|:---:|:---:|
| Places (lecture, plan, disponibilités) | ✔ | ✔ | ✔ | ✔ |
| Places (création / modification / suppression) | ✔ | | | |
| Règles tarifaires | ✔ | | | |
| Utilisateurs | ✔ | | | |
| Véhicules | ✔ | ✔ | ✔ | |
| Réservations | ✔ | ✔ | ✔ | ✔ (les siennes) |
| Entrées / sorties | ✔ | ✔ | ✔ | |
| Abonnements | ✔ | ✔ | | |
| Facturation | ✔ | ✔ | | |
| Statistiques / revenus | ✔ | ✔ | | |

---

## 7. API REST

Authentification **HTTP Basic**, sans session. Les réponses sont toujours des DTO.

### Places

| Méthode | Endpoint | Code |
|---|---|---|
| GET | `/api/parking/spaces?page=0&size=20&sort=number,desc&zone=B&type=VIP&status=LIBRE&search=A0` | 200 |
| GET | `/api/parking/spaces/available?type=VIP&zone=C` | 200 |
| GET | `/api/parking/spaces/{id}` | 200 / 404 |
| POST | `/api/parking/spaces` | 201 + `Location` |
| PUT | `/api/parking/spaces/{id}` | 200 |
| POST | `/api/parking/spaces/{id}/out-of-service` | 200 |
| POST | `/api/parking/spaces/{id}/in-service` | 200 |
| DELETE | `/api/parking/spaces/{id}` | 204 |

### Réservations

| Méthode | Endpoint | Code |
|---|---|---|
| GET | `/api/reservations?status=CONFIRMEE&userId=4&page=0&size=10&sort=expectedArrival,desc` | 200 |
| GET | `/api/reservations/{id}` | 200 / 404 |
| POST | `/api/reservations` | 201 |
| PUT | `/api/reservations/{id}` | 200 / 422 |
| DELETE | `/api/reservations/{id}?reason=...` (annulation) | 200 |
| POST | `/api/reservations/expire-stale` | 200 |

### Entrées / sorties

| Méthode | Endpoint | Code |
|---|---|---|
| POST | `/api/parking/entries` | 201 |
| POST | `/api/parking/exits` | 200 (facture calculée) |
| GET | `/api/parking/sessions?status=EN_COURS&zone=A` | 200 |
| GET | `/api/parking/sessions/ongoing` | 200 |
| GET | `/api/parking/sessions/{id}` | 200 / 404 |
| GET | `/api/parking/occupancy` | 200 |
| GET | `/api/parking/revenue?from=2026-09-01&to=2026-09-30` | 200 |

### Abonnements, facturation, tarifs, statistiques

| Méthode | Endpoint |
|---|---|
| GET / POST | `/api/subscriptions` |
| POST | `/api/subscriptions/{id}/suspend`, `/reactivate`, `/refresh-expired` |
| DELETE | `/api/subscriptions/{id}` (annulation) |
| GET | `/api/invoices`, `/api/invoices/{id}` |
| POST | `/api/invoices/{id}/pay` |
| GET / POST / PUT / DELETE | `/api/tariffs` |
| GET | `/api/statistics/dashboard`, `/occupancy`, `/revenue` |
| GET / POST / PUT / DELETE | `/api/users`, `/api/vehicles` |

### Codes HTTP utilisés

| Code | Cas |
|---|---|
| 200 | Lecture ou mise à jour réussie |
| 201 | Création (en-tête `Location` renseigné) |
| 204 | Suppression |
| 400 | Validation ou règle métier (`BusinessException`) |
| 401 | Non authentifié |
| 403 | Rôle insuffisant |
| 404 | Ressource inexistante (`ResourceNotFoundException`) |
| 409 | Conflit : doublon, chevauchement, place déjà occupée (`ConflictException`) |
| 422 | Transition de statut interdite (`InvalidOperationException`) |

Exemple de réponse d'erreur :

```json
{
  "timestamp": "2026-09-20T23:54:20.56",
  "status": 409,
  "error": "Conflict",
  "message": "La place A01 est deja reservee sur la periode demandee",
  "path": "/api/reservations"
}
```

Exemple d'erreur de validation :

```json
{
  "status": 400,
  "message": "Donnees invalides",
  "fieldErrors": {
    "number": "Le numero de place est obligatoire",
    "zone": "La zone est obligatoire"
  }
}
```

---

## 8. Interface Thymeleaf

| Page | URL |
|---|---|
| Tableau de bord (KPI + plan + véhicules présents) | `/dashboard` |
| Plan du parking (représentation visuelle) | `/spaces/map` |
| Places disponibles | `/spaces/available` |
| Places (recherche, filtres, tri, pagination) | `/spaces` |
| Réservations | `/reservations` |
| Entrée véhicule | `/sessions/entry` |
| Sortie véhicule | `/sessions/exit` |
| Historique entrées / sorties | `/sessions` |
| Abonnements | `/subscriptions` |
| Facturation | `/invoices` |
| Statistiques d'occupation et de revenus | `/statistics` |
| Véhicules | `/vehicles` |
| Utilisateurs | `/users` |
| Tarification | `/tariffs` |

Le plan du parking code par couleur chaque place :
**vert** = libre, **orange** = réservée, **rouge** = occupée, **gris** = hors service.

Les erreurs métier sont affichées à l'utilisateur sous forme de message compréhensible
(`WebExceptionHandler`), et les pages `403` / `404` / `500` sont personnalisées.

---

## 9. Règles métier

### Places

Types `STANDARD`, `PMR`, `VIP`, `ABONNE` — statuts `LIBRE`, `RESERVEE`, `OCCUPEE`, `HORS_SERVICE`.
Les statuts `RESERVEE` et `OCCUPEE` sont pilotés automatiquement : seul le passage
`LIBRE ↔ HORS_SERVICE` est administratif.

### Attribution automatique

La catégorie de l'utilisateur détermine le type de place recherché en priorité, puis les
règles de substitution configurées dans `application.yml` s'appliquent :

```yaml
parking:
  allocation:
    substitution:
      VIP:      [ VIP, ABONNE, STANDARD ]
      PMR:      [ PMR, STANDARD ]
      ABONNE:   [ ABONNE, STANDARD ]
      STANDARD: [ STANDARD ]
```

La zone préférée est essayée en premier, puis la recherche s'étend à tout le parking.

### Réservations

- La date de départ doit suivre la date d'arrivée ; durée comprise entre 30 min et 48 h (configurable).
- Le véhicule doit appartenir à l'utilisateur.
- Deux réservations actives ne peuvent pas se chevaucher sur la même place (409).
- Un même véhicule ne peut pas avoir deux réservations sur la même période (409).
- Seule une réservation `CONFIRMEE` peut être modifiée ou annulée (422 sinon).
- Un compte `USER` ne voit, ne modifie et n'annule que ses propres réservations (403 sinon).
- Les réservations non honorées passent automatiquement en `EXPIREE` et libèrent leur place
  (tâche planifiée toutes les 5 min, réglable via `parking.maintenance.*`).

### Abonnements

Types `JOURNALIER`, `HEBDOMADAIRE`, `MENSUEL`, `ANNUEL` — la date de fin est calculée
automatiquement. La validité est déduite du statut et de la période.
Un véhicule ne peut pas cumuler deux abonnements actifs sur la même période.
Un client `STANDARD` passe en catégorie `ABONNE` à la création d'un abonnement et la perd dès
qu'il ne lui reste plus d'abonnement actif (annulation, suspension, échéance). Les abonnements
échus passent automatiquement en `EXPIRE` (même tâche planifiée que les réservations).

### Entrées / sorties

- Un véhicule déjà dans le parking ne peut pas entrer une deuxième fois (409).
- Le système honore automatiquement la réservation du véhicule (tolérance configurable).
- Sans réservation, une place compatible est attribuée automatiquement.
- Si l'opérateur impose une autre place que celle réservée, la réservation suit le véhicule et
  l'ancienne place est libérée.
- La sortie clôture la session, libère la place et émet la facture.
- L'historique complet des sessions est conservé.

---

## 10. Tarification

Le tarif dépend de la **durée**, de la **zone**, du **type de place**, du **type de véhicule**,
de la **catégorie d'utilisateur** et de l'**abonnement**.

Les règles (`TariffRule`) sont configurables par l'administrateur ; la règle active de plus
forte priorité (puis la plus spécifique) est retenue. Chaque règle porte :

- tarif horaire et **tarif minimum** ;
- **forfait journalier** (plafond par tranche de 24 h) ;
- **franchise gratuite** en minutes ;
- **coefficient de majoration** des dépassements ;
- **réduction abonné** en pourcentage.

S'y ajoutent le coefficient du type de véhicule (moto 0,60 / voiture 1,00 / utilitaire 1,40 /
camion 2,00 / électrique 0,90) et la majoration des places VIP.

### Gestion des dépassements

Exemple : réservation de 3 h, départ réel après 5 h.

```
Durée réelle    : 5 h            → 300 min
Franchise       : 15 min
Durée facturée  : 285 min
  dont normal   : 165 min × 33,33      = 5 500
  dont dépassem.: 120 min × 33,33 × 1,5 = 6 000
Total                                   = 11 500
```

Ce calcul est couvert par les tests unitaires de `PricingCalculator`.

---

## 11. Transactions

Les opérations critiques sont annotées `@Transactional` et exécutées en une seule unité :

| Opération | Étapes dans la transaction |
|---|---|
| Réserver une place | vérifier la période → vérifier la disponibilité → attribuer → créer la réservation → passer la place en `RESERVEE` |
| Entrée parking | vérifier que le véhicule n'est pas déjà présent → chercher la réservation → **verrouiller la place** → passer en `OCCUPEE` → réservation en `EN_COURS` → créer la session |
| Sortie parking | clôturer la session → clôturer la réservation → libérer la place → calculer le tarif → émettre la facture |

La recherche de place utilise un **verrou pessimiste** (`SELECT ... FOR UPDATE`) et l'entité
`ParkingSpace` porte un **verrou optimiste** (`@Version`) : deux entrées simultanées ne peuvent
pas prendre la même place.

---

## 12. Recherche, filtrage, pagination

Toutes les listes importantes (places, réservations, abonnements, sessions, factures,
utilisateurs, véhicules) acceptent :

```
?page=0&size=10&sort=champ,desc&search=texte&status=...&zone=...
```

Les filtres sont construits dynamiquement avec les **JPA Specifications**
(`repository/specification`) et combinés entre eux. L'API renvoie une enveloppe `PageResponse`
(contenu, page, taille, total, tri) ; l'interface Thymeleaf conserve les filtres actifs dans
les liens de pagination.

---

## 13. Tests

```bash
mvn test
```

16 tests unitaires couvrent le calcul tarifaire (franchise, arrondi, tarif minimum, forfait
journalier, coefficients, majoration VIP, réduction abonné, dépassement), le calcul des dates
d'abonnement et la détection de chevauchement des réservations.

9 tests d'intégration (`ParkingRegressionIntegrationTest`, MockMvc + H2 en mode MySQL, profil
`test`) vérifient les parcours complets API et Thymeleaf : droits par rôle, entrées / sorties,
abonnements, remise en service des places et gestion des paramètres invalides. Aucun MySQL
n'est nécessaire pour lancer `mvn test`.

La CI GitHub Actions (`.github/workflows/ci.yml`) exécute `mvn verify` sur chaque push et pull request.
