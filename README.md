# Flux TV

Une interface IPTV légère et rapide pour Android TV / Google TV, pensée pour les favoris.

## Ce que fait l'appli

- Connexion Xtream (serveur, identifiant, mot de passe), saisie une seule fois.
- Ouverture directe sur tes **favoris**, en grandes tuiles avec logos.
- Liste des chaînes par catégorie dans la colonne de gauche.
- Lecteur ExoPlayer (Media3) réglé pour démarrer vite, avec bascule automatique `.ts` / `.m3u8`.
- Guide « en cours / ensuite » dans le bandeau d'infos.
- Liste mise en cache : l'appli s'ouvre instantanément, la mise à jour se fait en arrière-plan.

## Télécommande

| Où | Touche | Action |
|---|---|---|
| Accueil | OK | Lancer la chaîne |
| Accueil | Appui long sur OK (ou Menu) | Ajouter / retirer des favoris |
| Lecteur | ▲ / CH+ | Chaîne suivante |
| Lecteur | ▼ / CH− | Chaîne précédente |
| Lecteur | OK, ◀, ▶ | Afficher / masquer les infos |
| Lecteur | Retour | Revenir à l'accueil |

## Installation

L'APK est compilé automatiquement par GitHub Actions à chaque modification, et publié dans **Releases** sous le nom `flux-tv.apk`.

Sur le boîtier : installe l'appli **Downloader**, autorise l'installation d'applis inconnues pour elle, puis ouvre le lien de l'APK.

Tes identifiants IPTV ne sont jamais dans ce code : ils restent uniquement sur ton boîtier.
