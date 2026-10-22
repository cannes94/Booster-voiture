# NewPipe patché (annonce de session audio)

NewPipe n'annonce jamais sa session audio : `AudioReactor` n'appelle
`notifyAudioSessionUpdate()` que depuis `onAudioSessionIdChanged()`, un
événement qu'ExoPlayer n'émet pas après la construction du player (l'id de
session est réservé dans le constructeur d'`ExoPlayerImpl`). Résultat : le
booster ne reçoit jamais `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` et ne peut
pas s'attacher, alors que Spotify le fait nativement.

Ce dossier contient un patch minimal et un script de build. Pas de fork : le
patch est versionné ici et appliqué à un commit upstream épinglé.

## Patch

`0001-boost-announce-audio-session.patch` modifie uniquement
`app/src/main/java/org/schabi/newpipe/player/helper/AudioReactor.java` :

- annonce OPEN dans le constructeur (l'id de session ExoPlayer est déjà réservé) ;
- annonce OPEN/CLOSE dans `onIsPlayingChanged` (reprise/pause) ;
- re-annonce OPEN toutes les 10 s pendant la lecture, pour couvrir le cas où
  le booster démarre alors que NewPipe joue déjà ;
- supprime le heartbeat dans `dispose()`.

Le booster ignore une annonce OPEN pour une session déjà attachée : le
heartbeat ne réécrit donc ni les préférences ni la notification.

## Build

Pré-requis (une fois) :

- JDK 21 (NewPipe : `jvmToolchain(21)`). Sur cette machine :
  `/home/fnavello/android-tooling/jdk-21.0.13+9`.
- SDK platform 37.0 + build-tools :
  ```bash
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
      "platforms;android-37.0" "build-tools;37.0.0"
  ```

Puis :

```bash
./newpipe/build-newpipe.sh
# => newpipe/build/app-continuous.apk
adb install -r newpipe/build/app-continuous.apk
```

Le script clone `teamnewpipe/newpipe` dans `newpipe/src` (ignoré par git),
checkout une branche locale `dev` sur le commit épinglé
`d7d6f25c031a43ec62dd37acffa5ce379d0f70cf`, applique le patch et lance
`assembleContinuous`. La branche `dev` est importante : NewPipe calcule le
suffixe du package depuis le nom de branche, et un HEAD détaché donnerait
`.continuous.HEAD`.

## Vérifier

```bash
aapt dump badging newpipe/build/app-continuous.apk | grep package
# package: name='org.schabi.newpipe.continuous' ...
```

Dans le booster : activer le boost, lancer une vidéo NewPipe, ouvrir le debug.
La ligne « Sessions audio » doit afficher `org.schabi.newpipe.continuous`.
Le boost doit s'entendre (A/B à volume voiture fixe). Si le booster a été
activé après le début de lecture, attendre ≤ 10 s (heartbeat) au lieu de
mettre en pause.

## Mise à jour upstream

Le patch est écrit contre le commit épinglé. Pour changer de commit :
modifier `COMMIT` dans `build-newpipe.sh`, relancer ; si `git apply --check`
échoue, régénérer le patch à la main contre `AudioReactor.java` du nouveau
commit (le fichier est petit et stable).
