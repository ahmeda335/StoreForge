"""Creates the load-test tenants THROUGH THE API (owners, stores, a shipping rate, an API key), then
writes load/data/stores.json for the SQL seed and the k6 scenarios. Sizes: 1 large, 9 medium, 40 small.

Usage: python3 load/seed/create-stores.py   (app running against storeforge_load, Mailpit up)"""
import json, random, re, time, urllib.parse, urllib.request, uuid, os

BASE = "http://localhost:8080"
PASSWORD = "load-test password 2026"


def call(method, path, body=None, headers=None):
    request = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read() or b"null")


def verification_token(email):
    for _ in range(80):
        found = json.load(urllib.request.urlopen("http://localhost:8025/api/v1/search?query=" + urllib.parse.quote("to:" + email)))["messages"]
        if found:
            text = json.load(urllib.request.urlopen(f"http://localhost:8025/api/v1/message/{found[0]['ID']}"))["Text"]
            return re.search(r"token=([A-Za-z0-9_-]+)", text).group(1)
        time.sleep(0.25)
    raise RuntimeError("no verification email for " + email)


def store(index, size):
    client = {"X-Forwarded-For": f"10.{index // 250}.{index % 250}.7"}   # one address per owner: login limits are per IP
    email = f"owner-{index}-{uuid.uuid4().hex[:6]}@load.test"
    call("POST", "/auth/v1/registrations", {"email": email, "password": PASSWORD}, client)
    call("POST", "/auth/v1/email-verifications/confirmation", {"token": verification_token(email)}, client)
    token = call("POST", "/auth/v1/token", {"email": email, "password": PASSWORD}, client)["access_token"]
    auth = {"Authorization": "Bearer " + token, **client}
    handle = f"load{index}-{uuid.uuid4().hex[:4]}"
    created = call("POST", "/admin/v1/stores", {"handle": handle, "name": f"Load store {index}", "currency": "USD"}, auth)
    sid = created["id"]
    call("POST", f"/admin/v1/stores/{sid}/shipping-rates", {"name": "Standard", "price": "4.90", "freeOver": "75.00"}, auth)
    key = call("POST", f"/admin/v1/stores/{sid}/api-keys", {"name": "load", "scopes": ["read_orders", "read_products"]}, auth)
    return {"id": sid, "handle": handle, "host": f"{handle}.storeforge.localhost", "size": size, "ownerEmail": email,
            "accessToken": token, "apiKey": key["token"]}


sizes = ["large"] + ["medium"] * 9 + ["small"] * 40
stores = [store(i, size) for i, size in enumerate(sizes)]
os.makedirs("load/data", exist_ok=True)
json.dump({"password": PASSWORD, "stores": stores}, open("load/data/stores.json", "w"), indent=1)
print(f"{len(stores)} stores created")
