# Revue de code — Dou's Home Launcher

Revue faite sur `main` au commit `5338c61`, en lisant l'intégralité des sources Kotlin (16 fichiers, ~3 700 lignes), le manifeste, la configuration Gradle et le workflow CI. Rien n'a été compilé ni exécuté : les constats viennent de la lecture du code. Ce qui reste une hypothèse est signalé comme tel.

Chemins abrégés : `…/launcher/` = `app/src/main/java/io/github/spartandou/launcher/`.

## Résumé : les cinq points les plus importants

1. **Élevée — une réponse météo invalide peut faire planter l'accueil en boucle.** Le corps de la réponse Open-Meteo est mis en cache sans validation (`…/launcher/Weather.kt:62`), puis relu par `JSONObject(json)` sans `try` sur le thread principal toutes les 5 s (`…/launcher/Weather.kt:30`, appelé depuis `…/launcher/HomeActivity.kt:320`). Le cache étant persistant, le plantage se reproduit à chaque redémarrage du launcher. Déclencheur peu probable, mais impact maximal pour une appli HOME.
2. **Moyenne — la clé d'API Immich circule et est stockée en clair.** La page de configuration est servie en HTTP sur toutes les interfaces (`…/launcher/SetupServer.kt:35`), une adresse Immich en `http://` est acceptée (`…/launcher/SetupServer.kt:89`, `AndroidManifest.xml:34`), et la clé est écrite en clair à deux endroits : les préférences (`…/launcher/Prefs.kt:19`) et `photos.json` (`…/launcher/PhotoWall.kt:236` via `…/launcher/Prefs.kt:146`).
3. **Moyenne — fuite de bitmaps quand une photo arrive après que l'accueil a été quitté.** Dans `…/launcher/PhotoWall.kt:195`, si `started` est faux ou qu'une image « média » est affichée, le bitmap `HARDWARE` plein écran et sa copie floutée ne sont ni affichés ni recyclés (idem lignes 160 et 144). Cela contredit la règle « RAM avant tout » du README.
4. **Moyenne — aucune mise en cache des images réseau, un seul thread pour tout, et reconstruction complète des rangées à chaque déplacement vertical.** L'affiche « Nouveautés » est retéléchargée toutes les 8 s (`…/launcher/NewsPanel.kt:166`), même interface masquée ; les téléchargements partagent le thread qui rend les tuiles d'applis (`…/launcher/HomeActivity.kt:46`) ; `shiftRows` recrée toutes les vues (`…/launcher/HomeActivity.kt:253`).
5. **Moyenne — aucun test, aucune vérification en CI, et chaque push sur `main` publie un APK signé.** Pas de `src/test`, le workflow ne lance que `assembleRelease` (`.github/workflows/release.yml:31`), les actions sont épinglées par tag et non par SHA (`.github/workflows/release.yml:16-17`), et `versionCode` reste à 1 (`app/build.gradle.kts:26`).

Points positifs relevés : `allowBackup="false"`, jeton de 128 bits issu de `SecureRandom` pour la page de configuration, échappement HTML systématique des valeurs injectées dans les pages, limites de taille sur les lignes et le corps des requêtes, `distributionSha256Sum` renseigné pour le wrapper Gradle, secrets de signature lus depuis l'environnement, R8 activé, une seule dépendance tierce.

---

## 1. Performance

### P1 — L'affiche « Nouveautés » est retéléchargée à chaque rotation — moyenne
- `…/launcher/NewsPanel.kt:56-61` et `:159-170` : toutes les 8 s, `show()` relance `WatchNext.bytes()` (`…/launcher/WatchNext.kt:156-168`), donc une requête HTTP, un décodage et un recadrage, sans aucun cache. Avec N affiches, le cycle retélécharge indéfiniment les mêmes images.
- La rotation continue quand l'interface est masquée après 30 s : `setUiHidden` ne fait qu'animer l'alpha (`…/launcher/HomeActivity.kt:351-359`), `news.stop()` n'est appelé que dans `onPause` (`…/launcher/HomeActivity.kt:154`).
- Recommandation : installer `android.net.http.HttpResponseCache` (framework, aucune dépendance) dans `cacheDir` avec quelques Mo, ou un petit cache disque indexé par hachage d'URL ; appeler `news.stop()` dans `setUiHidden(true)` et `news.start()` au retour.

### P2 — Un seul thread pour les E/S locales et le réseau — moyenne
- `…/launcher/HomeActivity.kt:46` : `worker` sert à la fois au chargement de la liste d'applis (`:363`), au rendu des bannières (`:631`), au téléchargement des vignettes « Reprendre la lecture » (`:493`, délais de 10 s + 15 s dans `…/launcher/WatchNext.kt:160-161`), à la météo (`:324`) et à la lecture du fournisseur TV (`:600`).
- Un serveur Jellyfin injoignable bloque donc le rendu des tuiles d'applis pendant N × 10 s.
- Recommandation : garder un thread pour le local (PackageManager, ContentResolver) et un petit pool de 2 ou 3 threads pour le réseau.

### P3 — Les rangées sont entièrement reconstruites à chaque touche haut/bas — moyenne
- `…/launcher/HomeActivity.kt:253` appelle `buildRows(NO_FOCUS)`, qui fait `rowsBox.removeAllViews()` (`:401`) puis recrée bandeaux, tuiles, listeners et `TextView`.
- Chaque reconstruction relit et reparse le JSON des rangées : `prefs.appRows` (`…/launcher/Prefs.kt:51-56`) est désérialisé à chaque accès (`…/launcher/HomeActivity.kt:75`, `:406`, `:591`, `…/launcher/Prefs.kt:73`).
- Pour les tuiles de programmes dont l'image n'est pas encore en cache, chaque reconstruction remet un téléchargement en file (`…/launcher/HomeActivity.kt:490-501`) sans dédoublonnage des requêtes en cours. Même chose pour les bannières d'applis (`:631-637`), rendues deux fois si `buildRows` est appelé deux fois de suite (au démarrage : `loadApps` puis `loadPrograms`).
- Recommandation : garder en mémoire la liste `AppRow` désérialisée (invalidée à l'écriture), ne reconstruire que le bandeau qui entre dans la fenêtre, et tenir un ensemble des clés en cours de chargement.

### P4 — Bitmaps non recyclés quand l'affichage est abandonné — moyenne
- `…/launcher/PhotoWall.kt:195` : `main.post { if (started && !media) display(...) }` sans branche `else`. Si l'accueil a été quitté pendant le téléchargement, le bitmap `HARDWARE` (~8 Mo de mémoire graphique en 1080p) et le flou restent alloués jusqu'au prochain GC, que le commentaire de la ligne 279-280 décrit lui-même comme tardif.
- Même défaut ligne 160 (`showLastPhoto`, ni bitmap ni flou recyclés) et ligne 144 (`resumePhotos`, le flou n'est pas recyclé).
- Recommandation : recycler explicitement `bitmap` et `blur` dans la branche négative, comme le fait déjà `showMedia` (`:124-131`).

### P5 — Liste de photos : pics de tas et relances sans plafond — moyenne
- `…/launcher/ImmichClient.kt:120-121` : chaque page de 1 000 assets avec EXIF (`:41-42`, `:138`) est lue en une `String` puis en arbre `JSONObject`, jusqu'à 3 000 par album (`…/launcher/PhotoWall.kt:369`). Sur 2 Go, ce sont plusieurs Mo de tas par page. Recommandation : `android.util.JsonReader` en flux, ou pages de 250.
- `…/launcher/PhotoWall.kt:219-220` : une liste vide n'est jamais considérée comme fraîche. En mode « souvenirs seuls » un jour sans souvenir, toutes les requêtes de liste repartent toutes les 10 s (`RETRY_MS`, `:210`, `:366`) tant que l'accueil est affiché.
- `…/launcher/PhotoWall.kt:210` : en cas de panne serveur, nouvelle tentative toutes les 10 s sans temporisation croissante, chaque tentative pouvant attendre 15 s.
- `…/launcher/PhotoWall.kt:218-222` : si la liste a plus de 6 h et que le rafraîchissement échoue, l'exception empêche d'utiliser la liste en cache pourtant valide.
- Recommandation : mémoriser l'heure du dernier essai même pour une liste vide, temporisation exponentielle (10 s, 30 s, 2 min, 10 min), et retomber sur la liste périmée quand le rafraîchissement échoue.

### P6 — Travail répété sur le thread principal toutes les 5 s — faible
- `…/launcher/HomeActivity.kt:62-67` appelle `updateWeather()` puis `showWeatherLine()` (`:320`, `:335-344`) : `Weather.line()` reparse le JSON du cache et alloue `Calendar` et `SimpleDateFormat` à chaque tick (`…/launcher/Weather.kt:28-43`), puis `setText` est appelé même si le texte n'a pas changé.
- Recommandation : garder la `Line` calculée tant que l'horodatage du cache et l'heure (bascule de 18 h) ne changent pas, et ne toucher à la vue que si le texte diffère.

### P7 — Requêtes au fournisseur TV sans projection — faible
- `…/launcher/WatchNext.kt:36` et `:112` : `query(uri, null, …)` rapatrie toutes les colonnes de `watch_next_program` et de `preview_program`, à chaque `onStart` (`…/launcher/HomeActivity.kt:139`).
- `…/launcher/WatchNext.kt:100-101` : une `Regex` est recompilée à chaque appel de `isRecentlyAdded`.
- Recommandation : passer la liste des colonnes réellement lues ; sortir la regex en constante. Un filtre `selection` côté fournisseur serait mieux encore, mais TvProvider le refuse à certaines applis : à vérifier sur la Shield (hypothèse).

### P8 — Divers — faible
- `…/launcher/PhotoWall.kt:74-75` et `:155-161` : à chaque retour sur l'accueil, `last.jpg` est relu, redécodé et son flou recalculé, alors que `shownBlur` est conservé par `stop()` (`:93`). Réutiliser `shownBlur` évite six passes de flou.
- `…/launcher/HomeActivity.kt:183-190` : `onTrimMemory` libère les photos et la grille mais pas `rowImages` ni `programImages` (`:57`, `:389`), soit ~250 Ko par tuile selon le commentaire de `…/launcher/Apps.kt:35-36`. Les vider à partir de `TRIM_MEMORY_MODERATE`.
- `…/launcher/HomeActivity.kt:135` : tout changement de mise en page (y compris cocher la météo) vide le cache des bannières.
- `…/launcher/PhotoWall.kt:329-337` : la dérive est une animation infinie plein écran ; l'écran est recomposé en continu tant que l'accueil est visible, même interface masquée. C'est un choix assumé (option désactivable), à garder en tête pour la consommation.
- `…/launcher/HomeActivity.kt:46-48`, `…/launcher/PhotoWall.kt:45`, `…/launcher/NewsPanel.kt:37` : quatre exécuteurs jamais arrêtés (`onDestroy`, `…/launcher/HomeActivity.kt:162-166`). Si l'activité est recréée, leurs threads et les références à l'ancienne activité restent.

---

## 2. Sécurité

### S1 — Page de configuration en HTTP clair, écoute sur toutes les interfaces — moyenne
- `…/launcher/SetupServer.kt:35` : `bind(InetSocketAddress(PORT))` écoute sur toutes les interfaces, alors que seule l'adresse locale de `localIpv4Address()` (`:240-244`) est annoncée.
- `…/launcher/SetupServer.kt:49` et `:84-98` : le jeton est dans l'URL et la clé d'API dans le corps du POST, le tout en HTTP. Tout appareil capable d'écouter le réseau local récupère les deux.
- `…/launcher/SettingsActivity.kt:108` : le serveur démarre dès l'ouverture des réglages, quelle que soit la section, et n'est arrêté que dans `onDestroy` (`:117-120`). `SettingsActivity` étant exportée (`AndroidManifest.xml:59`), n'importe quelle appli installée peut l'ouvrir et donc ouvrir le port.
- Recommandation : lier la socket à l'adresse annoncée, ne démarrer le serveur que sur la section « Connexion Immich », l'arrêter dans `onStop`, et l'arrêter après un enregistrement réussi. Le chiffrement du transport demanderait un certificat auto-signé peu pratique sur téléphone ; une alternative est de documenter clairement la limite.

### S2 — Clé d'API envoyée en clair si le serveur Immich est en `http://` — moyenne
- `AndroidManifest.xml:34` : `usesCleartextTraffic="true"` pour toute l'appli (justifié par les images Jellyfin locales).
- `…/launcher/SetupServer.kt:89` accepte `http://` sans distinction, et `…/launcher/ImmichClient.kt:97` joint `x-api-key` à chaque requête, images comprises.
- Recommandation : refuser `http://` quand l'hôte n'est pas une adresse privée ou un nom local, ou au minimum afficher un avertissement explicite sur la page.

### S3 — Clé d'API stockée en clair, à deux endroits — moyenne
- `…/launcher/Prefs.kt:17-19` : `SharedPreferences` en clair.
- `…/launcher/Prefs.kt:145-146` : `sourceKey` contient la clé, et `…/launcher/PhotoWall.kt:235-236` l'écrit telle quelle dans `files/photos.json`.
- `allowBackup="false"` (`AndroidManifest.xml:29`) limite l'exposition aux accès root ou ADB sur un appareil en mode développeur.
- Recommandation : remplacer `sourceKey` par un condensat SHA-256 (correction d'une ligne), et chiffrer la clé avec une clé AES-GCM de l'Android Keystore (API framework, pas besoin d'AndroidX).

### S4 — URI et intents fournis par d'autres applis utilisés sans filtrage — moyenne
- `…/launcher/WatchNext.kt:156-168` : toute URI ne commençant pas par `http` est passée à `contentResolver.openInputStream` : `file://`, `content://` quelconque, etc. Une appli qui publie un programme peut faire lire au launcher une ressource avec les droits du launcher. L'impact est limité, car le résultat n'est que décodé comme image, mais le schéma devrait être restreint à `http`, `https`, `content` et `android.resource`.
- `…/launcher/WatchNext.kt:162` et `:167`, `…/launcher/ImmichClient.kt:67` : `readBytes()` sans plafond. Une URL pointant vers un très gros fichier provoque un `OutOfMemoryError` sur un appareil de 2 Go. Plafonner la lecture (par exemple 8 Mo) et abandonner au-delà.
- `…/launcher/WatchNext.kt:188` puis `…/launcher/HomeActivity.kt:509` : `Intent.parseUri` sur une chaîne fournie par une autre appli, lancée telle quelle. Recommandation : retirer les drapeaux `FLAG_GRANT_*_URI_PERMISSION`, mettre `selector` à `null`, et vérifier que l'activité résolue est exportée.

### S5 — Serveur de configuration : un seul client à la fois, en-têtes sans limite — faible
- `…/launcher/SetupServer.kt:39-41` : boucle d'acceptation mono-thread, `soTimeout` de 10 s par lecture (`:58`). Un appareil du réseau qui ouvre une connexion et envoie un octet toutes les 9 s bloque la page indéfiniment.
- `…/launcher/SetupServer.kt:61-63` : le nombre de lignes d'en-tête n'est pas borné (chaque ligne l'est à 8 Ko).
- `…/launcher/SetupServer.kt:85` : le compteur d'essais ne protège que les POST déjà munis du bon jeton ; avec 128 bits d'entropie ce n'est pas un problème, mais la limite ne sert alors qu'à gêner l'utilisateur légitime.
- Recommandation : délai global par requête, 50 lignes d'en-tête au plus.

### S6 — Chaîne de publication — moyenne
- `.github/workflows/release.yml:16-17` : `actions/checkout` et `actions/setup-java` référencées par tag (`v4`) dans un job qui manipule le keystore et dispose de `contents: write` (`:9-10`). Épingler par SHA de commit.
- `.github/workflows/release.yml:22` : le secret est interpolé directement dans le script shell. Le passer par `env:` et écrire `printf '%s' "$KEYSTORE_B64" | base64 -d`.
- `.github/workflows/release.yml:5-6` et `:36` : tout push sur `main` publie un APK signé, sans test, sans lint, sans environnement protégé. Une relance manuelle sur le même commit échoue, le tag `build-<sha>` existant déjà.
- `gradle/wrapper/gradle-wrapper.jar` est un binaire versionné sans étape de validation (`gradle/actions/wrapper-validation`). Pas de `gradle/verification-metadata.xml` pour la dépendance ni pour le plugin (`build.gradle.kts:2`, `app/build.gradle.kts:55`).
- `app/build.gradle.kts:48` : sans variables d'environnement, la variante release est signée avec la clé de debug, sans avertissement. Faire échouer la tâche en CI si la configuration `release` est absente.
- Pas de Dependabot ni de Renovate : `com.google.zxing:core:3.5.3` (`app/build.gradle.kts:55`) a une version 3.5.4 sur Maven Central (vérifié le jour de la revue). Aucune vulnérabilité connue ne concerne l'usage fait ici (génération de QR code).

### S7 — Divers — faible
- `…/launcher/Weather.kt:80` : sans ville saisie, l'adresse IP publique est envoyée à `ipwho.is`. C'est documenté dans le README ; un réglage « pas de géolocalisation par IP » serait plus net.
- `…/launcher/SetupServer.kt:95` et `…/launcher/HomeActivity.kt:512`, `:966` : les messages d'exception bruts sont affichés à l'utilisateur.

---

## 3. Design et architecture

### D1 — Aucun garde-fou contre un cache corrompu sur le thread principal — élevée
- Météo : voir le point 1 du résumé (`…/launcher/Weather.kt:30` et `:62`). Correction : parser la réponse dans `refresh()` avant de l'enregistrer, et entourer `line()` d'un `try` qui efface le cache en cas d'échec.
- Rangées : `…/launcher/Prefs.kt:54-55` et `:203` (`JSONArray(json)`, `getJSONObject`, `getString`) lèvent une `JSONException` sur des préférences abîmées, depuis `onStart` de l'accueil. Même traitement : `try`, puis retour à la configuration par défaut.
- Pour un launcher, un plantage au démarrage laisse l'appareil sans écran d'accueil ; un `Thread.setDefaultUncaughtExceptionHandler` qui efface les caches (`weather`, `photos.json`) avant de laisser le processus mourir serait un filet de sécurité bon marché.

### D2 — `HomeActivity` concentre presque toute la logique — moyenne
- `…/launcher/HomeActivity.kt` fait 993 lignes et mêle cycle de vie, navigation au clavier (`:212-257`), construction des rangées (`:399-464`), carrousel (`:740-822`), programmes (`:472-504`, `:590-612`), grille (`:864-903`), dialogues d'édition (`:906-957`), météo (`:319-344`) et placement du panneau (`:558-587`).
- Le calcul de la fenêtre de rangées (`:413-428`) et la navigation (`:219-251`) sont de la logique pure, noyée dans du code de vues, donc non testable.
- Recommandation : extraire un `RowWindow` sans dépendance Android (rangées, épinglée, décalage, cible du focus), un `RowsView`, un `CarouselLayout` et un `AllAppsGrid`.

### D3 — Aucun test — moyenne
- Il n'y a ni `app/src/test` ni `app/src/androidTest`, et aucune dépendance de test dans `app/build.gradle.kts:53-56`.
- Candidats immédiats, sans Robolectric : `parseForm`, `readLine` et `escape` (`…/launcher/SetupServer.kt:199-228`), `portrait` (`…/launcher/WatchNext.kt:147-150`), `boxPass` (`…/launcher/Blur.kt:44-68`), `legacyRows` et `AppRow.fromJson` (`…/launcher/Prefs.kt:59-70`, `:198-207`), `describe` (`…/launcher/Weather.kt:113-127`), `toPhoto` (`…/launcher/ImmichClient.kt:73-92`).
- Recommandation : ajouter JUnit, lancer `./gradlew test lint` dans un workflow distinct sur chaque pull request, et conditionner la publication à sa réussite.

### D4 — Duplication — faible
- Trois clients HTTP : `…/launcher/ImmichClient.kt:94-125`, `…/launcher/Weather.kt:100-110`, `…/launcher/WatchNext.kt:156-172`.
- Trois boucles de sous-échantillonnage : `…/launcher/PhotoWall.kt:349-361`, `…/launcher/Blur.kt:14-20`, `…/launcher/WatchNext.kt:175-182`.
- Deux recadrages centrés : `…/launcher/HomeActivity.kt:517-527` et `…/launcher/NewsPanel.kt:189-208`.
- Le menu « déplacer / retirer » existe deux fois : `…/launcher/HomeActivity.kt:931-957` et `…/launcher/SettingsActivity.kt:329-336`. `rowName` aussi (`…/launcher/HomeActivity.kt:959`, `…/launcher/SettingsActivity.kt:481`), ainsi que la recherche du libellé d'une appli (`…/launcher/SettingsActivity.kt:291` et `:408-412`).
- `FocusFrame` et `AccentDisc` (`…/launcher/Accent.kt:61-118`) partagent la même gestion d'état.
- Recommandation : un `Http.get(url, maxBytes, headers)`, un `Images.decode(bytes, maxW, maxH, config)` et un `Images.centreCrop`, et un `RowEditor` commun aux deux écrans.

### D5 — Threads bruts et cycle de vie — moyenne
- `…/launcher/SettingsActivity.kt:444-455` : après le géocodage, un `AlertDialog` est affiché sans vérifier `isDestroyed`. L'activité étant en `noHistory` (`AndroidManifest.xml:58`), appuyer sur Accueil pendant la requête (jusqu'à 10 s) mène à une `BadTokenException`. Les autres threads du fichier font la vérification (`:200`, `:287`, `:392`).
- `…/launcher/SettingsActivity.kt:110`, `:197`, `:284`, `:389`, `:444` : cinq `Thread { }` anonymes qui capturent l'activité.
- `…/launcher/HomeActivity.kt:530` et `:538` : `mediaTarget` est écrit sur le thread principal et lu sur `mediaWorker` sans `` `@Volatile` ``. `…/launcher/NewsPanel.kt:38-39` : `width` et `height` sont modifiés par `fit()` sur le thread principal et lus par `crop()` sur le worker (`:190`).
- Recommandation : un exécuteur par activité, arrêté dans `onDestroy`, et un helper `postIfAlive`.

### D6 — État global et indices magiques — faible
- `…/launcher/Accent.kt:18-21` : singleton mutable avec un unique `onChange`, écrasé par la dernière activité qui s'y abonne. `…/launcher/GlassView.kt:116-117` : ensemble statique de vues et bitmap partagé.
- `…/launcher/SettingsActivity.kt:574-577` : `ALBUMS = 1` et `ROWS = 4` dépendent de l'ordre de la liste `sections` (`:53-62`) ; `sections.lastIndex` désigne la connexion (`:112`, `:433`).
- Accès par position d'enfant : `…/launcher/HomeActivity.kt:298`, `…/launcher/PhotoWall.kt:93`, `:316`, `:346`. `Prefs.ACCENT_AUTO = 6` (`…/launcher/Prefs.kt:157`) est lié à la taille de `ACCENTS` et au tableau de ressources.
- Recommandation : une `enum` de sections, des identifiants de vues dans `slide.xml`, `ACCENT_AUTO = ACCENTS.size`.

### D7 — Chaînes en dur et langue figée — faible
- Textes français hors de `strings.xml` : toute la page HTML (`…/launcher/SetupServer.kt:85-161`), `…/launcher/ImmichClient.kt:103-105`, `…/launcher/Weather.kt:42` et `:114-126`, `…/launcher/HomeActivity.kt:371`, `…/launcher/Prefs.kt:61`, `…/launcher/WatchNext.kt:50`.
- `…/launcher/ImmichClient.kt:86` force `Locale.FRENCH` et la date formatée est stockée dans le cache, ce qui la fige.
- Sans objet tant que le projet reste personnel, bloquant pour une traduction.

### D8 — Touche Retour sur Android 16 et plus — moyenne (hypothèse)
- `…/launcher/HomeActivity.kt:176-180` et `…/launcher/SettingsActivity.kt:122-131` reposent sur `onBackPressed`, avec `targetSdk = 37` (`app/build.gradle.kts:25`) et sans `android:enableOnBackInvokedCallback` dans le manifeste.
- Hypothèse non testée : à partir d'un `targetSdk` 36, sur un appareil Android 16 ou plus, `onBackPressed` n'est plus appelé. Sans effet sur la Shield (Android 11), mais la fermeture de « Toutes les applis » et le retour depuis la page d'une rangée ne fonctionneraient plus sur un appareil récent.
- Recommandation : enregistrer un `OnBackInvokedCallback` quand `SDK_INT >= 33`, ou déclarer explicitement `enableOnBackInvokedCallback="false"`.

### D9 — Divers — faible
- `…/launcher/KeyService.kt:19-28` : si l'appli cible n'est plus installée, l'appui est consommé (`true`) mais pas le relâchement (`false`), ce qui donne un événement incohérent au système.
- `…/launcher/KeyService.kt:17` : `Prefs(this)` est instancié à chaque événement de touche.
- `…/launcher/WatchNext.kt:101` : le motif `new` reconnaît aussi « News », ce qui sélectionne par défaut des chaînes d'actualités.
- `…/launcher/ImmichClient.kt:30` et `:54` : `as JSONArray` sur une réponse inattendue donne un message « ClassCastException » à l'utilisateur.
- `settings.gradle.kts:16` : `rootProject.name = "Immich Launcher"`, ancien nom. `gradle.properties:3` : `android.useAndroidX=true` alors qu'AndroidX n'est pas utilisé.
- `app/build.gradle.kts:26-27` : `versionCode` et `versionName` fixes ; les dériver du nombre de commits ou du numéro d'exécution CI pour que les versions soient ordonnées.
- Le dépôt est public sans fichier de licence.

---

## 4. Idées de fonctionnalités

1. **Économiseur d'écran Android TV.** Un `DreamService` qui réutilise `PhotoWall` (photos, légende, horloge) : le diaporama servirait aussi d'écran de veille, sans autre appli.
2. **Diaporama hors ligne.** Garder les 20 à 50 derniers aperçus dans `cacheDir` (plafond en Mo) et les faire tourner quand Immich est injoignable, au lieu du message d'erreur. Se combine avec le cache HTTP de P1.
3. **Actions sur la photo affichée.** Une touche pour mettre en pause, revenir à la photo précédente ou passer à la suivante, et « ne plus afficher cette photo » (liste d'exclusion locale).
4. **Autres sources Immich.** Favoris, personnes, ou tirage aléatoire côté serveur, pour ne plus rapatrier jusqu'à 5 000 entrées par liste ; règle aussi une partie de P5.
5. **Remappage de touches généralisé.** `KeyService` ne gère que `BUTTON_12` : proposer d'autres boutons, l'appui long et le double appui, avec un écran « appuyez sur la touche à associer ».
6. **Export et import de la configuration depuis la page du téléphone.** Avec `allowBackup="false"`, une réinstallation perd rangées et réglages. Un JSON téléchargeable et réimportable (sans la clé d'API), et l'édition des rangées depuis le téléphone.
7. **Gestion des applis depuis la grille.** Dans le menu d'appui long : « Infos sur l'appli » (`ACTION_APPLICATION_DETAILS_SETTINGS`), « Désinstaller », et « Masquer de la liste ».
8. **Mode nuit.** Entre deux heures choisies, assombrir la photo et réduire l'horloge, ou n'afficher que l'horloge.
9. **Météo plus riche.** Prévision sur trois jours au focus de la ligne météo, et choix de l'unité.
10. **Rangée « Entrées ».** Les entrées HDMI et tuners exposés par `TvInputManager`, comme sur l'accueil d'origine.
