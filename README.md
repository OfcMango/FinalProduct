# TouchTalk Android App + ESP32 Firmware

A two-way prototype for an original ESP32 / ESP32-WROOM using Bluetooth Classic SPP.

## Features
- Receive completed Braille-keyboard words from ESP32 and show them in the app.
- Optional phone text-to-speech for incoming words/messages.
- Tap-to-speak using the phone's installed speech-recognition service; edit the recognized text.
- Convert uncontracted English Braille letters, digits, spaces and common punctuation into six-bit dot masks.
- Send each cell as `CELL:<mask>\n` over Bluetooth; output pin bit 0 = dot 1 through bit 5 = dot 6.
- Includes a companion ESP32 sketch that reads keypad input and drives six LED test outputs.

## Build APK without Android Studio
1. Create a GitHub repository and upload the contents of this folder (including `.github`).
2. Open **Actions** and run **Build TouchTalk APK** (or push to main/master).
3. Download the `TouchTalk-apk` artifact and extract `app-debug.apk`.
4. Transfer it to the Android phone and install it; allow installation from that source if prompted.

## Build with Android Studio
Open this folder as a Gradle project in Android Studio (JDK 17; Android SDK 35 installed), sync Gradle, then choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## ESP32 setup
1. Open `esp32_firmware/TouchTalk_ESP32.ino` in Arduino IDE.
2. Select the original ESP32 / ESP32-WROOM board and install the ESP32 Arduino board package.
3. Upload the sketch. Pair `TouchTalk` in Android Bluetooth settings.
4. Open TouchTalk, tap **Connect**, and choose `TouchTalk`.
5. For LED testing, use six LEDs with individual 220–1kΩ series resistors from GPIO 4, 5, 26, 27, 32, 33 to GND. Do not connect electromagnets directly to GPIO pins. Each actuator requires a suitable transistor/MOSFET driver, a correctly rated supply, and flyback protection.

## Key map
- Braille dots 1–6: GPIO 16, 17, 18, 19, 21, 22.
- Space: GPIO 23. Backspace: GPIO 25.
- Six separate output/test pins: GPIO 4, 5, 26, 27, 32, 33.

## Bluetooth text protocol
- ESP32 -> phone: `DRAFT:<current-word>` updates the keypad preview; `TEXT:<word>` commits the word and triggers optional speech.
- Phone -> ESP32: `CELL:<0..63>` updates the six output pins; `DONE` marks the end of a message; `CLEAR` switches all six outputs off.
- The dot mask is a bit field: bit 0 is dot 1, bit 1 is dot 2, ... bit 5 is dot 6.

## Important limitations / test notes
- This is a first functional prototype, not a production-tested app. It uses Android's installed speech-recognition activity; whether recognition works offline depends on the phone's speech service and downloaded language packs.
- Current keyboard firmware commits a word when Space is pressed. It decodes basic uncontracted English letter patterns; the current firmware sends `?` for unsupported Braille combinations. The phone-side speech-to-Braille conversion supports common punctuation and standard number/capital indicators.
- For now the six output pins are LED test outputs. Electromagnet or solenoid pins must use proper drivers and a separate suitable power stage; do not drive coils directly from ESP32 pins.
- Output pins are separate from the chosen keyboard GPIOs. Confirm your exact ESP32 board and wiring before connecting hardware.
