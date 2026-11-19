# RealWear Assistant

Le projet source est en version `1.0.0-preview.5`. L’APK est disponible dans les [versions GitHub](https://github.com/speccy88/RealWear-Assistant/releases). Une compilation locale avec `assembleDebug` produit `app/build/outputs/apk/debug/app-debug.apk`.

Application Android native destinée au RealWear HMT-1 de Fred. Elle communique directement avec l’API OpenAI pour la conversation, le raisonnement, la vision et la recherche. Aucun serveur personnel n’est nécessaire à son fonctionnement.

Le dépôt contient le projet Android Studio et les scripts de configuration. La préversion a été installée et vérifiée sur un HMT‑1 sous Android 8.1. L’accès aux modèles et la reconnaissance vocale dépendent du projet OpenAI et du casque utilisés.

## Installation rapide sur Windows

1. Installe [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) si `adb` n’est pas déjà disponible.
2. Active le débogage USB sur le RealWear, branche-le et accepte l’autorisation de l’ordinateur dans le casque.
3. Télécharge `RealWearAssistant-v1.0.0-preview.5.apk` depuis les versions GitHub.
4. Ouvre un terminal dans le dossier où tu as téléchargé l’APK et lance les commandes suivantes.

```powershell
adb devices
adb install -r .\RealWearAssistant-v1.0.0-preview.5.apk
adb shell am start -n com.fred.realweargpt/.MainActivity
```

Depuis le dossier du projet, tu peux aussi lancer `Install-RealWear.bat -ApkPath "C:\chemin\RealWearAssistant-v1.0.0-preview.5.apk"`. Si tu omets `-ApkPath`, le script utilise l’APK compilé localement.

L’APK est signé avec une clé de développement fournie dans `dev-signing/`. Si un ancien APK du prototype a une autre signature, Android peut refuser la mise à jour avec `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. La désinstallation efface les réglages et la clé stockée : utilise-la seulement si tu acceptes de les reconfigurer.

```powershell
adb uninstall com.fred.realweargpt
adb install .\RealWearAssistant-v1.0.0-preview.5.apk
```

## Configurer la clé par ADB

Python 3.10 ou plus récent est nécessaire pour le script. Arrête toute conversation avant de remplacer la clé.

```powershell
python .\tools\import_key.py
```

Le terminal demande la clé avec une **saisie masquée**. Le script la transfère par l’entrée standard d’ADB vers le stockage privé de l’application; la clé n’apparaît ni dans la commande ni dans son historique. Il ouvre ensuite l’application et vérifie l’existence du fichier chiffré et la disparition du fichier d’importation.

Variantes :

```powershell
# Plusieurs appareils connectés
python .\tools\import_key.py --serial NUMERO_ADB

# ADB installé hors du PATH
python .\tools\import_key.py --adb C:\Android\platform-tools\adb.exe

# Import depuis un fichier que tu possèdes déjà
python .\tools\import_key.py --key-file C:\chemin\openai.key
```

Le fichier local donné à `--key-file` reste sur ton ordinateur. L’application supprime uniquement sa copie d’importation après chiffrement. Le script ne teste pas la facturation ni l’accès aux modèles : ce test a lieu quand tu sélectionnes **Démarrer**.

Cette méthode ADB emploie `run-as`, donc elle fonctionne avec l’APK de développement fourni. Une future version de distribution non débogable nécessitera un autre canal de configuration.

## Configurer la clé par SSH

Cette option suppose que Termux et son serveur OpenSSH sont déjà opérationnels sur le RealWear. Ils servent uniquement au transfert initial; l’application Android ne dépend pas de Termux pour discuter avec OpenAI.

Sur le casque, Termux doit disposer de l’accès au stockage partagé (`termux-setup-storage`). Son nom d’utilisateur se trouve avec `whoami`.

```powershell
python .\tools\import_key.py --transport ssh --host IP_REALWEAR --user UTILISATEUR_TERMUX --port 8022
```

Le script utilise le client OpenSSH de l’ordinateur. Accepte l’empreinte de l’hôte seulement après l’avoir vérifiée selon ta procédure habituelle. L’authentification SSH reste celle de ton installation existante.

Sous Android 8.1, la clé passe temporairement par :

```text
/sdcard/Android/data/com.fred.realweargpt/files/import/openai.key
```

L’application importe cette clé au retour au premier plan, la chiffre avec Android Keystore et supprime le fichier d’importation. Si le lancement automatique échoue, ouvre **RealWear Assistant → Menu → Importer la clé**. La suppression d’un fichier sur mémoire flash ne garantit pas un effacement physique; le transfert ADB vers le stockage privé est donc la méthode privilégiée.

## Premier essai

1. Sans clé, ouvre **Menu → Démonstration**. **Suivant** fait défiler un exemple de texte, de tableau, de schéma et de sources. Aucune API n’est appelée.
2. Importe la clé et sélectionne **Démarrer**. Accorde le microphone et, si tu souhaites utiliser la vision, la caméra.
3. Sur un vrai RealWear, l’application attend la confirmation que WearHF a libéré le microphone avant d’ouvrir la conversation.
4. Pose une question simple en français, puis essaie une demande d’affichage.
5. Termine avec **Arrêter**, le bouton physique **Action**, ou « Termine la conversation ».

Exemples de demandes :

- « Explique-moi le fonctionnement d’un moteur à induction et affiche les trois points importants. »
- « Cherche les dernières informations sur ce produit et montre-moi les sources. »
- « Fais un tableau comparatif avec trois colonnes. »
- « Génère et affiche une illustration d’un moteur à induction en coupe. »
- « Regarde ce que j’ai devant moi et lis l’étiquette. »
- « Agrandis le texte. Page suivante. Reviens au tableau. »
- « Masque le contenu. »
- « Active les commandes du casque. »

Les commandes physiques et les boutons restent utilisables si le réseau ne répond plus. Une commande orale de fermeture passe par l’intelligence OpenAI et dépend donc de la connexion.

## Fonctions de cette version

| Fonction | Implémentation |
|---|---|
| Conversation | GPT-Live, audio bidirectionnel PCM16 mono à 24 kHz sur WebSocket |
| Intelligence et recherches | Délégation Responses hébergée par OpenAI; outil `web_search` |
| Vision | Photo JPEG sur demande, ramenée à 1 280 pixels sur le grand côté, puis envoyée au modèle délégué |
| Texte et tableaux | Vues Android natives; tableaux limités à trois colonnes et douze lignes |
| Images | Photos du casque, images publiques HTTPS vérifiées, images générées à la demande avec GPT Image 2.6 Flare |
| Sources | Liste de liens reçus avec les résultats; ouverture dans le navigateur Android |
| Navigation | Pages, retour à une fiche antérieure, agrandissement, réduction, masquage |
| WearHF | Demande, confirmation et restitution explicites du microphone; bouton Action intégré |
| Clé API | Import ADB privé ou SSH; chiffrement AES-GCM avec Android Keystore |
| Réglages | Modèles, voix, recherche, illustrations et durée maximale de session |
| Démonstration | Interface locale utilisable sans clé ni connexion |

L’affichage automatique est activé par défaut. Les images et tableaux affichés explicitement sont conservés pendant l’explication qui suit. Les pages, photos et échanges récents restent en mémoire tant que l’activité existe; il n’y a pas encore de bibliothèque persistante de conversations ou de documents. Lors d’une nouvelle session dans la même activité, un extrait récent des échanges est fourni comme contexte au modèle vocal.

Les commandes sont numérotées de haut en bas dans la colonne de gauche. Avec WearHF, dis « numéro quatre » pour ouvrir **Menu**. Les onze options de ce sous-menu sont visibles sur un seul écran. Pendant la conversation, seul **Arrêter** reste visible. Les autres commandes passent par la voix, par exemple « page suivante », « montre les sources » ou « prends une photo ». Une image générée occupe la zone de droite en conservant ses proportions.

La caméra n’est pas utilisée en continu. La photo reste en mémoire dans l’application et n’est pas enregistrée dans la galerie. Si une session est active, une photo prise avec le bouton **Photo** est envoyée à OpenAI pour analyse. Hors session, elle reste un aperçu local.

## Bascule entre WearHF et GPT-Live

| Action | Comportement |
|---|---|
| Démarrer | L’application demande `RELEASE_MIC`, attend `MIC_RELEASED`, ouvre GPT-Live, puis démarre sa capture audio. |
| Arrêter | La capture et la lecture audio s’arrêtent. Une fois les périphériques fermés, l’application envoie `MIC_RELEASED` à WearHF. GPT-Live reçoit `session.close`. |
| Bouton physique Action | Le casque revient à l’accueil. L’assistant arrête la session et restitue le microphone en passant à l’arrière-plan. |
| Erreur ou perte de connexion | Le son s’arrête et le microphone est rendu à WearHF. Aucune reconnexion automatique. |
| Retour à l’accueil ou autre application | La session se ferme et le microphone est rendu. |
| Revenir à l’assistant | Une nouvelle session s’ouvre. L’ancienne session payante n’est pas gardée en attente. |

Si Android arrête l’application pendant qu’elle possède le microphone, la prochaine ouverture renvoie `MIC_RELEASED` à WearHF.

La confirmation de fermeture OpenAI peut prendre quelques secondes. L’interface n’autorise pas le redémarrage tant que la libération audio et la fermeture du transport ne sont pas toutes deux confirmées localement. Une fermeture réseau sans confirmation serveur est indiquée comme telle.

## Réglages initiaux

| Paramètre | Valeur |
|---|---|
| Version Android minimale | Android 8.1, API 27 |
| Package | `com.fred.realweargpt` |
| Modèle vocal | `gpt-live-1` |
| Modèle délégué | `gpt-6-luna` |
| Voix | `marin` |
| Recherche Web | Activée |
| Affichage automatique | Activé |
| Images générées | Activées par défaut pour les demandes explicites. Le réglage dans Paramètres permet de les désactiver. |
| Session maximale | 30 minutes, réglable de 1 à 120 minutes |
| API | `wss://api.openai.com/v1/live/sessions` |

Les modèles doivent être disponibles pour le projet OpenAI associé à la clé. Les réglages permettent de les modifier sans recompilation. Le modèle vocal doit rester compatible avec le protocole GPT-Live : remplacer son nom par un modèle Realtime ne convertit pas le protocole. Il n’y a aucune substitution silencieuse de modèle.

Le passage à WearHF ferme la session et arrête sa consommation future, sous réserve de la finalisation du service. L’application n’affiche pas d’estimation monétaire locale : consulte l’usage réel et les contrôles du projet OpenAI. Une alerte de budget ne doit pas être assimilée à une coupure garantie.

## Architecture du projet

| Classe | Responsabilité |
|---|---|
| `MainActivity` | Interface, cycle de vie, permissions, commandes et coordination |
| `LiveClient` / `LiveProtocol` | WebSocket GPT-Live, événements, délégation, fonctions et fermeture |
| `AudioEngine` / `PcmResampler` | Audio natif 48/16 kHz, conversion 24 kHz, focus et nettoyage |
| `WearHfController` | Protocole de propriété du microphone RealWear |
| `SecretStore` | Import et chiffrement de la clé dans le stockage privé |
| `ScreenDeck` | Fiches texte, tableaux, images, sources et historique en mémoire |
| `CameraCapture` | Capture caméra compatible avec l’ancien matériel |
| `ImageLoader` / `UrlPolicy` | Chargement borné d’images HTTPS publiques, sans authentification OpenAI |
| `ImageGenerator` | Appel authentifié à l’API Images avec `gpt-image-2.6-flare`, décodage du JPEG et annulation à la fermeture de la session |
| `ToolCatalog` | Fonctions locales exposées au modèle délégué |
| `AppConfig` | Préférences sans données secrètes |

Les fonctions exécutées sur l’appareil sont limitées à `display_text`, `display_table`, `display_image`, `generate_image`, `capture_photo`, `navigate_screen` et `set_mode`. Aucune fonction n’exécute de commande shell ou de code généré par le modèle. Les réponses s’affichent dans des composants Android natifs, sans exécuter du HTML ou du JavaScript retourné par OpenAI.

## Compiler et modifier

Ouvre le dossier dans Android Studio ou utilise la ligne de commande avec un JDK 17 et le SDK Android 35 :

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

Sur macOS/Linux :

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Versions fixées : Gradle 8.9, Android Gradle Plugin 8.7.3, OkHttp 4.12.0 et AndroidX ExifInterface 1.3.7. Le wrapper Gradle est inclus et son téléchargement est vérifié par SHA-256. L’APK compilé se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

Le SDK de compilation et la cible sont 35; `minSdk` reste 27. Une cible récente n’oblige pas le casque à disposer d’Android 15 : la compatibilité minimale est bien Android 8.1.

`dev-signing/realwear-debug.jks` contient une **clé de signature de développement distribuée avec le code**, avec le mot de passe standard `android` et l’alias `androiddebugkey`. Elle permet de reconstruire des mises à jour du prototype avec la même signature. Elle ne doit pas servir à une application de production ou à une publication Play Store.

## Diagnostic

```powershell
python .\tools\diagnose_device.py
```

Ce script relève seulement le modèle, la version Android, l’architecture, la résolution, la densité, la date du casque et la présence de l’application. Il ne lit ni la clé ni les données privées.

| Symptôme | Vérification |
|---|---|
| ADB indique `unauthorized` | Accepter l’ordinateur dans le casque. |
| `run-as: package not debuggable` | Installer l’APK de développement fourni ou employer l’import SSH adapté à Android 8.1. |
| « WearHF n’a pas confirmé » | Fermer les autres applications audio, revenir à l’accueil et réessayer. Ne pas démarrer deux captures simultanément. |
| Le casque n’est pas détecté comme RealWear | Activer « Forcer l’intégration WearHF » dans les paramètres. |
| HTTP 401 | Remplacer la clé par une clé API valide. |
| Modèle inaccessible / 403 / validation refusée | Vérifier les droits du projet et les modèles choisis. |
| Erreur de connexion TLS | Vérifier le Wi-Fi et la date/heure Android. |
| Le casque s’entend lui-même | Tester le volume, la position du haut-parleur et les effets AEC sur le véritable HMT-1. |
| Photo impossible | Accorder la caméra, fermer l’application caméra native, puis réessayer. |
| Une image Internet ne s’affiche pas | L’URL doit pointer directement vers un JPEG, PNG ou WebP public en HTTPS; pas vers une page HTML. |

## Limites connues

- Pas encore d’essai avec un véritable HMT-1, sa version WearHF et sa chaîne acoustique. Les tests de broadcasts simulés ne remplacent pas ce test matériel.
- Pas encore d’appel facturé avec une clé OpenAI de Fred. Les tests réseau emploient un serveur WebSocket local et des événements de référence.
- Les interruptions naturelles dépendent du comportement GPT-Live, du Wi-Fi et du tampon audio. Cette version utilise une lecture immédiate et une file bornée à trois secondes; aucun événement Realtime non documenté n’est supposé.
- L’import SSH dépend des permissions de stockage Termux. L’import ADB est le parcours privilégié et utilisable sans Termux.
- Les illustrations sont facultatives, dépendent de l’accès du compte et peuvent coûter davantage. Une image générée est toujours identifiée comme telle.
- Ouvrir une source dans le navigateur quitte l’activité et ferme la session vocale.
- Pas de service d’écoute en arrière-plan, pas de vidéo continue, pas de mémoire persistante entre redémarrages.
- Cette APK est débogable pour permettre la configuration ADB. Une clé permanente dans une application mobile demeure récupérable si l’appareil ou l’accès ADB est compromis, même si elle est chiffrée au repos. Utilise une clé de projet dédiée. [OpenAI recommande de conserver les clés permanentes côté serveur](https://developers.openai.com/api/docs/guides/api-keys); le choix de Fred ici est un appareil personnel autonome avec import de sa propre clé.

## Sources techniques consultées

Documentation vérifiée le 18 novembre 2026. L’implémentation emploie GPT-Live et non les anciens événements Realtime du prototype v0.1.

- [OpenAI — WebSockets pour la voix](https://developers.openai.com/api/docs/guides/voice-websockets)
- [OpenAI — Délégation GPT-Live et outils](https://developers.openai.com/api/docs/guides/live-delegation)
- [OpenAI — Gestion des sessions GPT-Live](https://developers.openai.com/api/docs/guides/live-conversations)
- [OpenAI — Instructions GPT-Live](https://developers.openai.com/api/docs/guides/live-prompting)
- [OpenAI — Référence du WebSocket Live](https://developers.openai.com/api/reference/resources/live/primary-websocket)
- [RealWear — Intents du microphone](https://github.com/realwear/devdocs/blob/main/docs/wear-ml/wearhf-intents/microphone-intents.md)
- [RealWear — Libération du microphone](https://github.com/realwear/devdocs/blob/main/docs/developer-examples/microphone-release.md)
- [RealWear — Capture audio](https://github.com/realwear/devdocs/blob/main/docs/developer-examples/audio-capture.md)
- [RealWear — Bouton Action](https://github.com/realwear/devdocs/blob/main/docs/developer-examples/action-button.md)
- [Android — ADB](https://developer.android.com/tools/adb)
- [Android — Keystore](https://developer.android.com/privacy-and-security/keystore)

Le fichier `VALIDATION.md` décrit les vérifications réellement réalisées sur cette livraison. Les tests Robolectric simulent le framework Android 8.1; ils ne remplacent ni un essai sur casque ni une conversation avec une vraie clé API.
