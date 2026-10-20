# Flux TV

Une interface IPTV légère et rapide pour Android TV / Google TV, pensée pour les favoris.

## Ce que fait l'appli

- Trois sources de chaînes : **chaînes gratuites** en un clic (liste publique iptv-org pour la France), **lien M3U** (box internet, tuner TNT réseau, abonnement), ou **codes Xtream**.
- Rangée **Applis** pour ouvrir toutes les applis du boîtier, même sans abonnement.
- Interface inspirée d'Apple TV : heure et date en haut, rangées de chaînes, lueur colorée qui prend la couleur du logo sélectionné.
- Ouverture directe sur tes **favoris**, dans une étagère en verre dépoli.
- Une rangée par catégorie, qui remonte en haut quand tu la sélectionnes.
- Lecteur ExoPlayer (Media3) réglé pour démarrer vite, avec bascule automatique `.ts` / `.m3u8`.
- Guide « en cours / ensuite » dans le bandeau d'infos.
- Liste mise en cache : l'appli s'ouvre instantanément, la mise à jour se fait en arrière-plan.

## Télécommande

| Où | Touche | Action |
|---|---|---|
| Accueil | ◀ ▶ ▲ ▼ | Parcourir les rangées (Favoris puis catégories) |
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
