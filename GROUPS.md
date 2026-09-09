# Bot dans les groupes Telegram et sujets

<!-- Traduit depuis le russe par Ayoub Lemsoudi -->

## Pourquoi le bot ne répond-il pas dans un groupe ?

Par défaut, Telegram active le **mode confidentialité** des bots. Dans un
groupe, le bot ne reçoit alors que les commandes qui lui sont adressées, et
non les messages ordinaires. Un simple lien Vinted peut donc ne pas lui parvenir.

Le champ `can_read_all_group_messages: false` retourné par `getMe` indique que
le mode confidentialité est actif.

## Méthode A : désactiver le mode confidentialité

Le propriétaire du bot effectue cette opération une seule fois dans
[@BotFather](https://t.me/BotFather) :

1. `/mybots` → choisir le bot → **Bot Settings** → **Group Privacy** → **Turn off**.
2. Autre possibilité : `/setprivacy` → choisir le bot → **Disable**.
3. Retirer puis ajouter de nouveau le bot au groupe pour appliquer le réglage.

Le bot pourra ensuite recevoir les liens Vinted envoyés comme messages ordinaires.

## Méthode B : conserver le mode confidentialité

Les commandes sont toujours reçues. Utilisez :

```text
/search https://www.vinted.fr/catalog?search_text=nike&order=newest_first
```

`/track` est un alias. Pour une annonce individuelle :

```text
/parse_link <lien>
```

## Un sujet par recherche

1. Activez les **Sujets** dans les paramètres du groupe.
2. Ajoutez le bot comme administrateur.
3. Accordez-lui l’autorisation **Gérer les sujets**.
4. Ajoutez une recherche par lien ou avec `/search`.

Le bot créera un sujet du type `Vinted · Recherche : …` et y publiera les
nouvelles annonces correspondantes. Sans sujets ou sans autorisation, il
publiera dans la conversation générale.

## Liste de vérification

- [ ] Le bot est ajouté au groupe.
- [ ] Le bot est administrateur avec l’autorisation **Gérer les sujets**.
- [ ] Les **Sujets** sont activés si cette organisation est souhaitée.
- [ ] Le mode confidentialité est désactivé, ou `/search` est utilisé.
- [ ] Un lien de recherche trié par `newest_first` a été envoyé.

Dans le groupe, la commande `/setup` affiche également ces instructions.

## Problèmes fréquents

| Symptôme | Cause probable | Solution |
|---|---|---|
| Aucun effet après l’envoi d’un lien | mode confidentialité actif | méthode A ou `/search <lien>` |
| Impossible de créer un sujet | autorisation manquante | accorder **Gérer les sujets** |
| Aucun sujet créé | sujets désactivés | activer les sujets du groupe |
| Le bot ne répond jamais | bot absent, arrêté ou exclu | vérifier le bot et le processus local |

---

**2026-08-23 – Ayoub Lemsoudi : traduction française et actualisation du guide des groupes.**
