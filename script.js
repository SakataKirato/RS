const targetBpmInput = document.getElementById("targetBpm");
const toleranceBpmInput = document.getElementById("toleranceBpm");
const speechEnabledInput = document.getElementById("speechEnabled");
const connectButton = document.getElementById("connectButton");
const resetButton = document.getElementById("resetButton");
const clearLogButton = document.getElementById("clearLogButton");
const currentBpmElement = document.getElementById("currentBpm");
const deltaBpmElement = document.getElementById("deltaBpm");
const inputSourceElement = document.getElementById("inputSource");
const statusBadge = document.getElementById("statusBadge");
const logList = document.getElementById("logList");

let serialPort = null;
let serialReader = null;
let keepReading = false;
let lastSpokenStatus = "";
let lastSpeechAt = 0;

function getTargetBpm() {
  return Number(targetBpmInput.value) || 120;
}

function getToleranceBpm() {
  return Math.max(1, Number(toleranceBpmInput.value) || 3);
}

function formatNumber(value) {
  return Number.isFinite(value) ? value.toFixed(1) : "--";
}

function setStatus(kind, text) {
  statusBadge.className = `status ${kind}`;
  statusBadge.textContent = text;
}

function setInputSource(text) {
  inputSourceElement.textContent = text;
}

function speak(text) {
  if (!speechEnabledInput.checked || !("speechSynthesis" in window)) {
    return;
  }

  const now = performance.now();
  if (text === lastSpokenStatus && now - lastSpeechAt < 900) {
    return;
  }

  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = "ja-JP";
  utterance.rate = 1.05;
  utterance.pitch = 1.0;
  window.speechSynthesis.speak(utterance);

  lastSpokenStatus = text;
  lastSpeechAt = now;
}

function addLogEntry({ bpm, delta, judgement }) {
  const timestamp = new Date().toLocaleTimeString("ja-JP", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });

  const item = document.createElement("article");
  item.className = "log-item";
  item.innerHTML = `
    <div class="log-topline">
      <strong>${timestamp}</strong>
      <span class="log-judgement">${judgement}</span>
    </div>
    <span>推定 BPM: ${formatNumber(bpm)}</span>
    <span>目標との差: ${delta >= 0 ? "+" : ""}${formatNumber(delta)}</span>
  `;

  const empty = logList.querySelector(".log-empty");
  if (empty) {
    empty.remove();
  }

  logList.prepend(item);
}

function evaluateTempo(bpm) {
  const targetBpm = getTargetBpm();
  const tolerance = getToleranceBpm();
  const delta = bpm - targetBpm;

  currentBpmElement.textContent = formatNumber(bpm);
  deltaBpmElement.textContent = `${delta >= 0 ? "+" : ""}${formatNumber(delta)}`;

  let kind = "ok";
  let text = "ちょうど良いです";
  let shouldSpeak = false;

  if (delta > tolerance) {
    kind = "fast";
    text = "早いです";
    shouldSpeak = true;
  } else if (delta < -tolerance) {
    kind = "slow";
    text = "遅いです";
    shouldSpeak = true;
  }

  setStatus(kind, text);
  if (shouldSpeak) {
    speak(text);
  }
  addLogEntry({ bpm, delta, judgement: text });
}

function handleSerialLine(line) {
  const parts = line.trim().split(",");
  if (parts.length < 3) {
    return;
  }

  const bpm = Number(parts[2]);
  if (!Number.isFinite(bpm) || bpm <= 0) {
    return;
  }

  evaluateTempo(bpm);
}

async function disconnectSerial() {
  keepReading = false;

  if (serialReader) {
    try {
      await serialReader.cancel();
    } catch (_) {
      // ignore
    }
    serialReader.releaseLock();
    serialReader = null;
  }

  if (serialPort) {
    try {
      await serialPort.close();
    } catch (_) {
      // ignore
    }
    serialPort = null;
  }

  connectButton.textContent = "センサ接続";
  setInputSource("未接続");
  setStatus("neutral", "センサ待機中");
}

async function readSerialLoop() {
  const decoder = new TextDecoder();
  let buffer = "";

  while (serialPort?.readable && keepReading) {
    serialReader = serialPort.readable.getReader();
    try {
      while (keepReading) {
        const { value, done } = await serialReader.read();
        if (done) {
          break;
        }

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split(/\r?\n/);
        buffer = lines.pop() || "";

        for (const line of lines) {
          handleSerialLine(line);
        }
      }
    } finally {
      serialReader.releaseLock();
      serialReader = null;
    }
  }
}

async function connectSerial() {
  if (!("serial" in navigator)) {
    setStatus("slow", "このブラウザは Web Serial 非対応です");
    return;
  }

  if (serialPort) {
    await disconnectSerial();
    return;
  }

  try {
    serialPort = await navigator.serial.requestPort();
    await serialPort.open({ baudRate: 115200 });
    keepReading = true;
    connectButton.textContent = "切断";
    setInputSource("圧力センサ");
    setStatus("neutral", "シリアル受信中");
    readSerialLoop();
  } catch (error) {
    serialPort = null;
    setInputSource("未接続");
    setStatus("slow", `接続失敗: ${error.message}`);
  }
}

function resetSession() {
  currentBpmElement.textContent = "--";
  deltaBpmElement.textContent = "--";
  setStatus(serialPort ? "neutral" : "neutral", serialPort ? "シリアル受信中" : "センサ待機中");
}

connectButton.addEventListener("click", connectSerial);
resetButton.addEventListener("click", resetSession);
clearLogButton.addEventListener("click", () => {
  logList.innerHTML = '<p class="log-empty">まだログはありません。</p>';
});

targetBpmInput.addEventListener("change", () => {
  targetBpmInput.value = String(Math.min(300, Math.max(20, getTargetBpm())));
});

toleranceBpmInput.addEventListener("change", () => {
  toleranceBpmInput.value = String(Math.min(30, Math.max(1, getToleranceBpm())));
});

window.addEventListener("beforeunload", () => {
  if (serialPort) {
    disconnectSerial();
  }
});
