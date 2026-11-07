Casts through the local proxy no longer stop after a few minutes with the phone's screen off.

### Install

Download **`snapcast-1.1.4-arm64-v8a.apk`** (almost every phone from the last few years) and open it on your phone. Older 32-bit phones need `armeabi-v7a`; `x86_64` is for emulators and Chromebooks.

**Updating from 1.1.3:** uninstall 1.1.3 first. Like 1.1.3, this version is signed with a temporary key, so Android refuses to install it over the previous one.

### What's new in 1.1.4

- **Proxied casts keep playing with the screen off.** With **Use local proxy**, the phone serves the video to the Chromecast for the whole film. With the screen off, Android can lose sight of the Chromecast or drop its connection to it for a moment; Snapcast took that as the end of the cast and shut the proxy down while the Chromecast was still playing from it, so the cast stopped once its buffer ran out (casts without the proxy were not affected). The proxy now stays up as long as the Chromecast uses it, and the phone stays awake to serve it while it plays, even after hours.
- **Pausing from the TV.** If you pause with the TV's remote while the phone is out of touch, the proxy waits half an hour for you to resume, and the phone stays awake for it again as soon as the Chromecast starts fetching.
- **A stream that fails restarts itself.** If the Chromecast gives up on a proxied stream (a Wi-Fi hiccup, a server that hung for a moment), Snapcast loads it again where it stopped, up to three times in ten minutes, before showing the error. An expired link is still reported straight away.
- **Honest notification.** While the phone is out of touch with the TV, the casting notification says "Not connected to …" and offers only **Stop**, and the volume keys control the phone again instead of a TV volume they could not reach. Everything comes back when the connection does.
- **Stop from anywhere.** **Stop casting** in the Cast dialog or Android's media output panel now ends the cast and the proxy at once, like Snapcast's own **Stop**.
- The proxy gets the same address back after a restart, and a proxy port changed in the settings while casting is used for the next cast.

### Known limitations

- DRM-protected streams (Netflix, Disney+, …) are detected but can't be cast; the app explains why.
- Sites that stream audio and video separately (YouTube, for example) can't be cast with the Default Media Receiver.
- Your phone and Chromecast must be on the same Wi-Fi network.
