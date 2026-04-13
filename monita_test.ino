const int sensorPin = A0;  // 圧力センサを接続したアナログピン

const int sampleCount = 8;           // 1回の表示に使う平均サンプル数
const float attackSmoothing = 0.75f; // 立ち上がりは速く追従
const float releaseSmoothing = 0.18f;  // 下がるときは少し滑らかに
const float pressThreshold = 40.0f;  // 立ち上がり判定のしきい値
const float releaseThreshold = 25.0f;  // 次の立ち上がりを待てるようにする戻りしきい値

const uint8_t sixteenthNotesPerInterval = 8;  // 立ち上がり間隔を16分音符8個ぶんとして扱う
const unsigned long minRiseIntervalMs = 120;  // 誤検出防止
const unsigned long maxRiseIntervalMs = 4000; // あまりに遅い入力は無視
const float bpmSmoothing = 0.4f;              // BPM 表示の追従性

float filteredValue = 0.0f;
bool filterInitialized = false;
bool pressed = false;

unsigned long lastRiseTimeMs = 0;
float displayedBpm = 0.0f;
bool bpmInitialized = false;

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

void setup() {
  Serial.begin(115200);
}

void loop() {
  float sensorValue = readSensorAveraged();

  if (!filterInitialized) {
    filteredValue = sensorValue;
    filterInitialized = true;
  } else {
    float smoothing = sensorValue > filteredValue ? attackSmoothing : releaseSmoothing;
    filteredValue += (sensorValue - filteredValue) * smoothing;
  }

  unsigned long now = millis();

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

  // Serial Plotter 用: raw, filtered, bpm
  Serial.print(sensorValue);
  Serial.print(',');
  Serial.print(filteredValue);
  Serial.print(',');
  Serial.println(displayedBpm);

  delay(1);
}
