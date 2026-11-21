# SFR Télécommande Express — prototype 0.1

**Télécommande personnelle pour SFR Box 8 TV (décodeur STB8)**. Application expérimentale, non affiliée à SFR.

## Fonctionnement

- Commandes réseau **WebSocket** non officielles : `ws://ADRESSE_DU_DECODEUR:7682/ws`.
- Télécommande : navigation, OK, accueil, retour, veille, lecture/pause, avance/retour, volume, changement de chaîne.
- Raccourcis TF1 / France 2 / M6 / Arte, pavé numérique et chaînes TNT (numérotation SFR de novembre 2026).
- Reconnaissance vocale **sur le téléphone Android** via le service de dictée/reconnaissance disponible, puis transformation des phrases courantes en commandes de télécommande.
- Clavier numérique pleinement prévu. Tentative de saisie alphabétique **expérimentale** (le protocole STB8 public ne documente pas l'envoi de texte et peut ignorer ces touches).
- L'app ne déverrouille pas les restrictions publicitaires de TF1/M6 et ne gère pas la TNT.
- Réseau local uniquement : aucune donnée envoyée à un serveur de l'application. La reconnaissance vocale peut cependant utiliser le service Internet proposé par le téléphone.

## Pour tester sans installation Android

Ouvrir le fichier `SFR-Telecommande-Express.html` dans Chrome sur Android (télécharger puis choisir *Ouvrir avec Chrome*). Renseigner l'adresse IP du **décodeur TV**, pas du modem. Si le navigateur empêche les connexions WebSocket locales ou l'accès au microphone depuis un fichier local, utiliser la version Android native.

## Compilation APK

**Ce dossier contient les sources, pas un APK précompilé.**

- Dans **Android Studio** : ouvrir ce dossier comme projet Gradle, attendre la synchronisation, puis *Build → Build APK(s)*. Installer le fichier `app/build/outputs/apk/debug/app-debug.apk` sur le téléphone.
- Sur **GitHub** : téléverser l'intégralité du projet dans un nouveau dépôt (avec `.github/workflows/build-apk.yml`). Dans l'onglet *Actions*, déclencher *Construire l'APK Android* si besoin, puis télécharger l'artifact `SFR-Telecommande-Express-APK`, qui contient l'APK de test.

L'environnement dans lequel ce projet a été préparé ne dispose pas du SDK Android : les sources ont été contrôlées statiquement, **pas compilées ni testées sur une Box 8 réelle**.

## Première utilisation

1. Activer le Wi-Fi du téléphone sur le même réseau que le décodeur.
2. Trouver l'adresse IP locale du **décodeur SFR Box 8 TV** dans les réglages réseau ou la liste des appareils connectés du routeur.
3. Ouvrir ⚙, saisir l'IP du décodeur (ex. `192.168.1.133`) puis toucher **Connecter**.
4. Vérifier le témoin **Connecté**. Tester d'abord la touche **Accueil** ou une flèche, puis une chaîne.
5. Toucher **Parler** pour tester la reconnaissance vocale : « TF1 », « France 2 », « augmente le son », « pause », « accueil ».

En veille profonde, les commandes locales peuvent être indisponibles. Il faudra allumer le décodeur avec la télécommande d'origine.

## Limitations connues

- Protocole non officiel susceptible de changer selon les mises à jour SFR.
- L'application ne découvre pas encore automatiquement l'adresse IP, elle la mémorise après première saisie.
- L'envoi direct de mots et de phrases vers le clavier de recherche de la TV n'est **pas garanti**. Le mode clavier est un essai de touches alphabétiques, pas un presse-papiers distant confirmé.
- L'état `Connecté` indique une connexion WebSocket établie, pas la confirmation de chaque action : certaines commandes peuvent être rejetées.
- Le volume du téléviseur piloté en infrarouge/CEC n'est pas forcément modifiable par ces commandes ; le volume du décodeur l'est si la Box accepte l'événement.

## Source technique et attribution

Format du protocole inspiré de l'étude publique [GehDoc/sfr-tv-box-remote](https://github.com/GehDoc/sfr-tv-box-remote), sous licence MIT : commandes `buttonEvent`, `getStatus`, clés STB8, port 7682, chemin `/ws`. Ce projet propose une implémentation Android distincte, sans copie des fichiers Python.

Les numéros des chaînes ont été vérifiés sur le [guide SFR novembre 2026](https://static.s-sfr.fr/media/assistance/GUIDE-CHAINE-TV-SFR-NOVEMBRE-2026.pdf).
