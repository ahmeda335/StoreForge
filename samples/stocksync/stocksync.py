#!/usr/bin/env python3
"""StockSync: a sample third-party app for StoreForge (Python standard library only).

It keeps a "warehouse" (a JSON file) in sync with a store, from the OUTSIDE, the way any integration would:

  serve                       run the app: /install, /oauth/callback, /webhooks
  restock <item-id> <qty>     the warehouse received stock -> adjust the store (idempotent, backs off on 429)
  reconcile                   catch up via the "changed since" feed (webhooks are hints; this is the truth)
  status                      what the app knows

Environment: STOREFORGE_URL (http://localhost:8080), CLIENT_ID, CLIENT_SECRET, APP_URL (http://127.0.0.1:9095),
STATE_FILE (stocksync-state.json).
"""
import base64, hashlib, hmac, http.server, json, os, secrets, sys, time, urllib.error, urllib.parse, urllib.request, uuid

STOREFORGE = os.environ.get("STOREFORGE_URL", "http://localhost:8080")
CLIENT_ID = os.environ.get("CLIENT_ID", "")
CLIENT_SECRET = os.environ.get("CLIENT_SECRET", "")
APP_URL = os.environ.get("APP_URL", "http://127.0.0.1:9095")
STATE_FILE = os.environ.get("STATE_FILE", "stocksync-state.json")
API_VERSION = "2026-10"
SCOPES = "read_orders read_inventory write_inventory"
pending_installs = {}   # state -> {"verifier": ..., "store": ...}   (in memory: a real app would use its session store)


# ---- persistence (a real app has a database) -------------------------------------------------------
def load():
    try:
        with open(STATE_FILE) as f:
            return json.load(f)
    except FileNotFoundError:
        return {"installations": {}, "seen_events": [], "shipments": [], "levels": {}, "last_sync": None}


def save(state):
    with open(STATE_FILE + ".tmp", "w") as f:
        json.dump(state, f, indent=2)
    os.replace(STATE_FILE + ".tmp", STATE_FILE)


def log(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


# ---- calling StoreForge ---------------------------------------------------------------------------
def api(method, path, token, body=None, headers=None, attempts=6):
    """Developer API call. 429 -> wait Retry-After and retry; network errors -> retry (same Idempotency-Key)."""
    data = None if body is None else json.dumps(body).encode()
    for attempt in range(1, attempts + 1):
        request = urllib.request.Request(STOREFORGE + path, data=data, method=method, headers={
            "Authorization": "Bearer " + token, "Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status, json.loads(response.read() or b"null")
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < attempts:
                wait = int(e.headers.get("Retry-After", "1"))
                log(f"429 rate limited; waiting {wait}s as told (attempt {attempt})")
                time.sleep(wait)
                continue
            return e.code, json.loads(e.read() or b"null")
        except urllib.error.URLError as e:
            if attempt < attempts:
                log(f"network error ({e.reason}); retrying with the same Idempotency-Key")
                time.sleep(min(2 ** attempt, 30))
                continue
            raise


def store_path(installation, resource):
    return f"/api/{API_VERSION}/stores/{installation['store_id']}{resource}"


# ---- OAuth: install -------------------------------------------------------------------------------
def install_redirect(store_host):
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)                      # CSRF protection for the callback
    pending_installs[state] = {"verifier": verifier, "store": store_host}
    return STOREFORGE + "/oauth2/authorize?" + urllib.parse.urlencode({
        "response_type": "code", "client_id": CLIENT_ID, "redirect_uri": APP_URL + "/oauth/callback", "scope": SCOPES,
        "state": state, "code_challenge": challenge, "code_challenge_method": "S256", "store": store_host})


def complete_install(code, state):
    pending = pending_installs.pop(state, None)
    if pending is None:
        raise ValueError("unknown state: not an install we started (possible CSRF)")
    form = urllib.parse.urlencode({"grant_type": "authorization_code", "code": code, "redirect_uri": APP_URL + "/oauth/callback",
                                   "code_verifier": pending["verifier"]}).encode()
    basic = base64.b64encode(f"{urllib.parse.quote(CLIENT_ID)}:{urllib.parse.quote(CLIENT_SECRET)}".encode()).decode()
    request = urllib.request.Request(STOREFORGE + "/oauth2/token", data=form, headers={
        "Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(request, timeout=15) as response:
        tokens = json.loads(response.read())
    installation = {"store_id": tokens["store_id"], "store": pending["store"], "token": tokens["access_token"],
                    "scopes": tokens["scope"].split(" "), "webhook_secrets": []}
    for topic in ("orders/paid", "inventory_levels/update"):
        status, subscription = api("POST", store_path(installation, "/webhook-subscriptions"), installation["token"],
                                   {"url": APP_URL + "/webhooks", "topics": [topic]})
        if status != 201:
            raise RuntimeError(f"could not subscribe to {topic}: {status} {subscription}")
        installation["webhook_secrets"].append(subscription["secret"])
    state_data = load()
    state_data["installations"][installation["store_id"]] = installation
    save(state_data)
    log(f"installed on {pending['store']} (store {installation['store_id']}), subscribed to orders/paid and inventory_levels/update")
    return installation


# ---- webhooks: the receiver checklist ---------------------------------------------------------------
def handle_webhook(headers, raw):
    state = load()
    installation = state["installations"].get(headers.get("X-StoreForge-Store-Id", ""))
    if installation is None:
        return 404, "unknown store"
    timestamp = headers.get("X-StoreForge-Timestamp", "0")
    signatures = [p.strip()[3:] for p in headers.get("X-StoreForge-Signature", "").split(",") if p.strip().startswith("v1=")]
    expected = [hmac.new(s.encode(), timestamp.encode() + b"." + raw, hashlib.sha256).hexdigest() for s in installation["webhook_secrets"]]
    if not any(hmac.compare_digest(e, s) for e in expected for s in signatures):   # 1. signature over the raw body
        return 400, "bad signature"
    if abs(time.time() - int(timestamp)) > 300:                                     # 2. replay window
        return 400, "stale"
    event_id = headers["X-StoreForge-Event-Id"]
    if event_id in state["seen_events"]:                                            # 3. dedup by event id
        log(f"duplicate {headers['X-StoreForge-Topic']} {event_id}: ignored")
        return 200, "duplicate"
    event = json.loads(raw)
    if event["topic"] == "orders/paid":                                             # 5. don't trust order: act per event
        state["shipments"].append({"order": event["data"]["name"], "orderId": event["data"]["orderId"], "at": event["createdAt"]})
        log(f"orders/paid {event['data']['name']} ({event['data']['total']['amount']} {event['data']['total']['currency']}) -> shipment recorded")
    elif event["topic"] == "inventory_levels/update":
        d = event["data"]
        state["levels"][d["inventoryItemId"]] = d["onHand"]
        log(f"inventory_levels/update item {d['inventoryItemId'][:8]}… onHand={d['onHand']} ({d['reason']} {d['delta']:+d})")
    state["seen_events"] = (state["seen_events"] + [event_id])[-5000:]
    save(state)
    return 200, "ok"                                                                # 4. answer fast


class App(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        query = dict(urllib.parse.parse_qsl(url.query))
        if url.path == "/install" and "store" in query:
            self.send_response(302); self.send_header("Location", install_redirect(query["store"])); self.end_headers()
        elif url.path == "/oauth/callback":
            if "error" in query:
                self.reply(400, f"Installation refused: {query['error']}")
                return
            try:
                installation = complete_install(query["code"], query["state"])
                self.reply(200, f"StockSync installed on store {installation['store']}.")
            except Exception as e:
                self.reply(400, f"Installation failed: {e}")
        else:
            self.reply(404, "not found")

    def do_POST(self):
        if self.path != "/webhooks":
            self.reply(404, "not found")
            return
        raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        status, text = handle_webhook(self.headers, raw)
        self.reply(status, text)

    def reply(self, status, text):
        body = text.encode()
        self.send_response(status); self.send_header("Content-Type", "text/plain"); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def log_message(self, *args):
        pass


# ---- commands ----------------------------------------------------------------------------------------
def only_installation():
    installations = load()["installations"]
    if len(installations) != 1:
        sys.exit("expected exactly one installation; install first (serve, then open /install?store=<host>)")
    return next(iter(installations.values()))


def restock(item_id, quantity):
    installation = only_installation()
    key = str(uuid.uuid4())   # ONE key for this restock: every retry repeats it, so the store adds the stock once
    status, body = api("POST", store_path(installation, f"/inventory-items/{item_id}/adjustments"), installation["token"],
                       {"delta": int(quantity), "reason": "RECEIVED", "note": "StockSync restock"}, {"Idempotency-Key": key})
    log(f"restock {item_id[:8]}… +{quantity}: HTTP {status} {body}")


def reconcile():
    state = load()
    installation = only_installation()
    since = state.get("last_sync") or "1970-01-01T00:00:00Z"
    cursor, changed, newest = None, 0, since
    while True:
        query = {"updatedAfter": since, "limit": "50", **({"cursor": cursor} if cursor else {})}
        status, page = api("GET", store_path(installation, "/inventory-items?" + urllib.parse.urlencode(query)), installation["token"])
        if status != 200:
            sys.exit(f"reconcile failed: {status} {page}")
        for item in page["data"]:
            state["levels"][item["id"]] = item["onHand"]
            changed += 1
        if not page["page"]["hasMore"]:
            break
        cursor = page["page"]["nextCursor"]
    state["last_sync"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    save(state)
    log(f"reconcile: {changed} item(s) changed since {since}")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "serve"
    if command == "serve":
        port = int(urllib.parse.urlparse(APP_URL).port or 9095)
        log(f"StockSync listening on {APP_URL} (install: {APP_URL}/install?store=<store host>)")
        http.server.ThreadingHTTPServer(("127.0.0.1", port), App).serve_forever()
    elif command == "restock":
        restock(sys.argv[2], sys.argv[3])
    elif command == "reconcile":
        reconcile()
    elif command == "status":
        print(json.dumps({k: v for k, v in load().items() if k != "installations"} | {"installations": list(load()["installations"])}, indent=2))
    else:
        sys.exit(__doc__)
