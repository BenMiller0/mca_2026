import os
import queue
import threading
import time
from pathlib import Path
from flask import Flask, Response, redirect, render_template, request, send_from_directory, stream_with_context, url_for
import paho.mqtt.client as mqtt

# ── Config ────────────────────────────────────────────────────────────────────
MQTT_BROKER   = "172.20.10.5"
MQTT_PORT     = 1883
MQTT_TOPIC    = "spell/cast"
SPELL_MAP     = {"1": "PUSH", "2": "LUMOS", "3": "SUMMON"}

app = Flask(__name__, static_folder="assets", static_url_path="/assets")
app.config["TEMPLATES_AUTO_RELOAD"] = True
app.config["SEND_FILE_MAX_AGE_DEFAULT"] = 0
LEGACY_STATIC_DIR = Path(__file__).resolve().parent / "static"

# Each SSE client gets its own queue; broadcast pushes to all of them.
_client_queues: list[queue.Queue] = []
_lock = threading.Lock()


def broadcast(data: str):
    with _lock:
        dead = []
        for q in _client_queues:
            try:
                q.put_nowait(data)
            except queue.Full:
                dead.append(q)
        for q in dead:
            _client_queues.remove(q)


# ── MQTT callbacks ────────────────────────────────────────────────────────────

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print(f"[MQTT] Connected to {MQTT_BROKER}:{MQTT_PORT}")
        client.subscribe(MQTT_TOPIC)
        print(f"[MQTT] Subscribed to '{MQTT_TOPIC}'")
    else:
        print(f"[MQTT] Connection failed: rc={rc}")


def on_message(client, userdata, msg):
    payload = msg.payload.decode("utf-8", errors="replace").strip()
    spell   = SPELL_MAP.get(payload, f"UNKNOWN({payload})")
    ts      = time.strftime("%H:%M:%S")
    print(f"[MQTT] {ts}  topic={msg.topic}  payload={payload!r}  → {spell}")
    broadcast(f"{ts}|{spell}|{payload}")


def on_disconnect(client, userdata, rc):
    print(f"[MQTT] Disconnected (rc={rc}), reconnecting…")


# ── MQTT thread ───────────────────────────────────────────────────────────────

def start_mqtt():
    client = mqtt.Client(client_id="flask-wand-subscriber", protocol=mqtt.MQTTv311)
    client.on_connect    = on_connect
    client.on_message    = on_message
    client.on_disconnect = on_disconnect

    while True:
        try:
            client.connect(MQTT_BROKER, MQTT_PORT, keepalive=60)
            client.loop_forever()
        except Exception as e:
            print(f"[MQTT] Connection error: {e} — retrying in 5 s")
            time.sleep(5)


threading.Thread(target=start_mqtt, daemon=True).start()


# ── Flask routes ──────────────────────────────────────────────────────────────

@app.route("/front")
def front():
    return redirect(url_for("index", takeover=1))


@app.route("/static/<path:filename>")
def legacy_static(filename):
    return send_from_directory(LEGACY_STATIC_DIR, filename)


@app.route("/")
def index():
    return render_template("index.html",
                           broker=MQTT_BROKER,
                           port=MQTT_PORT,
                           topic=MQTT_TOPIC,
                           takeover_on_load=request.args.get("takeover") == "1")


@app.route("/stream")
def stream():
    """Server-Sent Events endpoint — one event per MQTT message."""
    q: queue.Queue = queue.Queue(maxsize=50)
    with _lock:
        _client_queues.append(q)

    def generate():
        try:
            # Send a heartbeat every 15 s so proxies don't drop the connection.
            while True:
                try:
                    data = q.get(timeout=15)
                    yield f"data: {data}\n\n"
                except queue.Empty:
                    yield ": heartbeat\n\n"
        finally:
            with _lock:
                if q in _client_queues:
                    _client_queues.remove(q)

    return Response(
        stream_with_context(generate()),
        mimetype="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@app.after_request
def disable_html_caching(response):
    if response.mimetype in {"text/html", "text/event-stream"}:
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "5000")), debug=False)
