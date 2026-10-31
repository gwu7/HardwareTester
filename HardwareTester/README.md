# Hardware Tester (Android, Kotlin)

Open this folder in Android Studio (Koala or newer), let Gradle sync, press Run
(or Build > Build APK) on your phone with USB debugging on.

Tests: speaker, microphone, vibration, flashlight, cameras, touchscreen, display
(dead pixels), volume buttons, accelerometer, gyroscope, magnetometer, proximity,
light, barometer, Wi-Fi, Bluetooth, NFC, GPS, fingerprint/biometrics, battery.

Automatic = result decided by the app (mic level, sensor data, camera open, GPS fix...).
Confirm = app triggers the hardware and asks you (speaker, vibration, flash, display).

Add your own test: add a `Row("Name") { done -> yourTest(done) }` in MainActivity.onCreate
and call `done(Result(Status.PASS/FAIL/SKIP, "detail"))` when finished.
