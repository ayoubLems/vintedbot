# Maintenir le bot en ligne 24 h/24

<!-- Traduit depuis le russe par Ayoub Lemsoudi -->

Un bot Telegram utilisant le long polling doit fonctionner en permanence. Il
lui faut donc un hôte qui ne se met pas en veille. Les modèles de déploiement
se trouvent dans le dossier `deploy/`.

L’analyse fonctionne principalement avec HTTP et Jsoup. Chrome est facultatif,
ce qui permet au bot de fonctionner avec environ 256 Mo de mémoire.

## Option 1 : Oracle Cloud Always Free

Une machine ARM gratuite peut héberger le bot, PostgreSQL et le mode de secours
Chrome. Une carte peut être demandée pour vérifier l’identité.

1. Inscrivez-vous sur <https://www.oracle.com/cloud/free/> et choisissez une région proche.
2. Créez une VM dans **Compute → Instances → Create** :
   - forme : **Ampere (ARM) VM.Standard.A1.Flex**, 1 à 2 OCPU et 6 Go de RAM ;
   - image : **Ubuntu 22.04** ;
   - téléchargez la clé SSH privée ;
   - dans le réseau, autorisez uniquement SSH.
3. Installez Docker :

   ```bash
   ssh -i key.pem ubuntu@<IP>
   sudo apt update && sudo apt install -y docker.io docker-compose-plugin git
   sudo usermod -aG docker $USER && newgrp docker
   sudo systemctl enable --now docker
   ```

4. Installez et démarrez le projet :

   ```bash
   sudo mkdir -p /opt/vinted-telegram-bot
   sudo chown $USER /opt/vinted-telegram-bot
   git clone <votre-depot> /opt/vinted-telegram-bot
   cd /opt/vinted-telegram-bot
   cp .env.example .env && nano .env
   docker compose up -d --build
   ```

Le paramètre `restart: unless-stopped` de `docker-compose.yml` assure le
redémarrage automatique. Le service systemd `deploy/vinted-bot.service` peut
également être utilisé.

Journaux : `docker compose logs -f bot`.

Mise à jour : `git pull && docker compose up -d --build`.

## Option 2 : Fly.io et PostgreSQL hébergé

Cette solution utilise l’image légère sans Chrome. Les adresses IP de centres
de données peuvent être davantage exposées aux protections anti-bot de Vinted.

1. Créez une base PostgreSQL chez Neon ou Supabase et récupérez la connexion.
2. Installez `flyctl`, puis exécutez depuis `deploy/` :

   ```bash
   fly launch --no-deploy --dockerfile ../Dockerfile.slim
   fly secrets set BOT_TOKEN=xxx BOT_USERNAME=yourbot \
       DB_URL="jdbc:postgresql://<host>/<db>?sslmode=require" \
       DB_USER=<user> DB_PASSWORD=<pass> SELENIUM_ENABLED=false
   fly deploy
   ```

Le fichier `deploy/fly.toml` contient une configuration de départ. Koyeb et
Railway peuvent utiliser `Dockerfile.slim` de manière similaire.

## Option 3 : Mac ou mini-PC personnel

Le bot peut fonctionner gratuitement sur votre propre ordinateur, qui doit
rester allumé. Après avoir créé `.env`, installer l’agent `launchd` :

```bash
./scripts/install-launchd.sh
```

Le plist est généré dans `~/Library/LaunchAgents/` avec le chemin réel du
checkout et n’est jamais versionné.

Pour empêcher la mise en veille pendant une exécution manuelle :

```bash
caffeinate -s ./deploy/run-local.sh
```

Sur Raspberry Pi ou Linux, utilisez `deploy/vinted-bot.service`.

## Comparaison

| Hébergement | Toujours actif | Gratuit | Carte requise | Secours Chrome |
|---|---:|---:|---:|---:|
| Oracle Cloud Free | ✅ | ✅ selon l’offre | généralement | ✅ |
| Fly.io/Koyeb + Neon | ✅ | selon les limites | souvent | ❌ |
| Mac ou Raspberry Pi | tant qu’il reste allumé | ✅ | non | ✅ |

## Protection anti-bot Vinted

Le symptôme habituel est le message « Vinted bloque temporairement les
requêtes ». La version maintenue par Ayoub Lemsoudi applique :

- une vérification automatique toutes les 60 secondes ;
- une pause globale de 15 minutes après un blocage ;
- la même pause pour les boutons de vérification manuelle ;
- aucune notification issue du HTML lorsque l’API ordonnée est indisponible.

Variables correspondantes :

```dotenv
MONITOR_INTERVAL_MS=60000
MONITOR_BACKOFF_MS=900000
```

Un proxy peut être configuré avec `VINTED_PROXY`, sous réserve de respecter les
conditions d’utilisation de Vinted. Évitez les vérifications manuelles répétées.

---

**2026-08-23 – Ayoub Lemsoudi : traduction française, documentation du déploiement et paramètres anti-bot.**
