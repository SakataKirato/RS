const int sensorPin = A0; // 圧力センサを接続したアナログピン

const int sampleCount = 8; // 1回の表示に使う平均サンプル数
const float attackSmoothing = 0.75f;  // 立ち上がりは速く追従
const float releaseSmoothing = 0.18f; // 下がるときは少し滑らかに
const float pressThreshold = 40.0f;   // 立ち上がり判定のしきい値
const float releaseThreshold =
    25.0f; // 次の立ち上がりを待てるようにする戻りしきい値

const uint8_t sixteenthNotesPerInterval =
    8; // 立ち上がり間隔を16分音符8個ぶんとして扱う
const unsigned long minRiseIntervalMs = 120; // 誤検出防止
const unsigned long maxRiseIntervalMs = 4000; // あまりに遅い入力は無視
const float bpmSmoothing = 0.8f;              // BPM 表示の追従性
const size_t commandBufferSize = 16;

float filteredValue = 0.0f;
bool filterInitialized = false;
bool pressed = false;
bool waitingForReleaseAfterReset = false;

unsigned long lastRiseTimeMs = 0;
float displayedBpm = 0.0f;
bool bpmInitialized = false;
char commandBuffer[commandBufferSize];
size_t commandLength = 0;

float readSensorAveraged() {
  long total = 0;
  for (int i = 0; i < sampleCount; i++) {
    total += analogRead(sensorPin);
  }
  return total / (float)sampleCount;
}

void updateDisplayedBpm(float bpm) {
  if (!bpmInitialized) {
    displayedBpm = bpm;
    bpmInitialized = true;
  } else {
    displayedBpm += (bpm - displayedBpm) * bpmSmoothing;
  }
}

void resetMeasurementState() {
  lastRiseTimeMs = 0;
  displayedBpm = 0.0f;
  bpmInitialized = false;
  filterInitialized = false;
  pressed = false;
  waitingForReleaseAfterReset = true;
}

void handleCommand(const char *command) {
  if (strcmp(command, "RESET") == 0) {
    resetMeasurementState();
    Serial.println("RESET_ACK");
  }
}

void pollSerialCommands() {
  while (Serial.available() > 0) {
    char incoming = (char)Serial.read();

    if (incoming == '\r' || incoming == '\n') {
      if (commandLength > 0) {
        commandBuffer[commandLength] = '\0';
        handleCommand(commandBuffer);
        commandLength = 0;
      }
      continue;
    }

    if (commandLength < commandBufferSize - 1) {
      commandBuffer[commandLength++] = incoming;
    } else {
      commandLength = 0;
    }
  }
}

void setup() { Serial.begin(115200); }

void loop() {
  pollSerialCommands();
  float sensorValue = readSensorAveraged();

  if (!filterInitialized) {
    filteredValue = sensorValue;
    filterInitialized = true;
  } else {
    float smoothing =
        sensorValue > filteredValue ? attackSmoothing : releaseSmoothing;
    filteredValue += (sensorValue - filteredValue) * smoothing;
  }

  unsigned long now = millis();

  if (waitingForReleaseAfterReset) {
    if (filteredValue <= releaseThreshold) {
      waitingForReleaseAfterReset = false;
    }
  } else {
    // 立ち上がりを1回だけ拾うためのヒステリシス付きしきい値判定
    if (!pressed && filteredValue >= pressThreshold) {
      pressed = true;

      if (lastRiseTimeMs != 0) {
        unsigned long intervalMs = now - lastRiseTimeMs;
        if (intervalMs >= minRiseIntervalMs && intervalMs <= maxRiseIntervalMs) {
          float quarterNotesPerInterval = sixteenthNotesPerInterval / 4.0f;
          float bpm = (60000.0f * quarterNotesPerInterval) / intervalMs;
          updateDisplayedBpm(bpm);
        }
      }

      lastRiseTimeMs = now;
    } else if (pressed && filteredValue <= releaseThreshold) {
      pressed = false;
    }
  }

  // Serial Plotter 用: raw, filtered, bpm
  Serial.print(sensorValue);
  Serial.print(',');
  Serial.print(filteredValue);
  Serial.print(',');
  Serial.println(displayedBpm);

  delay(1);
}
