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
| Rangée d'accueil | Appui long sur OK | Déplacer à gauche ou à droite, vers la rangée du dessus ou du dessous, retirer |
| Toutes les applis | Appui long sur OK | Mettre sur une rangée, ou retirer de l'accueil |
| « Toutes les applis » de l'accueil | Appui long sur OK | Ouvre les réglages |
| Partout | Appui long sur Menu | Ouvre les réglages |
| Partout | Accueil | Revient à la première appli |

Les réglages s'ouvrent aussi par la roue dentée en fin de dernière rangée.

Après 30 s sans toucher la télécommande, les applis s'effacent et seule la photo reste. La première touche les fait revenir, sans rien lancer.

**Sous l'heure**, la météo du jour, ou celle du lendemain à partir de 18 h ([Open-Meteo](https://open-meteo.com), sans compte). La ville se choisit sur la page du téléphone ; laissée vide, c'est celle de l'adresse IP de la box ([ipwho.is](https://ipwho.is)). Une requête par heure au plus, seulement quand l'accueil est affiché.

**En haut à droite**, en icônes ombrées comme l'heure : la connexion (Wi-Fi ou Ethernet) et la RAM utilisée par la Shield, puis le lieu et la date de la photo. La charge du processeur et les piles de la télécommande ne sont pas lisibles par une appli sur la Shield (Android 8+ et service NVIDIA).

**Réglages** (roue dentée, appui long sur Menu) : menu à gauche comme les paramètres Android TV — Photos, Albums (3 colonnes, cochés en premier), Météo (ville modifiable à la télécommande), Affichage, Mise en page, Connexion Immich (QR code).

**Reprendre la lecture** : une rangée spéciale, placée parmi les autres, qui liste ce que les applis publient pour l'accueil Android TV (Wholphin, Jellyfin, M6+…), en choisissant les applis incluses. Sur une vidéo, le fond prend son image après une demi-seconde ; il revient aux photos en quittant la rangée. Demande l'autorisation `READ_TV_LISTINGS` (une fois). Les images de serveurs locaux en HTTP sont autorisées.

Navigation : haut depuis la première rangée atteint « Toutes les applis » et les réglages (petites icônes) ; bas depuis la dernière rangée revient à la première. En haut à droite, une icône ouvre les paramètres de la Shield.

**Mise en page** :
- applis en bas ou en haut de l'écran ;
- 1 à 3 rangées d'applis indépendantes, chacune dans son propre bandeau de verre, avec un titre (modifiable, ou aucun) et une taille petite, moyenne ou grande ;
- horloge à gauche, à droite, au centre ou masquée (du côté opposé aux applis), la barre d'état et la légende de la photo prenant le coin libre ;
- couleur d'accent du cadre de focus : blanc, bleu glacier, lavande, rose, ambre, menthe, ou automatique (la couleur la plus vive de chaque photo, tirée de sa copie floutée).

**Mémoire mesurée** (Shield 2019, 2 Go) : ~111 Mo accueil affiché, ~37 Mo pendant un film, dont environ 50 Mo à l'écran imposés par la pile graphique de la Shield à toute appli affichée.

## Compilation

JDK 17 ou plus, SDK Android 37, `local.properties` avec `sdk.dir=…`.

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot"
.\gradlew.bat :app:assembleRelease
```

La version release est signée si `RELEASE_KEYSTORE_PATH`, `RELEASE_KEY_ALIAS`, `RELEASE_KEYSTORE_PASSWORD` et `RELEASE_KEY_PASSWORD` sont définies, sinon avec la clé de debug. La CI (`.github/workflows/release.yml`) publie un APK signé à chaque push sur `main`.
