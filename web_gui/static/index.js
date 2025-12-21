// =======================
// BLUETOOTH CONSTANTS
// =======================
const SERVICE_UUID = "12345678-1234-1234-1234-1234567890ab";
const CHAR_UUID = "abcd1234-1234-1234-1234-abcdef123456";
const DEVICE_NAME = "CarRover";

let bleCharacteristic = null;
let deviceGlobal = null;
let connected = false;

let x = 0;
let y = 0;
let lastSend = 0;
const SEND_INTERVAL = 200;
let sentCenter = false;

// =======================
// JOYSTICK ELEMENTS
// =======================
const container = document.getElementById("joystick-container");
const stick = document.getElementById("stick");
const readout = document.getElementById("readout");
const honkBtn = document.getElementById("honk");
const connectBtn = document.getElementById("Connect");
const speedSlider = document.getElementById('speed');
const driftSlider = document.getElementById('drift');
const speedVal = document.getElementById('speed-val');
const driftVal = document.getElementById('drift-val');

if (speedSlider) speedVal.textContent = speedSlider.value;
if (driftSlider) driftVal.textContent = Number(driftSlider.value).toFixed(2);

let dragging = false;
const radius = container.clientWidth / 2;
const stickRadius = stick.clientWidth / 2;

setConnected(false);

// =======================
// BLUETOOTH CONNECT
// =======================
async function ConnectBT() {
    try {
        const device = await navigator.bluetooth.requestDevice({
            filters: [{ services: [SERVICE_UUID] }],
            optionalServices: [SERVICE_UUID]
        });

        deviceGlobal = device;
        device.addEventListener('gattserverdisconnected', onDisconnected);

        const server = await device.gatt.connect();
        const service = await server.getPrimaryService(SERVICE_UUID);
        bleCharacteristic = await service.getCharacteristic(CHAR_UUID);

        setConnected(true);
        alert("Bluetooth connected!");
    } catch (err) {
        console.error("Bluetooth connect failed:", err);
        alert("Failed to connect: " + (err && err.message ? err.message : err));
        setConnected(false);
    }
}

function onDisconnected(evt) {
    console.log("Device disconnected", evt);
    alert("Bluetooth disconnected");
    setConnected(false);
}

// allow manual connect button
connectBtn.addEventListener("click", () => {
    if (!connected) ConnectBT();
});

// auto-connect on first click anywhere
document.body.addEventListener("click", () => {
    if (!bleCharacteristic && !connected) ConnectBT();
}, { once: true });

// update sliders
speedSlider.addEventListener('input', () => {
  speedVal.textContent = speedSlider.value;
});

driftSlider.addEventListener('input', () => {
  driftVal.textContent = Number(driftSlider.value).toFixed(2);
});


// =======================
// UI STATE HANDLER
// =======================
function setConnected(state) {
    connected = state;

    if (state) {
        connectBtn.textContent = "Connected";
        connectBtn.disabled = true;
        connectBtn.classList.add('connected');

        container.classList.remove('disabled');
        honkBtn.disabled = false;
        
        speedSlider.disabled = false;
        driftSlider.disabled = false;

    } else {
        connectBtn.textContent = "Connect";
        connectBtn.disabled = false;
        connectBtn.classList.remove('connected');

        container.classList.add('disabled');
        honkBtn.disabled = true;

        dragging = false;
        stick.style.left = "50%";
        stick.style.top = "50%";
        x = 0; y = 0;
        // force send center so vehicle stops
        sendControl(0, true);
        sentCenter = true;

        bleCharacteristic = null;
        
        speedSlider.disabled = true;
        driftSlider.disabled = true;
    }
}

// =======================
// SEND DATA
// =======================
function sendControl(honk = 0, force = false) {
    if (bleCharacteristic) {
        try {
            const speedSliderVal = parseInt(speedSlider.value, 10);
            const driftSliderVal = parseFloat(driftSlider.value);
            
            console.log(speedSlider.value)
            console.log(driftSlider.value)
            
            let speed = y * speedSliderVal;
            let drift = driftSliderVal * x;
            
            let vLeft = Math.abs(speed);
            let vRight = vLeft;
            
              if (drift >= 0)
                vRight = vRight * (1 - drift);
            else
                vLeft = vLeft * (1 - Math.abs(drift));
            
            const dataString = `${vLeft},${vRight},${Math.sign(y)},${honk}`;
            const encoder = new TextEncoder();
            bleCharacteristic.writeValue(encoder.encode(dataString))
                .catch(error => console.log("BLE send error", error));
        } catch (e) {
            console.warn("BLE write failed:", e);
        }
    }

    sendToServer(force, honk);
}

honkBtn.addEventListener("click", () => {
    if (!connected) return;
    sendControl(1);
});

// =======================
// JOYSTICK LOGIC
// =======================
function updateStick(px, py) {
    const maxRange = radius - stickRadius;

    stick.style.left = `${px + radius}px`;
    stick.style.top = `${py + radius}px`;

    x = px / maxRange;
    y = -py / maxRange;

    x = Math.max(-1, Math.min(1, x));
    y = Math.max(-1, Math.min(1, y));

    x = Number(x.toFixed(2));
    y = Number(y.toFixed(2));

    readout.textContent = `x: ${x} | y: ${y}`;

    // Only send while joystick is NOT centered.
    if (x !== 0 || y !== 0) {
        sentCenter = false;
        sendControl(0, false);
    }
}

// only attach pointer handlers
container.addEventListener("pointerdown", e => {
    if (container.classList.contains('disabled')) return;
    dragging = true;
    container.setPointerCapture(e.pointerId);
});

container.addEventListener("pointermove", e => {
    if (!dragging) return;
    if (container.classList.contains('disabled')) return;

    const rect = container.getBoundingClientRect();
    let px = e.clientX - rect.left - radius;
    let py = e.clientY - rect.top - radius;

    const dist = Math.hypot(px, py);
    if (dist > radius - stickRadius) {
        const angle = Math.atan2(py, px);
        px = Math.cos(angle) * (radius - stickRadius);
        py = Math.sin(angle) * (radius - stickRadius);
    }

    updateStick(px, py);
});

container.addEventListener("pointerup", () => {
    if (container.classList.contains('disabled')) return;

    dragging = false;
    stick.style.left = "50%";
    stick.style.top = "50%";

    x = 0;
    y = 0;

    if (!sentCenter) {
        sendControl(0, true); // force send of center
        sentCenter = true;
    }
});

// =======================
// Server-sending with throttle + force override
// =======================
function sendToServer(force = false, honk = 0) {
    const now = Date.now();

    if (!force && (now - lastSend < SEND_INTERVAL)) return;
    lastSend = now;

    fetch("/control", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ x: x, y: y, honk: honk })
    }).catch(err => console.error("Server error:", err));
}

const modal = document.getElementById("logModal");
const logContainer = document.getElementById("log-container");

async function openModal() {
    modal.style.display = "block";
    logContainer.innerHTML = "Fetching...";
    
    try {
        const response = await fetch("/logs");
        const data = await response.json();
        
        logContainer.innerHTML = data.map(entry => `
            <div class="log-entry">
                [${entry.time}] X: ${entry.x} | Y: ${entry.y}
            </div>
        `).join('') || "No logs found.";
    } catch (err) {
        logContainer.innerHTML = "Error loading logs.";
    }
}

function closeModal() {
    modal.style.display = "none";
}

window.onclick = function(event) {
    if (event.target == modal) closeModal();
}
