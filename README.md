# Booster Voiture

Amplificateur de volume perso pour écouter podcasts / musique en voiture
malgré le bruit moteur. Android uniquement, sans pub, sans compte.

## Fonctionnement

- Monte le volume système `STREAM_MUSIC` au max à l'activation (restauré à la coupure).
- Ajoute un gain logiciel (`LoudnessEnhancer`) de 100 à 150 %, attaché à la session audio
  de chaque lecteur (Spotify, YouTube Music...) via le broadcast
  `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`.
- Preset « Voix » : +3 dB sur 1-4 kHz, aucune coupe (les basses coupées
  faisaient baisser le niveau global perçu).
- NewPipe : variante patchée fournie (`newpipe/`), qui annonce sa session audio
  comme Spotify. Voir [docs/newpipe.md](docs/newpipe.md).
- Option « Effet global session 0 » (OFF par défaut) : moteur
  `LoudnessEnhancer(0)` pour tester les applis qui n'annoncent aucune session
  (YouTube natif). Déprécié par Android, inopérant sur beaucoup d'appareils ;
  actif seulement si le booster est ON, et jamais pendant un appel.

Ordre d'utilisation :

1. À l'arrêt, régler et ACTIVER le boost dans l'appli.
2. Lancer (ou relancer) la lecture dans Spotify / YouTube Music. Le bloc debug doit
   afficher le lecteur dans « Sessions audio ».
3. Brancher Android Auto. Ensuite, volume avec les commandes voiture.

## Limites connues

- Le boost ne marche que si le lecteur annonce sa session audio. Si « Aucune session
  audio détectée » reste affiché, ce lecteur ne supporte pas les effets système
  (utiliser NewPipe patché pour YouTube, ou tester l'option session 0).
- L'effet global session 0 reste déprécié par Android et ignoré par beaucoup
  d'appareils : c'est une option de test, OFF par défaut.
- YouTube vidéo est bloqué en roulant par Android Auto (normal) ;
  YouTube Music et Spotify passent.
- Coupure auto pendant les appels, reprise ensuite.

## Protocole A/B (volume voiture fixe)

1. OFF (volume système seul) → référence.
2. ON brut 130 % (voix OFF) → doit être plus fort que 1.
3. ON voix 130 % puis 150 % → ne doit plus être plus faible que 2.

Le bloc debug de l'appli affiche : volume sys actuel/max, gain appliqué
en mB, erreur éventuelle, réglages EQ.

## Compiler

```bash
gradle assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Nécessite le SDK Android (API 34) et JDK 17.

## Santé

Un boost prolongé à 150 % peut fatiguer l'audition et saturer les petits
haut-parleurs. Monter progressivement, baisser si ça grésille.
Usage à tes risques.
