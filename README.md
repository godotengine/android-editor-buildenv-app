Godot Android Build Environment (GABE)
======================================

The Godot Android Build Environment is a companion app for the
[Godot Android Editor](https://play.google.com/store/apps/details?id=org.godotengine.editor.v4) that provides a
build environment to perform Gradle builds when running natively on an Android device.

This is done through an Android service which leverages a
[rootfs](https://github.com/godotengine/android-editor-buildenv-rootfs) and [proot](https://github.com/termux/proot) to
set up a build environment able to perform the Gradle builds. See the **How GABE works** section to learn more.

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

## How GABE works

Gradle isn't designed to run on Android, but it _can_ run on normal Linux systems.
Android does use the Linux kernel, but it doesn't provide a standard Linux environment,
and so can't run Gradle out-of-the-box.

One of the first steps when using GABE is to install a "rootfs", which is, in fact,
a Linux rootfs (based on Alpine Linux) which provides that standard Linux environment.
Our rootfs also includes other dependencies that Gradle needs, namely Java and the
Android SDK, built in a special way to run on Android itself.

GABE uses a tool called [proot](https://proot-me.github.io/), which allows executing
programs (like Gradle) inside of a rootfs, such that the programs see the rootfs as
the full system environment.

So, from the perspective of Gradle, it thinks it's running within Alpine Linux!

GABE exposes an Android service that other applications (like Godot) can send messages
to, in order to trigger it to run Gradle on a given project.

## Why a companion app?

There are several reasons for making GABE as a companion app, rather than building
its functionality directly into the Godot editor itself:

- **Licensing:** Godot uses the MIT license, but proot is licensed under the GPL.
  By keeping them separate and using message passing, we don't have to worry about
  the "viral" nature of the GPL affecting Godot. This is also why GABE uses the GPL.
- **Updates:** Keeping GABE separate allows us to update GABE at its own pace, and
  not be tied to Godot's release cycle. It also allows us to potentially support
  multiple Godot versions from a single version of GABE.
- **Size:** Not all developers will want to do Gradle builds, so they shouldn't
  have to pay for the additional space that it takes on their device.
