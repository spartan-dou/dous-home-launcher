# Dou's Home Launcher

Launcher Android TV minimaliste pour NVIDIA Shield (Android 11, 2 Go de RAM) : photos Immich en fond d'écran, horloge, rangée d'applis favorites, surfaces en verre dépoli.

**Règle d'or : consommation de RAM et réactivité avant tout.**
- Aucune bibliothèque Android (ni AndroidX, ni Leanback, ni chargeur d'images) : APK d'environ 130 Ko. Seule dépendance : ZXing core, pour le QR code des réglages.
- Photos décodées directement en mémoire graphique (bitmaps `HARDWARE`) à la taille de l'écran, deux au plus pendant un fondu.
- Le flou est calculé une fois par photo, sur une copie de 240 px (~130 Ko) : Android 11 n'a pas de flou GPU (`RenderEffect` arrive avec Android 12).
- Rien ne tourne quand l'accueil n'est pas affiché. Quand Android réclame de la mémoire (`onTrimMemory`), les photos et la grille d'applis sont libérées.

## Photos

Plusieurs albums Immich au choix, avec en option les **souvenirs du jour** (« il y a X ans ») mélangés aux albums.

Connexion depuis le téléphone : *Réglages de l'accueil* affiche un QR code. La page s'ouvre sur le téléphone. Elle est servie par la TV, sur le réseau local uniquement, à une adresse aléatoire. On y saisit l'adresse du serveur et une **clé d'API limitée** à `album.read`, `asset.read`, `asset.view` et `memory.read`, puis on coche les albums.

## Utilisation

| Où | Touche | Action |
|---|---|---|
| Rangée d'accueil | OK | Ouvre l'appli |
| Rangée d'accueil | Appui long sur OK | Déplacer ou retirer |
| Toutes les applis | Appui long sur OK | Ajouter à l'accueil ou retirer |
| Partout | Accueil | Revient à la première appli |

Après 30 s sans toucher la télécommande, les applis s'effacent et seule la photo reste. La première touche les fait revenir, sans rien lancer.

## Compilation

JDK 17 ou plus, SDK Android 37, `local.properties` avec `sdk.dir=…`.

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot"
.\gradlew.bat :app:assembleRelease
```

La version release est signée si `RELEASE_KEYSTORE_PATH`, `RELEASE_KEY_ALIAS`, `RELEASE_KEYSTORE_PASSWORD` et `RELEASE_KEY_PASSWORD` sont définies, sinon avec la clé de debug. La CI (`.github/workflows/release.yml`) publie un APK signé à chaque push sur `main`.
