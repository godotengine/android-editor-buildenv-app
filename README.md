Godot Android Build Environment (GABE)
======================================

The Godot Android Build Environment is a companion app for the
[Godot Android Editor](https://play.google.com/store/apps/details?id=org.godotengine.editor.v4) that provides a
build environment to perform Gradle builds when running natively on an Android device.

This is done through an Android service which leverages a
[rootfs](https://github.com/godotengine/android-editor-buildenv-rootfs) and [proot](https://github.com/termux/proot) to
set up a build environment able to perform the Gradle builds.

## Getting the app

The app can be installed from the
[Google Play Store](https://play.google.com/store/apps/details?id=org.godotengine.godot_gradle_build_environment)
and from the [Meta Horizon Store](https://www.meta.com/experiences/gabe/26529365196759917/).

Apk binaries can be downloaded directly from the
[releases page](https://github.com/godotengine/android-editor-buildenv-app/releases). **Make sure** to select the
right apk for your target device.

## Building the app

After cloning this project, run the following command in the project root directory to initialize
the project's submodules:
```
git submodule update --init --recursive
```

Run the following command from the root directory to build the app:

- Linux / MacOs:
```
./gradlew build
```

- Windows:
```
gradlew.bat build
```

The generated binaries can be found under the `app/build/outputs/apk` directory.
