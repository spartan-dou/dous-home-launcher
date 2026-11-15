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
| Toutes les applis | Appui long sur OK | Mettre sur une rangée, retirer de l'accueil, infos sur l'appli, désinstaller, masquer de la liste |
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

**Nouveautés** : à droite, sous les icônes d'état, une affiche à la fois (fondu toutes les 8 s) tirée des rangées que les applis publient pour l'accueil Android TV (« Récemment ajouté » de Wholphin, « Derniers médias ajoutés » de Jellyfin… au choix dans les réglages). Haut depuis les icônes de l'accueil pour y aller, gauche/droite pour changer, OK pour ouvrir. Sa taille suit la place laissée par les rangées ; trop peu de place et il se masque.

**Mise en page** :
- applis en bas ou en haut de l'écran ;
- 1 à 3 rangées d'applis indépendantes, chacune dans son propre bandeau de verre, avec un titre (modifiable, ou aucun) et une taille petite, moyenne ou grande ;
- horloge à gauche, à droite, au centre ou masquée (du côté opposé aux applis), la barre d'état et la légende de la photo prenant le coin libre ;
- couleur d'accent du cadre de focus : blanc, bleu glacier, lavande, rose, ambre, menthe, ou automatique (la couleur la plus vive de chaque photo, tirée de sa copie floutée).

**Mémoire mesurée** (Shield 2019, 2 Go) : ~111 Mo accueil affiché, ~37 Mo pendant un film, dont environ 50 Mo à l'écran imposés par la pile graphique de la Shield à toute appli affichée.

**Entrées** : une rangée spéciale de plus, pour les entrées que le système connaît (HDMI, tuners), ouvertes dans l'appli TV du système. Elle ne s'affiche que s'il y en a ; une box comme la Shield n'en a généralement pas.

**Applis masquées** : une appli masquée depuis « Toutes les applis » se retrouve dans *Réglages → Rangées → Applis masquées*.

**Télécommande** : *Réglages → Télécommande → Associer une touche* attend un appui sur la touche voulue, puis propose l'appui court, l'appui long ou le double appui, et l'appli à ouvrir. Une même touche peut ainsi ouvrir jusqu'à trois applis. Une touche associée ne fait plus ce qu'elle faisait à l'origine. Demande d'activer le service d'accessibilité « Dou's Home Launcher ».

**Depuis le téléphone** (QR code de *Connexion Immich*, affiché tant que cette section est ouverte) :
- *Rangées de l'accueil* : titre, taille et place de chaque rangée, ordre des applis, ajout et retrait ;
- *Sauvegarde et restauration* : télécharge la configuration (rangées, mise en page, albums, touches) dans un fichier, et la restaure après une réinstallation ou sur un autre appareil. Les clés (Immich, cadre photo) n'y figurent pas.

## Cadre photo

Les mêmes photos en plein écran, avec l'heure, la météo, le lieu et la date : de quoi remplacer ImmichFrame, sans serveur ni navigateur.

- **Sur la TV** : c'est un économiseur d'écran (« Cadre photo », à choisir dans *Paramètres → Préférences relatives à l'appareil → Économiseur d'écran*). Android le lance et l'arrête ; la première touche revient à ce qui était affiché.
- **Sur une tablette** : c'est l'appli « Cadre photo », la seule partie visible (l'accueil TV et le service de la télécommande se désactivent d'eux-mêmes hors d'une Android TV, et les réglages n'affichent que ce qui concerne le cadre). Elle garde l'écran allumé tant qu'elle est affichée. Un toucher fait apparaître les boutons (précédente, pause, suivante, réglages, fermer), un glissement change de photo.

Dans les deux cas, rien ne tourne quand le cadre n'est pas à l'écran.

Réglages → Photos propose aussi **uniquement les souvenirs du jour** : quand il y en a, eux seuls défilent ; sinon les albums comme d'habitude.

### Home Assistant

Tant qu'il est affiché, le cadre répond sur le réseau local (port 47915) à l'intégration `dous_home_launcher` (dans [home-assistant-core](https://github.com/spartan-dou/home-assistant-core)). L'adresse et la clé à saisir sont dans *Réglages → Cadre photo*. Elle apporte :

- des interrupteurs **Souvenirs**, **Souvenirs uniquement** et **Pause** ;
- des boutons **Photo suivante**, **Photo précédente** et **Fermer** ;
- des **notifications** par-dessus la photo (les trois plus récentes, avec durée, étiquette et lien ouvert au toucher) ;
- des **valeurs sous l'heure** (température d'une pièce, d'un capteur…), choisies dans les options de l'intégration.

Cadre fermé, ces entités sont « indisponibles » : c'est voulu, l'appli n'écoute rien en arrière-plan. Les valeurs sous l'heure reviennent dès qu'il est rouvert.

Sur une tablette, l'appli compagnon Home Assistant fait le reste (allumer l'écran, ouvrir le cadre, régler la luminosité), ce qui remplace Tasker. Exemple, à adapter (`mobile_app_tablette`, `binary_sensor.tablette_interactive`, `input_boolean.nuit`) :

```yaml
automation:
  # La tablette s'éteint d'elle-même après son délai : on la rallume sur le cadre, sauf la nuit
  - alias: Tablette - cadre photo au repos
    triggers:
      - trigger: state
        entity_id: binary_sensor.tablette_interactive
        to: "off"
    conditions:
      - condition: state
        entity_id: input_boolean.nuit
        state: "off"
    actions:
      - action: notify.mobile_app_tablette
        data:
          message: command_screen_on
      - action: notify.mobile_app_tablette
        data:
          message: command_launch_app
          data:
            package_name: io.github.spartandou.launcher

  # Coucher : le cadre se ferme, l'écran s'éteint ensuite à son délai habituel
  - alias: Tablette - coucher
    triggers:
      - trigger: state
        entity_id: input_boolean.nuit
        to: "on"
    actions:
      - action: button.press
        target:
          entity_id: button.tablette_close

  # Devant la télé : luminosité au minimum, puis retour à la normale
  - alias: Tablette - luminosité selon la télé
    triggers:
      - trigger: state
        entity_id: media_player.shield
    actions:
      - action: notify.mobile_app_tablette
        data:
          message: command_screen_brightness_level
          data:
            command: "{{ 1 if is_state('media_player.shield', 'playing') else 150 }}"
```

Le réveil reprend la première automatisation, déclenchée par `input_boolean.nuit` qui repasse à `off`. `command_launch_app` demande à l'appli compagnon l'autorisation « Afficher par-dessus les autres applis ».

L'API, pour qui veut s'en servir autrement (en-tête `Authorization: Bearer <clé>`, JSON) :

| Requête | Effet |
|---|---|
| `GET /api/state` | Ce que le cadre affiche et ses réglages |
| `PUT /api/settings` | `memories`, `memories_only`, `paused` (au choix) |
| `POST /api/command` | `{"command": "next" \| "previous" \| "close"}` |
| `POST /api/notifications` | `message`, `link`, `duration` (minutes), `tag` ; message vide : efface |
| `PUT /api/sensors` | `{"sensors": [{"icon", "value", "unit"}]}` |

## Sécurité

- La clé d'API Immich est chiffrée sur l'appareil (clé AES de l'Android Keystore).
- La page de configuration n'est servie que pendant que son QR code est affiché, et seulement sur l'adresse du réseau local. Elle reste en HTTP : à faire sur un réseau de confiance.
- Une adresse Immich en `http://` n'est acceptée que sur le réseau local ; ailleurs, `https://`.

## Compilation

JDK 17 ou plus, SDK Android 37, `local.properties` avec `sdk.dir=…`.

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot"
.\gradlew.bat :app:assembleRelease
```

La version release est signée si `RELEASE_KEYSTORE_PATH`, `RELEASE_KEY_ALIAS`, `RELEASE_KEYSTORE_PASSWORD` et `RELEASE_KEY_PASSWORD` sont définies, sinon avec la clé de debug. La CI lance les tests unitaires et une compilation sur chaque pull request (`check.yml`), puis publie un APK signé à chaque push sur `main` une fois ces vérifications passées (`release.yml`). Le numéro de version suit le numéro d'exécution de la CI.
