# Booster Voiture

Amplificateur de volume perso pour écouter podcasts / musique en voiture
malgré le bruit moteur. Android uniquement, sans pub, sans compte.

## Fonctionnement

- Monte le volume système `STREAM_MUSIC` au max à l'activation (restauré à la coupure).
- Ajoute un gain logiciel global (`LoudnessEnhancer`, session 0) de 100 à 150 %.
- Preset « Voix » : +3 dB sur 1-4 kHz, aucune coupe (les basses coupées
  faisaient baisser le niveau global perçu).

Ordre d'utilisation :

1. À l'arrêt, régler le boost dans l'appli.
2. Lancer Spotify / YouTube Music.
3. Brancher Android Auto. Ensuite, volume avec les commandes voiture.

## Limites connues

- L'effet global (session audio 0) dépend du fabricant : certains
  autoradios l'ignorent. L'écran d'aide de l'appli décrit un protocole
  A/B (volume voiture fixe) : si ON brut 130 % = OFF, c'est le chemin
  audio de la voiture qui contourne l'effet, pas un bug de réglage.
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
