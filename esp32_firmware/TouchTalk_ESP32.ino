#include "BluetoothSerial.h"

// TouchTalk companion firmware for original ESP32 / ESP32-WROOM (Bluetooth Classic SPP).
// Keyboard pins follow the user's selected mapping. LED/output pins are separate.
BluetoothSerial SerialBT;

const uint8_t DOT_KEYS[6] = {16, 17, 18, 19, 21, 22};
const uint8_t SPACE_PIN = 23;
const uint8_t BACKSPACE_PIN = 25;
const uint8_t DOT_OUTPUTS[6] = {4, 5, 26, 27, 32, 33};
const unsigned long CHORD_GAP_MS = 100;
const unsigned long DEBOUNCE_MS = 25;

uint8_t pendingMask = 0;
bool wasDotDown = false;
unsigned long lastDotRelease = 0;
String currentWord = "";
String commandBuffer = "";
unsigned long lastSpaceChange = 0;
unsigned long lastBackspaceChange = 0;
bool lastSpaceRaw = HIGH;
bool lastBackspaceRaw = HIGH;

char decodeBraille(uint8_t m) {
  switch (m) {
    case 1: return 'a'; case 3: return 'b'; case 9: return 'c'; case 25: return 'd';
    case 17: return 'e'; case 11: return 'f'; case 27: return 'g'; case 19: return 'h';
    case 10: return 'i'; case 26: return 'j'; case 5: return 'k'; case 7: return 'l';
    case 13: return 'm'; case 29: return 'n'; case 21: return 'o'; case 15: return 'p';
    case 31: return 'q'; case 23: return 'r'; case 14: return 's'; case 30: return 't';
    case 37: return 'u'; case 39: return 'v'; case 58: return 'w'; case 45: return 'x';
    case 61: return 'y'; case 53: return 'z';
    default: return '?';
  }
}

void setBrailleOutputs(uint8_t mask) {
  for (uint8_t i = 0; i < 6; i++) digitalWrite(DOT_OUTPUTS[i], (mask & (1 << i)) ? HIGH : LOW);
}

void sendDraft() {
  SerialBT.print("DRAFT:");
  SerialBT.println(currentWord);
}

void commitWord() {
  if (currentWord.length() > 0) {
    SerialBT.print("TEXT:");
    SerialBT.println(currentWord);
    currentWord = "";
    sendDraft();
  }
}

void readPhoneCommands() {
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    if (c == '\r') continue;
    if (c == '\n') {
      commandBuffer.trim();
      if (commandBuffer.startsWith("CELL:")) {
        int value = commandBuffer.substring(5).toInt();
        setBrailleOutputs((uint8_t)(value & 0x3F));
      } else if (commandBuffer == "DONE") {
        // Leave the final cell active for tactile reading; uncomment to clear after a message.
        // setBrailleOutputs(0);
        SerialBT.println("STATUS:Message sequence complete");
      } else if (commandBuffer == "CLEAR") {
        setBrailleOutputs(0);
      }
      commandBuffer = "";
    } else if (commandBuffer.length() < 40) commandBuffer += c;
  }
}

void setup() {
  Serial.begin(115200);
  for (uint8_t i = 0; i < 6; i++) {
    pinMode(DOT_KEYS[i], INPUT_PULLUP);
    pinMode(DOT_OUTPUTS[i], OUTPUT);
    digitalWrite(DOT_OUTPUTS[i], LOW);
  }
  pinMode(SPACE_PIN, INPUT_PULLUP);
  pinMode(BACKSPACE_PIN, INPUT_PULLUP);
  SerialBT.begin("TouchTalk");
  Serial.println("TouchTalk ready. Pair Bluetooth device named TouchTalk.");
}

void loop() {
  readPhoneCommands();
  bool anyDotDown = false;
  uint8_t liveMask = 0;
  for (uint8_t i = 0; i < 6; i++) {
    if (digitalRead(DOT_KEYS[i]) == LOW) {
      anyDotDown = true;
      liveMask |= (1 << i);
    }
  }
  if (anyDotDown) {
    pendingMask |= liveMask;
    wasDotDown = true;
    lastDotRelease = millis();
  } else if (wasDotDown) {
    wasDotDown = false;
    lastDotRelease = millis();
  }
  if (!anyDotDown && pendingMask && millis() - lastDotRelease >= CHORD_GAP_MS) {
    char letter = decodeBraille(pendingMask);
    currentWord += letter;
    SerialBT.print("DRAFT:"); SerialBT.println(currentWord);
    pendingMask = 0;
  }

  bool spaceRaw = digitalRead(SPACE_PIN);
  if (lastSpaceRaw == HIGH && spaceRaw == LOW && millis() - lastSpaceChange > DEBOUNCE_MS) {
    lastSpaceChange = millis();
    commitWord();
    setBrailleOutputs(0);
  }
  lastSpaceRaw = spaceRaw;

  bool backRaw = digitalRead(BACKSPACE_PIN);
  if (lastBackspaceRaw == HIGH && backRaw == LOW && millis() - lastBackspaceChange > DEBOUNCE_MS) {
    lastBackspaceChange = millis();
    if (currentWord.length() > 0) currentWord.remove(currentWord.length() - 1);
    sendDraft();
  }
  lastBackspaceRaw = backRaw;
  delay(2);
}
