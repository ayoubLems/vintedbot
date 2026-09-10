# Installation reproductible sur macOS

## Prérequis

- macOS Intel ou Apple Silicon
- Homebrew
- accès au repository privé
- `.env` transféré par canal chiffré ou valeurs à recréer
- dump PostgreSQL si les données existantes doivent être conservées

## Installation

```bash
git clone https://github.com/ayoubLems/vintedbot.git
cd vintedbot
./scripts/setup-macos.sh
```

Le script installe Java 17, PostgreSQL 14, Git et Chrome, télécharge Maven 3.9.9 avec contrôle SHA-512, crée `.env`, le rôle et la base si nécessaire, exécute les tests et construit le JAR.

## Secrets

Ne jamais committer `.env`, un dump, un token, un cookie ou un credential. Un repository privé conserve aussi l'historique Git et ses clones.

Créer `.env` sans afficher les secrets :

```bash
./scripts/init-env.sh
```

Pour transférer un `.env` existant, le copier à la racine puis :

```bash
chmod 600 .env
```

## Migration des données

Sur l'ancien Mac :

```bash
./scripts/backup-db.sh
```

Transférer le dump chiffré. Sur le nouveau Mac :

```bash
./scripts/restore-db.sh /chemin/vers/vinted-AAAAMMJJ-HHMMSS.dump
```

## Démarrage

```bash
./deploy/run-local.sh
```

Arrêter l'ancien bot avant de démarrer le nouveau : un même token Telegram ne doit pas faire du long polling sur deux machines.

## Vérification

```bash
git status --short
java -version
./scripts/mvn.sh --version
./scripts/mvn.sh test
```

Vérifier les logs, `/start`, les recherches restaurées et une notification Vinted.
